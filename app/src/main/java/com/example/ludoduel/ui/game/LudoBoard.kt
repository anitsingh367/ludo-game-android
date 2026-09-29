package com.example.ludoduel.ui.game

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Path
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
import com.example.ludoduel.data.RoomGame
import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.SAFE_SQUARES
import com.example.ludoduel.ui.theme.BoardColors
import com.example.ludoduel.ui.theme.LocalBoardColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val STEP_MILLIS = 120
private const val CAPTURE_MILLIS = 350
private const val SETTLE_MILLIS = 150

private fun GridPoint.toOffset() = Offset(x, y)

/**
 * Draws the board and tokens on a Canvas that scales to any square size. Token moves animate
 * square by square; a captured token then slides back to its yard. After a reconnect (version
 * jumps by more than one) tokens snap to their positions without replaying missed moves.
 */
@Composable
fun LudoBoard(
    game: RoomGame,
    me: PlayerColor,
    movable: List<Int>,
    onTokenTap: (Int) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalBoardColors.current
    val targets = remember(game, me) { BoardGeometry.layout(game.state, me) }
    val positions = rememberTokenPositions(game, me, targets)
    val textMeasurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(650), RepeatMode.Reverse),
        label = "glowAlpha",
    )
    val currentMovable by rememberUpdatedState(movable)
    val currentTap by rememberUpdatedState(onTokenTap)

    Canvas(
        modifier
            .semantics { contentDescription = description }
            .pointerInput(me) {
                detectTapGestures { tap ->
                    val unit = size.width / BoardGeometry.SIZE.toFloat()
                    val grid = Offset(tap.x / unit, tap.y / unit)
                    // Pick the nearest movable token of mine within about half a square.
                    val hit = currentMovable
                        .map { it to positions.getValue(TokenKey(me, it)).value }
                        .minByOrNull { (_, p) -> hypot(p.x - grid.x, p.y - grid.y) }
                        ?.takeIf { (_, p) -> hypot(p.x - grid.x, p.y - grid.y) < 0.75f }
                    hit?.let { currentTap(it.first) }
                }
            },
    ) {
        val unit = size.width / BoardGeometry.SIZE
        drawBoard(colors, me, unit)
        val movableKeys = movable.map { TokenKey(me, it) }.toSet()
        // Movable tokens are drawn last so they sit on top of any stack.
        val order = targets.keys.sortedBy { it in movableKeys }
        for (key in order) {
            val center = positions.getValue(key).value * unit
            val radius = unit * 0.36f * targets.getValue(key).scale
            if (key in movableKeys) {
                drawCircle(colors.glow.copy(alpha = pulse), radius + unit * 0.14f, center, style = Stroke(unit * 0.1f))
            }
            drawToken(colors, key.color, center, radius, textMeasurer)
        }
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

private fun DrawScope.drawBoard(colors: BoardColors, viewer: PlayerColor, unit: Float) {
    drawRect(colors.board)

    fun rect(x: Float, y: Float, w: Float, h: Float): Pair<Offset, Size> {
        val (rx, ry) = if (viewer == PlayerColor.RED) x to y else (BoardGeometry.SIZE - x - w) to (BoardGeometry.SIZE - y - h)
        return Offset(rx * unit, ry * unit) to Size(w * unit, h * unit)
    }

    fun cell(c: Cell, fill: Color) {
        val v = BoardGeometry.forViewer(c, viewer)
        val topLeft = Offset(v.col * unit, v.row * unit)
        drawRect(fill, topLeft, Size(unit, unit))
        drawRect(colors.grid, topLeft, Size(unit, unit), style = Stroke(unit * 0.03f))
    }

    // Yards: Red bottom-left and Yellow top-right (in Red's view); the other two corners are unused.
    val yards = listOf(
        Triple(0f, 9f, colors.red),
        Triple(9f, 0f, colors.yellow),
        Triple(0f, 0f, colors.neutral),
        Triple(9f, 9f, colors.neutral),
    )
    for ((x, y, fill) in yards) {
        val (tl, sz) = rect(x, y, 6f, 6f)
        drawRoundRect(fill, tl, sz, CornerRadius(unit * 0.4f))
        val (itl, isz) = rect(x + 1f, y + 1f, 4f, 4f)
        drawRoundRect(colors.cell, itl, isz, CornerRadius(unit * 0.3f))
    }
    for (color in PlayerColor.entries) {
        for (i in 0 until 4) {
            val p = BoardGeometry.forViewer(BoardGeometry.yardSpot(color, i), viewer).toOffset() * unit
            drawCircle(colors.of(color).copy(alpha = 0.35f), unit * 0.45f, p)
        }
    }

    // Shared track, start squares and safe squares.
    BoardGeometry.track.forEachIndexed { index, c ->
        val fill = when (index) {
            PlayerColor.RED.startIndex -> colors.red
            PlayerColor.YELLOW.startIndex -> colors.yellow
            else -> colors.cell
        }
        cell(c, fill)
        if (index in SAFE_SQUARES) {
            val center = BoardGeometry.forViewer(BoardGeometry.center(c), viewer).toOffset() * unit
            drawStar(center, unit * 0.32f, colors.star.copy(alpha = if (fill == colors.cell) 1f else 0.6f))
        }
    }
    // Home columns, and the two unused ones (middle of the left and right arms) in grey.
    for (color in PlayerColor.entries) {
        for (p in 51..55) cell(BoardGeometry.homeColumnCell(color, p), colors.of(color))
    }
    for (c in (1..5) + (9..13)) cell(Cell(c, 7), colors.neutral)

    // Center: four triangles pointing inward. Red's comes from Red's side, Yellow's from Yellow's.
    val mid = BoardGeometry.forViewer(GridPoint(7.5f, 7.5f), viewer).toOffset() * unit
    fun triangle(a: GridPoint, b: GridPoint, fill: Color) {
        val pa = BoardGeometry.forViewer(a, viewer).toOffset() * unit
        val pb = BoardGeometry.forViewer(b, viewer).toOffset() * unit
        drawPath(Path().apply { moveTo(pa.x, pa.y); lineTo(pb.x, pb.y); lineTo(mid.x, mid.y); close() }, fill)
    }
    triangle(GridPoint(6f, 9f), GridPoint(9f, 9f), colors.red)
    triangle(GridPoint(6f, 6f), GridPoint(9f, 6f), colors.yellow)
    triangle(GridPoint(6f, 6f), GridPoint(6f, 9f), colors.neutral)
    triangle(GridPoint(9f, 6f), GridPoint(9f, 9f), colors.neutral)
}

private fun DrawScope.drawStar(center: Offset, radius: Float, color: Color) {
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) radius else radius * 0.45f
        val angle = -PI / 2 + i * PI / 5
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}

/** A token: colored disc with a dark rim and a letter (R or Y) so colors never need to be told apart. */
private fun DrawScope.drawToken(
    colors: BoardColors,
    color: PlayerColor,
    center: Offset,
    radius: Float,
    textMeasurer: TextMeasurer,
) {
    drawCircle(Color.Black.copy(alpha = 0.25f), radius, center + Offset(0f, radius * 0.12f))
    drawCircle(colors.of(color), radius, center)
    drawCircle(colors.darkOf(color), radius, center, style = Stroke(radius * 0.18f))
    val letter = if (color == PlayerColor.RED) "R" else "Y"
    val textColor = if (color == PlayerColor.RED) Color.White else colors.yellowDark
    val layout = textMeasurer.measure(
        letter,
        TextStyle(color = textColor, fontSize = (radius * 1.1f).toSp(), fontWeight = FontWeight.Bold),
    )
    drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}
