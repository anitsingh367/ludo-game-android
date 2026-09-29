package com.example.ludoduel.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.LAST_TRACK
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.Baloo
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoPalette
import kotlinx.coroutines.launch

private const val STEP_MILLIS = 120
private const val CAPTURE_MILLIS = 350
private const val SETTLE_MILLIS = 150

private fun GridPoint.toOffset() = Offset(x, y)

/**
 * Draws the tokens on top of the static board. Movable tokens bob gently above a pulsing ring.
 * Token moves animate square by square; after a reconnect (version jumps by more than one) tokens
 * snap to their positions without replaying missed moves.
 */
@Composable
fun LudoBoard(
    game: RoomGame,
    me: PlayerColor,
    movable: List<Int>,
    colorblind: Boolean,
    onTokenTap: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalLudoPalette.current
    val targets = remember(game, me) { BoardGeometry.layout(game.state, me) }
    val positions = rememberTokenPositions(game, me, targets)
    val textMeasurer = rememberTextMeasurer()
    val loop = rememberInfiniteTransition(label = "tokens")
    // Bobbing: 0..1..0 over 600 ms. Pulse: the ring under movable tokens.
    val bob by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val pulse by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(800), RepeatMode.Restart), label = "pulse")
    val currentMovable by rememberUpdatedState(movable)
    val currentTap by rememberUpdatedState(onTokenTap)
    val myTokens by rememberUpdatedState(game.state.tokensOf(me))

    BoardFrame(me, modifier) {
        Canvas(
            Modifier.fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(me) {
                    detectTapGestures { tap ->
                        pickToken(tap, size.width / BoardGeometry.SIZE.toFloat(), 24.dp.toPx(), currentMovable, myTokens) {
                            positions.getValue(TokenKey(me, it)).value
                        }?.let { currentTap(it) }
                    }
                },
        ) {
            val unit = size.width / BoardGeometry.SIZE
            val movableKeys = movable.map { TokenKey(me, it) }.toSet()
            drawBlocks(game, targets, unit, palette)
            drawFinished(game, me, unit, palette, textMeasurer)
            // Movable tokens last, so they sit on top of any stack.
            val order = targets.keys
                .filter { game.state.tokensOf(it.color)[it.index] != HOME }
                .sortedWith(compareBy({ it in movableKeys }, { positions.getValue(it).value.y }))
            for (key in order) {
                val center = positions.getValue(key).value * unit
                val spot = targets.getValue(key)
                val canMove = key in movableKeys
                if (canMove) {
                    val ringR = unit * (0.36f + 0.2f * pulse) * spot.scale
                    drawOval(
                        Color.White.copy(alpha = 0.9f * (1f - pulse)),
                        topLeft = center + Offset(-ringR, unit * 0.36f * spot.scale - ringR * 0.4f),
                        size = Size(ringR * 2, ringR * 0.8f),
                        style = Stroke(unit * 0.07f),
                    )
                }
                drawPawn(
                    base = center,
                    unit = unit,
                    colors = palette.of(key.color),
                    look = PawnLook(scale = spot.scale, liftPx = if (canMove) 4.dp.toPx() * bob else 0f),
                    letter = if (colorblind) colorblindLetter(key.color) else null,
                    textMeasurer = textMeasurer,
                )
            }
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
private fun DrawScope.drawBlocks(game: RoomGame, targets: Map<TokenKey, TokenSpot>, unit: Float, palette: LudoPalette) {
    for (color in PlayerColor.entries) {
        game.state.tokensOf(color).withIndex()
            .filter { it.value in 0..LAST_TRACK }
            .groupBy { it.value }
            .values.filter { it.size >= 2 }
            .forEach { group ->
                val points = group.map { targets.getValue(TokenKey(color, it.index)).point }
                val left = points.minOf { it.x } - 0.32f
                val right = points.maxOf { it.x } + 0.32f
                val top = points.minOf { it.y } - 0.42f
                val bottom = points.maxOf { it.y } + 0.4f
                val tl = Offset(left * unit, top * unit)
                val sz = Size((right - left) * unit, (bottom - top) * unit)
                val colors = palette.of(color)
                drawRoundRect(colors.light.copy(alpha = 0.55f), tl, sz, CornerRadius(unit * 0.3f))
                drawRoundRect(colors.dark.copy(alpha = 0.7f), tl, sz, CornerRadius(unit * 0.3f), style = Stroke(unit * 0.06f))
            }
    }
}

/** One small pawn in each color's center triangle with the number of tokens that reached home. */
private fun DrawScope.drawFinished(game: RoomGame, viewer: PlayerColor, unit: Float, palette: LudoPalette, textMeasurer: TextMeasurer) {
    for (color in PlayerColor.entries) {
        val done = game.state.tokensOf(color).count { it == HOME }
        if (done == 0) continue
        val spot = BoardGeometry.forViewer(BoardGeometry.finishSpot(color), viewer).toOffset() * unit
        drawPawn(spot, unit, palette.of(color), PawnLook(scale = 0.8f), null, textMeasurer)
        val badge = spot + Offset(unit * 0.32f, -unit * 0.3f)
        drawCircle(Color.White, unit * 0.22f, badge)
        drawCircle(palette.of(color).dark, unit * 0.22f, badge, style = Stroke(unit * 0.04f))
        val layout = textMeasurer.measure(
            done.toString(),
            TextStyle(color = palette.of(color).dark, fontSize = (unit * 0.3f).toSp(), fontWeight = FontWeight.ExtraBold, fontFamily = Baloo),
        )
        drawText(layout, topLeft = badge - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}

@Composable
private fun rememberTokenPositions(
    game: RoomGame,
    viewer: PlayerColor,
    targets: Map<TokenKey, TokenSpot>,
): Map<TokenKey, Animatable<Offset, AnimationVector2D>> {
    val anims = remember {
        targets.mapValues { (_, spot) -> Animatable(spot.point.toOffset(), Offset.VectorConverter) }
    }
    // The last game this board started showing. A plain holder: changing it must not recompose.
    val shown = remember { arrayOfNulls<RoomGame>(1) }
    LaunchedEffect(game, viewer) {
        val prev = shown[0]
        shown[0] = game
        val last = game.state.lastAction
        val isNextMove = prev != null && game.gameNumber == prev.gameNumber &&
            game.version == prev.version + 1 && last?.type == ActionType.MOVE
        if (!isNextMove) {
            targets.forEach { (key, spot) -> anims.getValue(key).snapTo(spot.point.toOffset()) }
            return@LaunchedEffect
        }
        val token = checkNotNull(last.token)
        val mover = TokenKey(last.by, token)
        val steps = BoardGeometry.path(last.by, token, checkNotNull(last.from), checkNotNull(last.to))
            .map { BoardGeometry.forViewer(it, viewer).toOffset() }
        val moverAnim = anims.getValue(mover)
        for (p in steps.dropLast(1)) moverAnim.animateTo(p, tween(STEP_MILLIS, easing = LinearEasing))
        moverAnim.animateTo(targets.getValue(mover).point.toOffset(), tween(STEP_MILLIS, easing = LinearEasing))
        // Then the captured token goes back to its yard and stacks settle.
        val captured = last.captured?.let { TokenKey(last.by.opponent, it) }
        targets.forEach { (key, spot) ->
            val anim = anims.getValue(key)
            val target = spot.point.toOffset()
            if (key == mover || anim.value == target) return@forEach
            val duration = if (key == captured) CAPTURE_MILLIS else SETTLE_MILLIS
            launch { anim.animateTo(target, tween(duration)) }
        }
    }
    return anims
}
