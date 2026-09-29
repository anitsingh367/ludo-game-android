package com.example.ludoduel.ui.game

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
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
) {
    val me = animator.viewer
    val palette = LocalLudoPalette.current
    val textMeasurer = rememberTextMeasurer()
    val loop = rememberInfiniteTransition(label = "tokens")
    // Bobbing: up and down 4 dp over 600 ms. Pulse: the ring under movable tokens.
    val bob by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Restart), label = "pulse")

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

    BoardFrame(me, modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(me) {
                    detectTapGestures { tap ->
                        val myTokens = animator.shown.state.tokensOf(me)
                        pickToken(tap, size.width / BoardGeometry.SIZE.toFloat(), 24.dp.toPx(), currentMovable, myTokens) {
                            animator.tokens.getValue(TokenKey(me, it)).pos
                        }?.let { currentTap(it) }
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
                val look = token.look()
                val canMove = key in movableKeys
                if (canMove) {
                    val ringR = unit * (0.36f + 0.2f * pulse) * look.scale
                    drawOval(
                        Color.White.copy(alpha = 0.9f * (1f - pulse)),
                        topLeft = center + Offset(-ringR, unit * 0.36f * look.scale - ringR * 0.4f),
                        size = Size(ringR * 2, ringR * 0.8f),
                        style = Stroke(unit * 0.07f),
                    )
                }
                drawPawn(
                    base = center,
                    unit = unit,
                    colors = palette.of(key.color),
                    look = if (canMove) look.copy(liftPx = look.liftPx + 4.dp.toPx() * bob) else look,
                    letter = if (colorblind) colorblindLetter(key.color) else null,
                    textMeasurer = textMeasurer,
                )
            }
            if (hasBursts) drawBursts(effects.bursts, frameNanos, unit)
        }
    }
}

/**
 * Finds the token to move for a tap. The touch area is at least [minRadius] (24 dp, so 48 dp
 * across) even when tokens are small or stacked. When the tap hits a stack, it picks a token that
 * can move; if several can, the first one.
 */
internal fun pickToken(
    tap: Offset,
    unit: Float,
    minRadius: Float,
    movable: List<Int>,
    myTokens: List<Int>,
    positionOf: (Int) -> Offset,
): Int? {
    val radius = maxOf(minRadius, unit * 0.6f)
    val nearest = movable.minByOrNull { (positionOf(it) * unit - tap).getDistance() } ?: return null
    if ((positionOf(nearest) * unit - tap).getDistance() > radius) return null
    return movable.filter { myTokens[it] == myTokens[nearest] }.min()
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
