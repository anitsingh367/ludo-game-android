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
import com.example.ludoduel.engine.Dice
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.LastAction
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.TOKENS_PER_PLAYER
import com.example.ludoduel.engine.YARD
import com.example.ludoduel.ui.components.PillState
import com.example.ludoduel.ui.theme.LudoPalette
import kotlinx.coroutines.CoroutineScope
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
    /** Sideways offset in pixels while shaking ("Can't move"). */
    var shakeX by mutableFloatStateOf(0f)

    fun look() = PawnLook(scale = scale * (1f + 0.15f * hop), liftPx = liftPx, squash = squash, flash = flash)
}

/** Texts for the messages the animations show (resolved from string resources by the screen). */
data class AnimatorTexts(
    val plusOneTurn: String,
    /** "No moves" with the reason: yard tokens need a 6, home-column tokens need the exact number, or both. */
    val noMovesSix: String,
    val noMovesExact: String,
    val noMovesBoth: String,
    val threeSixes: String,
    val captured: String,
    val yourTurn: String,
    /** Format with the player's name. */
    val playersTurn: String,
    /** Format with the player's name. */
    val ranOutOfTime: String,
    val couldNotRoll: String,
    val cantMove: String,
    val luckyBoost: String,
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
    var texts = AnimatorTexts("", "", "", "", "", "", "", "%1\$s", "%1\$s", "", "", "")
    /** The room's Lucky Boost setting (for the "Lucky Boost!" message). */
    var luckyBoost = false
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

    /** Where each token is on the board right now, as the screen shows it (not the server's latest). */
    private val progress: MutableMap<TokenKey, Int> = progressOf(initial).toMutableMap()
    /** Tokens in the middle of an animation. They are never part of a stack. */
    private val moving = mutableSetOf<TokenKey>()
    val dice: Map<PlayerColor, DieVisual> = PlayerColor.entries.associateWith { DieVisual() }

    private val pending = ArrayDeque<RoomGame>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var lastSubmitted: RoomGame? = null
    private lateinit var scope: CoroutineScope

    /** My die's state machine; [myRollState] mirrors it for the screen. */
    private val myRoll = RollMachine()
    var myRollState by mutableStateOf<RollMachine.State>(RollMachine.State.Idle)
        private set

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

    /** A tap on my die. Returns true when the roll should be sent (the die is disabled from now on). */
    fun tapRoll(nowMillis: Long): Boolean = myRoll.tap(nowMillis).also { syncRoll() }

    /** The roll could not be sent: the die works again and a pill says so. */
    fun rollSendFailed() {
        if (myRoll.sendFailed()) pills.show(texts.couldNotRoll, palette.amber)
        syncRoll()
    }

    /** Safety net: no confirmed roll within 5 s (or the app was away) gives up, with a pill. */
    fun tickRoll(nowMillis: Long) {
        if (myRoll.tick(nowMillis)) pills.show(texts.couldNotRoll, palette.amber)
        syncRoll()
    }

    private fun syncRoll() {
        myRollState = myRoll.state
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
        val mine = roller == viewer
        if (mine) {
            myRoll.rollConfirmed(next.version)
            syncRoll()
            fx.buzz(Buzz.ROLL)
        }
        try {
            fx.play(Sfx.ROLL)
            rollAnimation(die, value)
        } finally {
            if (mine) {
                myRoll.animationFinished(next.version)
                syncRoll()
            }
        }

        // A 6 while the Lucky Boost was raising the odds for this player: say so, openly.
        if (value == 6 && luckyBoost && Dice.luckyStreak(prev.state, roller) >= 3) {
            pills.show(texts.luckyBoost, palette.green.main)
        }
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
                // Nothing on the track stops a token, so only yard tokens (no 6) and home-column
                // tokens (the roll would overshoot) can be stuck.
                val stuck = prev.state.tokensOf(roller).filter { it != HOME }
                val reason = when {
                    stuck.all { it == YARD } -> texts.noMovesSix
                    stuck.none { it == YARD } -> texts.noMovesExact
                    else -> texts.noMovesBoth
                }
                pills.show(reason, Color(0xFF546E7A))
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

    /**
     * The whole roll, played once with a fixed length (about 900 ms). The value is already decided;
     * the animation only shows it: the die jumps up (lifts about 24 dp, grows to 1.3x) while it
     * tumbles on a random mix of axes, fast at first and then slowing down, and lands with the value
     * facing the viewer, one small bounce and a wobble that settles. Whole turns of spin are added on
     * top of the resting angles, so it always ends exactly on [value].
     */
    private suspend fun rollAnimation(die: DieVisual, value: Int) {
        val (restX, restY, restZ) = DieCube.restAngles(value)
        fun spin(min: Int, max: Int) = Random.nextInt(min, max + 1) * 360f * (if (Random.nextBoolean()) 1 else -1)
        val spinX = spin(1, 2)
        val spinY = spin(1, 2)
        val spinZ = spin(0, 1)
        die.face = value
        try {
            animate(0f, 1f, animationSpec = tween(TUMBLE_MILLIS, easing = LinearEasing)) { t, _ ->
                val left = (1f - t) * (1f - t) * (1f - t) // ease-out: fast spin first, then slower
                die.rotX = restX + spinX * left
                die.rotY = restY + spinY * left
                die.rotZ = restZ + spinZ * left
                val up = sin(t * PI).toFloat()
                die.liftPx = up * 24.dp.px()
                die.scale3d = 1f + 0.3f * up
            }
            fx.play(Sfx.CLACK)
            animate(0f, 1f, animationSpec = tween(LANDING_MILLIS, easing = LinearEasing)) { t, _ ->
                die.liftPx = sin(t * PI).toFloat() * 5.dp.px()
                val wobble = sin(t * 3 * PI).toFloat() * 7f * (1f - t)
                die.rotX = restX + wobble
                die.rotY = restY - wobble * 0.6f
                die.rotZ = restZ
                die.scale3d = 1f
            }
        } finally {
            die.rest(value)
        }
    }

    /** A tap on my token that cannot move: a small shake and a short "Can't move" (not repeated while shown). */
    fun cantMove(key: TokenKey) {
        if (pills.current?.text != texts.cantMove) pills.show(texts.cantMove, Color(0xFF546E7A))
        val token = tokens.getValue(key)
        scope.launch {
            animate(0f, 1f, animationSpec = tween(300, easing = LinearEasing)) { t, _ ->
                token.shakeX = sin(t * 6 * PI).toFloat() * 4 * density.density * (1 - t)
            }
            token.shakeX = 0f
        }
    }

    private fun resetDie(die: DieVisual) {
        die.shakeX = 0f
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
        die.rest(null)
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
        val key = TokenKey(color, index)
        val token = tokens.getValue(key)
        activeToken = key
        val colors = palette.of(color)
        val points = BoardGeometry.path(color, index, from, to).map { BoardGeometry.forViewer(it, viewer).toOffset() }

        // The moving token leaves its stack at once: the tokens it leaves behind spread out again.
        moving += key
        coroutineScope {
            launch { relayout() }
            animate(token.scale, 1f, animationSpec = tween(RESTACK_MILLIS)) { v, _ -> token.scale = v }
        }
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
        // Arrived: it is at rest again, and joins whatever is on its new square.
        progress[key] = to
        moving -= key
        relayout()

        last.captured?.let { capturedIndex ->
            val victimKey = TokenKey(color.opponent, capturedIndex)
            val victim = tokens.getValue(victimKey)
            // Knocked: a flash and little stars, then it flies home along a fast arc.
            effects.add(BurstKind.STARS, victim.pos, palette.of(color.opponent).main)
            fx.play(Sfx.CAPTURE)
            fx.buzz(Buzz.CAPTURE)
            animate(0f, 1f, animationSpec = tween(200)) { t, _ -> victim.flash = sin(t * PI).toFloat() }
            victim.flash = 0f
            moving += victimKey
            val start = victim.pos
            val startScale = victim.scale
            val home = BoardGeometry.forViewer(BoardGeometry.yardSpot(color.opponent, capturedIndex), viewer).toOffset()
            coroutineScope {
                launch { relayout() } // the attacker has the square to itself again
                animate(0f, 1f, animationSpec = tween(500, easing = FastOutSlowInEasing)) { t, _ ->
                    val s = sin(t * PI).toFloat()
                    victim.pos = lerp(start, home, t)
                    victim.scale = startScale + (1f - startScale) * t
                    victim.liftPx = s * 42.dp.px()
                    victim.hop = s * 0.6f
                }
            }
            victim.liftPx = 0f
            victim.hop = 0f
            progress[victimKey] = YARD
            moving -= victimKey
            relayout()
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

    /**
     * Moves every token at rest to its place: stacks are worked out only from tokens at rest, so a
     * stack updates the moment a token leaves or arrives.
     */
    private suspend fun relayout(millis: Int = RESTACK_MILLIS) = coroutineScope {
        val atRest = progress.filterKeys { it !in moving }
        for ((key, spot) in BoardGeometry.layout(atRest, viewer)) {
            val token = tokens.getValue(key)
            val target = spot.point.toOffset()
            if (token.pos == target && token.scale == spot.scale) continue
            launch {
                val startPos = token.pos
                val startScale = token.scale
                animate(0f, 1f, animationSpec = tween(millis)) { t, _ ->
                    token.pos = lerp(startPos, target, t)
                    token.scale = startScale + (spot.scale - startScale) * t
                }
            }
        }
    }

    /** After an update: every token takes the place the new state gives it. */
    private suspend fun settle(game: RoomGame) {
        progress.putAll(progressOf(game))
        moving.clear()
        relayout(SETTLE_MILLIS)
    }

    private fun progressOf(game: RoomGame): Map<TokenKey, Int> =
        PlayerColor.entries.flatMap { c -> (0 until TOKENS_PER_PLAYER).map { i -> TokenKey(c, i) to game.state.tokensOf(c)[i] } }.toMap()

    /** After an update: the die goes to whoever rolls next, and a pill says whose turn it is. */
    private fun afterStep(prev: RoomGame, next: RoomGame) {
        val s = next.state
        if (s.phase == Phase.OVER) return
        if (s.phase == Phase.ROLL) {
            if (s.turn != dieOwner) moveDieTo(s.turn) else dice.getValue(s.turn).rest(null)
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
        myRoll.snapped(game.version)
        syncRoll()
        progress.putAll(progressOf(game))
        moving.clear()
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
        dice.forEach { (color, die) -> die.rest(if (color == game.state.turn) game.state.dice else null) }
    }

    /** Pill background for a player (yellow uses its darker shade so white text stays readable). */
    private fun seatPill(color: PlayerColor): Color =
        if (color == PlayerColor.YELLOW) palette.yellow.dark else palette.of(color).main

    private fun androidx.compose.ui.unit.Dp.px() = with(density) { toPx() }

    private companion object {
        /** Tumble part of the roll; with the landing the whole roll is about 900 ms. */
        const val TUMBLE_MILLIS = 700
        const val LANDING_MILLIS = 200
        const val NO_MOVE_HOLD_MILLIS = 1_000L
        const val HOP_MILLIS = 140
        const val SETTLE_MILLIS = 160
        /** How fast a stack rearranges when a token leaves or arrives. */
        const val RESTACK_MILLIS = 120
        /** More waiting updates than this and we stop animating and jump to the latest. */
        const val MAX_BACKLOG = 3
    }
}

fun GridPoint.toOffset() = Offset(x, y)
