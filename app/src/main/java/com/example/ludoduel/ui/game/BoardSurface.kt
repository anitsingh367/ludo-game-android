package com.example.ludoduel.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.random.Random
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoPalette
import com.example.ludoduel.ui.theme.LudoTheme
import com.example.ludoduel.ui.theme.Seat
import com.example.ludoduel.ui.theme.starPath

/**
 * The board with a thin dark-brown border and a soft shadow. [content] is laid out on the 15x15
 * playing area inside the border (the static board is drawn behind it).
 */
@Composable
fun BoardFrame(viewer: PlayerColor, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .shadow(6.dp, shape)
            .background(BORDER_COLOR, shape)
            .padding(3.dp)
            .clip(RoundedCornerShape(3.dp)),
    ) {
        BoardSurface(viewer, Modifier.fillMaxSize())
        content()
    }
}

private val BORDER_COLOR = Color(0xFF5A3E2B)

/**
 * The static board, in the look of a traditional printed board: flat colors, cream squares, thin
 * dark lines around every square, thick colored yard borders and a light paper grain. It has its
 * own graphics layer and is drawn once and cached; it only recomposes when the viewer or palette
 * changes, so moving tokens never cause it to be redrawn.
 */
@Composable
fun BoardSurface(viewer: PlayerColor, modifier: Modifier = Modifier) {
    val palette = LocalLudoPalette.current
    Box(
        modifier
            .graphicsLayer()
            .drawWithCache {
                val unit = size.width / BoardGeometry.SIZE
                val grain = paperGrain()
                onDrawBehind {
                    drawBoard(palette, viewer, unit, 1.dp.toPx())
                    drawImage(grain, dstSize = IntSize(size.width.toInt(), size.height.toInt()), alpha = 0.04f, filterQuality = FilterQuality.Low)
                }
            },
    )
}

/** A small tile of random light and dark specks, stretched over the board as paper grain. */
private fun paperGrain(): ImageBitmap {
    val side = 192
    val bitmap = ImageBitmap(side, side)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val paint = Paint()
    val random = Random(11)
    repeat(side * side / 3) {
        paint.color = if (random.nextBoolean()) Color.Black else Color.White
        paint.alpha = random.nextFloat()
        val x = random.nextInt(side).toFloat()
        val y = random.nextInt(side).toFloat()
        canvas.drawRect(x, y, x + 1f, y + 1f, paint)
    }
    return bitmap
}

