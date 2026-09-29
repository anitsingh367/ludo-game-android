package com.example.ludoduel.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.LAST_TRACK
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.Baloo
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoPalette

/**
 * The board with its tokens and effects. Everything is drawn from [animator]: token positions and
 * hop/squash/flash effects, the displayed game state, and particle bursts. Movable tokens bob gently
 * above a pulsing ring; [movable] is empty whenever input is not allowed.
 */
@Composable
fun LudoBoard(
    animator: GameAnimator,
    movable: List<Int>,
    colorblind: Boolean,
    onTokenTap: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    /** False while the app moves the only movable token by itself: taps do nothing then. */
    tapsEnabled: Boolean = true,
) {
    val me = animator.viewer
    val palette = LocalLudoPalette.current
    val textMeasurer = rememberTextMeasurer()
    // Bobbing (up and down 4 dp over 600 ms) and the pulsing ring run only while a token can move,
    // so the token layer is not redrawn every frame while the player waits.
    val bobAnim = remember { Animatable(0f) }
    val pulseAnim = remember { Animatable(0f) }
    val anyMovable = movable.isNotEmpty()
    LaunchedEffect(anyMovable) {
        if (!anyMovable) {
            bobAnim.snapTo(0f)
            pulseAnim.snapTo(0f)
            return@LaunchedEffect
        }
        launch { bobAnim.animateTo(1f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse)) }
        pulseAnim.animateTo(1f, infiniteRepeatable(tween(800), RepeatMode.Restart))
    }

    // Frame clock only while particle bursts are alive.
    var frameNanos by remember { mutableLongStateOf(0L) }
    val effects = animator.effects
    val hasBursts = effects.bursts.isNotEmpty()
    LaunchedEffect(hasBursts) {
        while (hasBursts) withFrameNanos {
            frameNanos = it
            effects.prune(it)
        }
    }

    val currentMovable by rememberUpdatedState(movable)
    val currentTap by rememberUpdatedState(onTokenTap)
    val currentTapsEnabled by rememberUpdatedState(tapsEnabled)
    val fx = LocalGameFx.current
    // Two tokens that were "too close to call": shown enlarged until the next tap decides.
    var tooClose by remember { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(movable) { tooClose = emptyList() }

    BoardFrame(me, modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .graphicsLayer()
                .semantics { contentDescription = description }
                .pointerInput(me) {
                    detectTapGestures { tap ->
                        if (!currentTapsEnabled) return@detectTapGestures
                        val unit = size.width / BoardGeometry.SIZE.toFloat()
                        val myTokens = animator.shown.state.tokensOf(me)
                        // After "too close", the second tap only chooses between those two squares.
                        val candidates = if (tooClose.isEmpty()) {
                            currentMovable
                        } else {
                            val squares = tooClose.map { myTokens[it] }.toSet()
                            currentMovable.filter { myTokens[it] in squares }
                        }
                        val result = pickToken(tap / unit, candidates, myTokens) { animator.tokens.getValue(TokenKey(me, it)).pos }
                        when (result) {
                            is TapResult.Pick -> {
                                tooClose = emptyList()
                                fx.buzz(Buzz.PICK)
                                currentTap(result.token)
                            }
                            is TapResult.TooClose -> tooClose = result.tokens
                            TapResult.Miss -> tooClose = emptyList()
                        }
                    }
                },
        ) {
            val unit = size.width / BoardGeometry.SIZE
            val state = animator.shown.state
            val movableKeys = movable.map { TokenKey(me, it) }.toSet()
            drawBlocks(state, animator.tokens, unit, palette)
            drawFinished(state, me, unit, palette, textMeasurer)
            // Tokens in the air, the token that just moved and movable tokens are drawn last, so they are on top.
            val order = animator.tokens.keys
                .filter { state.tokensOf(it.color)[it.index] != HOME }
                .sortedWith(
                    compareBy<TokenKey>(
                        { animator.tokens.getValue(it).liftPx > 0f },
                        { it in movableKeys },
                        { it == animator.activeToken },
                        { animator.tokens.getValue(it).pos.y },
                    ),
                )
            for (key in order) {
                val token = animator.tokens.getValue(key)
                val center = token.pos * unit
                val canMove = key in movableKeys
                var look = token.look()
                if (key.color == me && key.index in tooClose) look = look.copy(scale = look.scale * 1.3f)
                val s = look.scale * unit
                if (canMove) {
                    // A steady soft glow under the token plus a ring that pulses outwards, both
                    // inside the token's square.
                    val baseY = PawnShape.BASE_Y * s
                    val glowRx = (PawnShape.GLOW_RX - 0.03f) * s
                    val glowRy = (PawnShape.GLOW_RY - 0.02f) * s
                    drawOval(Color.White.copy(alpha = 0.75f), center + Offset(-glowRx, baseY - glowRy), Size(glowRx * 2, glowRy * 2))
                    val pulse = pulseAnim.value
                    val ringRx = (PawnShape.BASE_RX + (PawnShape.GLOW_RX - PawnShape.BASE_RX) * pulse) * s
                    val ringRy = (PawnShape.BASE_RY + (PawnShape.GLOW_RY - PawnShape.BASE_RY) * pulse) * s
                    drawOval(
                        Color.White.copy(alpha = 1f - pulse),
                        center + Offset(-ringRx, baseY - ringRy),
                        Size(ringRx * 2, ringRy * 2),
                        style = Stroke(s * 0.05f),
                    )
                    look = look.copy(liftPx = look.liftPx + PawnShape.BOB * s * bobAnim.value)
                }
                // Never lift the head above the board's top edge (hops along the top row).
                val headTop = center.y + (PawnShape.HEAD_Y - PawnShape.HEAD_R) * s
                look = look.copy(liftPx = look.liftPx.coerceAtMost(headTop.coerceAtLeast(0f)))
                drawPawn(
                    base = center,
                    unit = unit,
                    colors = palette.of(key.color),
                    look = look,
                    letter = if (colorblind) colorblindLetter(key.color) else null,
                    textMeasurer = textMeasurer,
                )
            }
            if (hasBursts) drawBursts(effects.bursts, frameNanos, unit)
        }
    }
}

