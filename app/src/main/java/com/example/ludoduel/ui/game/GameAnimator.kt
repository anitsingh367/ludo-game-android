package com.example.ludoduel.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.LastAction
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.YARD
import com.example.ludoduel.ui.components.PillState
import com.example.ludoduel.ui.theme.LudoPalette
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** How one token is drawn right now. Written by [GameAnimator], read while drawing. */
@Stable
class TokenVisual(position: Offset, scale: Float) {
    /** Center of the token's square in grid units, viewer's orientation. */
    var pos by mutableStateOf(position)
    var scale by mutableFloatStateOf(scale)
    var liftPx by mutableFloatStateOf(0f)
    /** 0..1 grow while in the air (1.15x at the top of a hop). */
    var hop by mutableFloatStateOf(0f)
    var squash by mutableFloatStateOf(0f)
    var flash by mutableFloatStateOf(0f)

    fun look() = PawnLook(scale = scale * (1f + 0.15f * hop), liftPx = liftPx, squash = squash, flash = flash)
}

/** Texts for the messages the animations show (resolved from string resources by the screen). */
data class AnimatorTexts(
    val plusOneTurn: String,
    val noMoves: String,
    val threeSixes: String,
    val captured: String,
    val yourTurn: String,
    /** Format with the player's name. */
    val playersTurn: String,
    /** Format with the player's name. */
    val ranOutOfTime: String,
)

/**
 * Plays the game on screen, one server update at a time.
 *
 * The screen hands every game state it receives to [submit]. Each state that is exactly the next
 * version is animated from its `lastAction`: roll -> move -> capture -> home -> next turn, in that
 * order, and only then does [shown] (what the board, panels and dice display) move on. Anything
 * else (a jump after reconnecting, a new game, or a long backlog) snaps straight to the latest
 * state. [busy] is true while an animation plays; the screen allows no input until it is false
 * and [shown] is the latest state, so the board never runs ahead of what the player sees.
 */