private fun DrawScope.drawBoard(palette: LudoPalette, viewer: PlayerColor, unit: Float, lineWidth: Float) {
    drawRect(palette.track)
    val line = palette.trackLine
    val ink = Color(0xFF3B2F2A)

    fun cellRect(c: Cell): Pair<Offset, Size> {
        val v = BoardGeometry.forViewer(c, viewer)
        return Offset(v.col * unit, v.row * unit) to Size(unit, unit)
    }

    fun point(p: GridPoint): Offset = BoardGeometry.forViewer(p, viewer).let { Offset(it.x * unit, it.y * unit) }

    /** A square with a thin dark line around it (on every square, colored ones included). */
    fun cell(c: Cell, fill: Color) {
        val (tl, sz) = cellRect(c)
        drawRect(fill, tl, sz)
        drawRect(line, tl, sz, style = Stroke(lineWidth))
    }

    fun area(x: Float, y: Float, w: Float, h: Float): Pair<Offset, Size> {
        val a = point(GridPoint(x, y))
        val b = point(GridPoint(x + w, y + h))
        return Offset(minOf(a.x, b.x), minOf(a.y, b.y)) to Size(w * unit, h * unit)
    }

    // Yards: a big colored square (a thick colored border) around a cream inner square with four
    // colored slots for the tokens.
    for (seat in Seat.entries) {
        val colors = palette.of(seat)
        val origin = BoardGeometry.yardOrigin(seat)
        val (tl, sz) = area(origin.col.toFloat(), origin.row.toFloat(), 6f, 6f)
        drawRect(colors.main, tl, sz)
        drawRect(line, tl, sz, style = Stroke(lineWidth))
        val (itl, isz) = area(origin.col + 1f, origin.row + 1f, 4f, 4f)
        drawRoundRect(palette.track, itl, isz, CornerRadius(unit * 0.08f))
        drawRoundRect(line, itl, isz, CornerRadius(unit * 0.08f), style = Stroke(lineWidth))
        for (i in 0 until 4) {
            val slot = tl + Offset(unit * (2f + (i % 2) * 2f), unit * (2f + (i / 2) * 2f))
            drawCircle(colors.main, unit * 0.5f, slot)
            drawCircle(colors.light.copy(alpha = 0.5f), unit * 0.3f, slot - Offset(unit * 0.08f, unit * 0.08f))
            drawCircle(ink.copy(alpha = 0.7f), unit * 0.5f, slot, style = Stroke(lineWidth * 1.2f))
        }
    }

    // Shared track (cream).
    BoardGeometry.track.forEach { cell(it, palette.track) }
    // Home columns and start squares in their color: together they read as one colored "L".
    for (seat in Seat.entries) BoardGeometry.homeColumn(seat).forEach { cell(it, palette.of(seat).main) }
    for (seat in Seat.entries) {
        val index = BoardGeometry.startIndex(seat)
        val here = BoardGeometry.track[index]
        cell(here, palette.of(seat).main)
        val next = BoardGeometry.forViewer(BoardGeometry.track[index + 1], viewer)
        val v = BoardGeometry.forViewer(here, viewer)
        drawArrow(
            center = Offset((v.col + 0.5f) * unit, (v.row + 0.5f) * unit),
            dx = (next.col - v.col).toFloat(),
            dy = (next.row - v.row).toFloat(),
            size = unit * 0.32f,
        )
    }
    // Safe squares: a simple outlined star in dark grey.
    for (index in BoardGeometry.starSquares) {
        val c = BoardGeometry.forViewer(BoardGeometry.track[index], viewer)
        val center = Offset((c.col + 0.5f) * unit, (c.row + 0.5f) * unit)
        drawPath(starPath(center, unit * 0.34f), Color(0xFF55504B), style = Stroke(unit * 0.055f, join = StrokeJoin.Round))
    }

    // Center: four flat colored triangles with thin dark lines between them.
    val mid = point(GridPoint(7.5f, 7.5f))
    for (seat in Seat.entries) {
        val (p1, p2) = BoardGeometry.centerTriangle(seat)
        val a = point(p1)
        val b = point(p2)
        val tri = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(mid.x, mid.y); close() }
        drawPath(tri, palette.of(seat).main)
        drawPath(tri, line, style = Stroke(lineWidth, join = StrokeJoin.Round))
    }
}

/** A white arrow pointing along (dx, dy) (one of the four axis directions). */
private fun DrawScope.drawArrow(center: Offset, dx: Float, dy: Float, size: Float) {
    val tip = center + Offset(dx * size, dy * size)
    val tail = center - Offset(dx * size, dy * size)
    // Perpendicular for the arrow head.
    val px = -dy
    val py = dx
    val headBase = center + Offset(dx * size * 0.05f, dy * size * 0.05f)
    val stroke = Stroke(size * 0.34f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawLine(Color.White, tail, tip, stroke.width, cap = StrokeCap.Round)
    drawPath(
        Path().apply {
            moveTo(headBase.x + px * size * 0.8f, headBase.y + py * size * 0.8f)
            lineTo(tip.x, tip.y)
            lineTo(headBase.x - px * size * 0.8f, headBase.y - py * size * 0.8f)
        },
        Color.White,
        style = stroke,
    )
}

@Preview(widthDp = 360, heightDp = 360)
@Composable
private fun BoardPreview() {
    LudoTheme { BoardFrame(PlayerColor.RED, Modifier.size(360.dp)) {} }
}

@Preview(widthDp = 360, heightDp = 360)
@Composable
private fun BoardYellowViewPreview() {
    LudoTheme { BoardFrame(PlayerColor.YELLOW, Modifier.size(360.dp)) {} }
}