/** What a tap on the board means. */
sealed interface TapResult {
    data class Pick(val token: Int) : TapResult
    /** Two movable tokens on different squares are about equally close: ask for a second tap. */
    data class TooClose(val tokens: List<Int>) : TapResult
    data object Miss : TapResult
}

/** A tap selects a movable token up to this far away (in squares). */
const val TAP_RADIUS = 1.5f
/** Two candidates closer in distance than this (in squares) are "too close to call". */
const val TOO_CLOSE = 0.3f

/**
 * Finds the token a tap means, in board squares. Only tokens that can legally move are considered,
 * so a token that cannot move never steals a tap. Tokens on the same square (the same progress,
 * which also covers tokens in the yard) make the same move, so they count as one candidate; the one
 * nearest the tap is picked.
 */
internal fun pickToken(tap: Offset, movable: List<Int>, myTokens: List<Int>, positionOf: (Int) -> Offset): TapResult {
    val near = movable
        .map { it to (positionOf(it) - tap).getDistance() }
        .filter { it.second <= TAP_RADIUS }
    if (near.isEmpty()) return TapResult.Miss
    val bySquare = near
        .groupBy { myTokens[it.first] }
        .map { (_, tokens) -> tokens.minBy { it.second } }
        .sortedBy { it.second }
    if (bySquare.size >= 2 && bySquare[1].second - bySquare[0].second < TOO_CLOSE) {
        return TapResult.TooClose(listOf(bySquare[0].first, bySquare[1].first))
    }
    return TapResult.Pick(bySquare[0].first)
}

/** A subtle shared outline around two or more tokens of one color on a track square (a block). */
private fun DrawScope.drawBlocks(state: GameState, tokens: Map<TokenKey, TokenVisual>, unit: Float, palette: LudoPalette) {
    for (color in PlayerColor.entries) {
        state.tokensOf(color).withIndex()
            .filter { it.value in 0..LAST_TRACK }
            .groupBy { it.value }
            .values.filter { it.size >= 2 }
            .forEach { group ->
                val points = group.map { tokens.getValue(TokenKey(color, it.index)).pos }
                val left = points.minOf { it.x } - 0.34f
                val right = points.maxOf { it.x } + 0.34f
                val top = points.minOf { it.y } - 0.46f
                val bottom = points.maxOf { it.y } + 0.42f
                val tl = Offset(left * unit, top * unit)
                val sz = Size((right - left) * unit, (bottom - top) * unit)
                val colors = palette.of(color)
                drawRoundRect(colors.light.copy(alpha = 0.55f), tl, sz, CornerRadius(unit * 0.3f))
                drawRoundRect(colors.dark.copy(alpha = 0.7f), tl, sz, CornerRadius(unit * 0.3f), style = Stroke(unit * 0.06f))
            }
    }
}

/** One pawn in each color's center triangle with the number of tokens that reached home. */
private fun DrawScope.drawFinished(state: GameState, viewer: PlayerColor, unit: Float, palette: LudoPalette, textMeasurer: TextMeasurer) {
    for (color in PlayerColor.entries) {
        val done = state.tokensOf(color).count { it == HOME }
        if (done == 0) continue
        val spot = BoardGeometry.forViewer(BoardGeometry.finishSpot(color), viewer).toOffset() * unit
        drawPawn(spot, unit, palette.of(color), PawnLook(scale = 0.8f), null, textMeasurer)
        val badge = spot + Offset(unit * 0.34f, -unit * 0.34f)
        drawCircle(Color.White, unit * 0.24f, badge)
        drawCircle(palette.of(color).dark, unit * 0.24f, badge, style = Stroke(unit * 0.045f))
        val layout = textMeasurer.measure(
            done.toString(),
            TextStyle(color = palette.of(color).dark, fontSize = (unit * 0.32f).toSp(), fontWeight = FontWeight.ExtraBold, fontFamily = Baloo),
        )
        drawText(layout, topLeft = badge - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}