@Stable
class GameAnimator(
    val viewer: PlayerColor,
    initial: RoomGame,
    private val density: Density,
    private val palette: LudoPalette,
) {
    var fx: GameFx = SilentFx
    var texts = AnimatorTexts("", "", "", "", "", "%1\$s", "%1\$s")
    var nameOf: (PlayerColor) -> String = { "" }

    val pills = PillState()
    val effects = BoardEffects()

    var shown by mutableStateOf(initial)
        private set
    var busy by mutableStateOf(false)
        private set
    /** The token that moved last; drawn above others on its square. */
    var activeToken by mutableStateOf<TokenKey?>(null)
        private set
    /** Whose box the die is in. */
    var dieOwner by mutableStateOf(initial.state.turn)
        private set

    val tokens: Map<TokenKey, TokenVisual> =
        BoardGeometry.layout(initial.state, viewer).mapValues { (_, spot) -> TokenVisual(spot.point.toOffset(), spot.scale) }
    val dice: Map<PlayerColor, DieVisual> = PlayerColor.entries.associateWith { DieVisual() }

    private val pending = ArrayDeque<RoomGame>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var lastSubmitted: RoomGame? = null
    private lateinit var scope: CoroutineScope
    private var localRoll: Job? = null
    private var localRollStartNanos = 0L

    init {
        showDieFor(initial)
    }

    fun submit(game: RoomGame) {
        if (game == lastSubmitted) return
        lastSubmitted = game
        pending.addLast(game)
        signal.trySend(Unit)
    }

    /** Runs the animation queue until cancelled. Call from a LaunchedEffect. */
    suspend fun run() = coroutineScope {
        scope = this
        launch { pills.run() }
        for (ignored in signal) {
            while (pending.isNotEmpty()) {
                if (pending.size > MAX_BACKLOG) {
                    val latest = pending.last()
                    pending.clear()
                    snap(latest)
                    continue
                }
                val next = pending.removeFirst()
                val prev = shown
                when {
                    next.gameNumber == prev.gameNumber && next.version <= prev.version -> Unit // Old news.
                    next.gameNumber == prev.gameNumber && next.version == prev.version + 1 -> step(prev, next)
                    else -> snap(next)
                }
            }
        }
    }

    /**
     * My own roll: the die starts tumbling right away (the only optimistic UI), while the roll is
     * written. The roll animation then lands it on the real value. Stops by itself if no roll arrives.
     */
    fun startLocalRoll() {
        if (localRoll?.isActive == true) return
        val die = dice.getValue(viewer)
        localRollStartNanos = System.nanoTime()
        fx.play(Sfx.ROLL)
        fx.buzz(Buzz.ROLL)
        localRoll = scope.launch {
            die.tumbling = true
            val started = System.nanoTime()
            while ((System.nanoTime() - started) < LOCAL_ROLL_TIMEOUT_NANOS) {
                shuffleFace(die)
                delay(60)
            }
            // No roll came back (for example the write was rejected): stop and show the blank face.
            resetDie(die)
            die.face = null
        }
    }

    private suspend fun step(prev: RoomGame, next: RoomGame) {
        busy = true
        try {
            val last = next.state.lastAction
            when (last?.type) {
                ActionType.ROLL -> playRoll(prev, next, last)
                ActionType.MOVE -> playMove(next, last)
                ActionType.TIMEOUT -> pills.show(texts.ranOutOfTime.format(nameOf(last.by)), palette.amber)
                ActionType.LEFT, null -> Unit
            }
            settle(next)
            shown = next
            afterStep(prev, next)
        } finally {
            busy = false
        }
    }

    // ---- Dice ----

    private suspend fun playRoll(prev: RoomGame, next: RoomGame, last: LastAction) {
        val roller = last.by
        val value = checkNotNull(last.dice)
        val die = dice.getValue(roller)
        moveDieTo(roller)
        val tumbleMillis = if (roller == viewer && localRoll?.isActive == true) {
            localRoll?.cancel()
            val elapsed = (System.nanoTime() - localRollStartNanos) / 1_000_000
            (TUMBLE_MILLIS - elapsed).coerceAtLeast(MIN_LANDING_TUMBLE_MILLIS)
        } else {
            fx.play(Sfx.ROLL)
            TUMBLE_MILLIS
        }
        tumble(die, tumbleMillis)
        land(die, value)

        val turnPassed = next.state.phase == Phase.ROLL && next.state.turn != roller
        when {
            next.state.phase == Phase.OVER -> Unit
            turnPassed && value == 6 && prev.state.sixesInRow == 2 -> {
                pills.show(texts.threeSixes, palette.red.dark)
                repeat(2) {
                    die.redFlash.animateTo(1f, tween(120))
                    shake(die, 160)
                    die.redFlash.animateTo(0f, tween(120))
                }
                delay(500)
            }
            turnPassed -> {
                pills.show(texts.noMoves, Color(0xFF546E7A))
                delay(NO_MOVE_HOLD_MILLIS)
            }
            value == 6 -> {
                fx.play(Sfx.SIX)
                scope.launch {
                    die.glow.snapTo(0f)
                    die.glow.animateTo(1f, tween(180))
                    die.glow.animateTo(0f, tween(520))
                }
                scope.launch {
                    die.floaterText = texts.plusOneTurn
                    die.floater.snapTo(0f)
                    die.floater.animateTo(1f, tween(1_100, easing = LinearEasing))
                }
                delay(250)
            }
        }
    }

    /** Random faces that change more and more slowly, with the die spinning and shaking. */
    private suspend fun tumble(die: DieVisual, millis: Long) {
        die.tumbling = true
        val started = System.nanoTime()
        var interval = 45.0
        while ((System.nanoTime() - started) / 1_000_000 < millis) {
            shuffleFace(die)
            delay(interval.toLong())
            interval *= 1.2
        }
        resetDie(die)
    }

    private fun shuffleFace(die: DieVisual) {
        die.face = Random.nextInt(1, 7)
        die.angle = Random.nextInt(-35, 36).toFloat()
        die.shakeX = Random.nextInt(-5, 6) * density.density
    }

    private fun resetDie(die: DieVisual) {
        die.tumbling = false
        die.angle = 0f
        die.shakeX = 0f
    }

    /** Lands with a small bounce: 1.0 -> 1.2 -> 1.0. */
    private suspend fun land(die: DieVisual, value: Int) {
        die.face = value
        die.pop.snapTo(1f)
        die.pop.animateTo(1.2f, tween(90))
        die.pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium))
    }

    private suspend fun shake(die: DieVisual, millis: Int) {
        animate(0f, 1f, animationSpec = tween(millis, easing = LinearEasing)) { t, _ ->
            die.shakeX = sin(t * 6 * PI).toFloat() * 7 * density.density * (1 - t)
        }
        die.shakeX = 0f
    }

    private fun moveDieTo(owner: PlayerColor) {
        if (dieOwner == owner) return
        dieOwner = owner
        val die = dice.getValue(owner)
        die.face = null
        scope.launch {
            die.enter.snapTo(0f)
            die.enter.animateTo(1f, tween(250, easing = FastOutSlowInEasing))
        }
    }

    // ---- Tokens ----

    private suspend fun playMove(next: RoomGame, last: LastAction) {
        val color = last.by
        val index = checkNotNull(last.token)
        val from = checkNotNull(last.from)
        val to = checkNotNull(last.to)
        val token = tokens.getValue(TokenKey(color, index))
        activeToken = TokenKey(color, index)
        val colors = palette.of(color)
        val points = BoardGeometry.path(color, index, from, to).map { BoardGeometry.forViewer(it, viewer).toOffset() }

        token.scale = 1f
        if (from == YARD) {
            // Pops out of its slot, then hops onto the start square with a sparkle.
            animate(0f, 1f, animationSpec = tween(160)) { t, _ ->
                val s = sin(t * PI).toFloat()
                token.hop = s
                token.liftPx = s * 6.dp.px()
            }
            effects.add(BurstKind.SPARKLE, points.first(), colors.main)
        }
        val hops = if (to == HOME) points.dropLast(1) else points
        for (p in hops) hop(token, p)
        if (to == HOME) {
            // Slides into the center triangle, shrinks and settles, with confetti and a chime.
            val start = token.pos
            val end = points.last()
            animate(0f, 1f, animationSpec = tween(300, easing = FastOutSlowInEasing)) { t, _ ->
                token.pos = lerp(start, end, t)
                token.scale = 1f - 0.2f * t
            }
            effects.add(BurstKind.CONFETTI, end, colors.main)
            fx.play(Sfx.HOME)
            fx.buzz(Buzz.HOME)
        } else {
            squash(token)
        }

        last.captured?.let { capturedIndex ->
            val victimColor = color.opponent
            val victim = tokens.getValue(TokenKey(victimColor, capturedIndex))
            // Knocked: a flash and little stars, then it flies home along a fast arc.
            effects.add(BurstKind.STARS, victim.pos, palette.of(victimColor).main)
            fx.play(Sfx.CAPTURE)
            fx.buzz(Buzz.CAPTURE)
            animate(0f, 1f, animationSpec = tween(200)) { t, _ -> victim.flash = sin(t * PI).toFloat() }
            victim.flash = 0f
            val start = victim.pos
            val home = BoardGeometry.forViewer(BoardGeometry.yardSpot(victimColor, capturedIndex), viewer).toOffset()
            animate(0f, 1f, animationSpec = tween(500, easing = FastOutSlowInEasing)) { t, _ ->
                val s = sin(t * PI).toFloat()
                victim.pos = lerp(start, home, t)
                victim.liftPx = s * 42.dp.px()
                victim.hop = s * 0.6f
            }
            victim.liftPx = 0f
            victim.hop = 0f
            victim.scale = 1f
            pills.show(texts.captured, seatPill(color))
        }
    }

    /** One square: an arc that lifts about 10 dp and grows to 1.15x at the top, with a tick on landing. */
    private suspend fun hop(token: TokenVisual, target: Offset) {
        val start = token.pos
        animate(0f, 1f, animationSpec = tween(HOP_MILLIS, easing = LinearEasing)) { t, _ ->
            val s = sin(t * PI).toFloat()
            token.pos = lerp(start, target, t)
            token.liftPx = s * 10.dp.px()
            token.hop = s
        }
        token.pos = target
        token.liftPx = 0f
        token.hop = 0f
        fx.play(Sfx.HOP)
    }

    private suspend fun squash(token: TokenVisual) {
        animate(0f, 1f, animationSpec = tween(60)) { t, _ -> token.squash = t }
        animate(1f, 0f, animationSpec = spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow)) { t, _ -> token.squash = t }
        token.squash = 0f
    }

    /** Moves every token to its final place (stacks fan out, finished tokens gather) together. */
    private suspend fun settle(game: RoomGame) = coroutineScope {
        for ((key, spot) in BoardGeometry.layout(game.state, viewer)) {
            val token = tokens.getValue(key)
            val target = spot.point.toOffset()
            if (token.pos == target && token.scale == spot.scale) continue
            launch {
                val startPos = token.pos
                val startScale = token.scale
                animate(0f, 1f, animationSpec = tween(SETTLE_MILLIS)) { t, _ ->
                    token.pos = lerp(startPos, target, t)
                    token.scale = startScale + (spot.scale - startScale) * t
                }
            }
        }
    }

    /** After an update: the die goes to whoever rolls next, and a pill says whose turn it is. */
    private fun afterStep(prev: RoomGame, next: RoomGame) {
        val s = next.state
        if (s.phase == Phase.OVER) return
        if (s.phase == Phase.ROLL) {
            if (s.turn != dieOwner) moveDieTo(s.turn) else dice.getValue(s.turn).face = null
        }
        if (s.turn != prev.state.turn) {
            if (s.turn == viewer) {
                pills.show(texts.yourTurn, seatPill(s.turn))
                fx.play(Sfx.YOUR_TURN)
            } else {
                pills.show(texts.playersTurn.format(nameOf(s.turn)), seatPill(s.turn))
            }
        }
    }

    /** Jumps straight to [game] without animating (reconnect, new game, long backlog). */
    private fun snap(game: RoomGame) {
        localRoll?.cancel()
        for ((key, spot) in BoardGeometry.layout(game.state, viewer)) {
            tokens.getValue(key).apply {
                pos = spot.point.toOffset()
                scale = spot.scale
                liftPx = 0f
                hop = 0f
                squash = 0f
                flash = 0f
            }
        }
        dice.values.forEach { resetDie(it) }
        shown = game
        showDieFor(game)
    }

    private fun showDieFor(game: RoomGame) {
        dieOwner = game.state.turn
        dice.forEach { (color, die) -> die.face = if (color == game.state.turn) game.state.dice else null }
    }

    /** Pill background for a player (yellow uses its darker shade so white text stays readable). */
    private fun seatPill(color: PlayerColor): Color =
        if (color == PlayerColor.YELLOW) palette.yellow.dark else palette.of(color).main

    private fun androidx.compose.ui.unit.Dp.px() = with(density) { toPx() }

    private companion object {
        const val TUMBLE_MILLIS = 600L
        const val MIN_LANDING_TUMBLE_MILLIS = 200L
        const val LOCAL_ROLL_TIMEOUT_NANOS = 5_000_000_000L
        const val NO_MOVE_HOLD_MILLIS = 1_000L
        const val HOP_MILLIS = 140
        const val SETTLE_MILLIS = 160
        /** More waiting updates than this and we stop animating and jump to the latest. */
        const val MAX_BACKLOG = 3
    }
}

fun GridPoint.toOffset() = Offset(x, y)
