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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.ui.theme.LocalLudoPalette
import com.example.ludoduel.ui.theme.LudoPalette
import com.example.ludoduel.ui.theme.LudoTheme
import com.example.ludoduel.ui.theme.Seat
import com.example.ludoduel.ui.theme.starPath

/**
 * The board as a physical object: a thick gold/wood frame with a drop shadow. [content] is laid
 * out on the 15x15 playing area inside the frame (the static board is drawn behind it).
 */
@Composable
fun BoardFrame(viewer: PlayerColor, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalLudoPalette.current
    val frameShape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .shadow(14.dp, frameShape)
            .background(
                Brush.linearGradient(listOf(palette.frameLight, palette.frameDark, palette.frameLight)),
                frameShape,
            )
            .padding(10.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        BoardSurface(viewer, Modifier.fillMaxSize())
        content()
    }
}

/**
 * The static board, drawn once and cached until its size changes. It only recomposes when the
 * viewer or palette changes, so moving tokens never cause it to be redrawn.
 */
@Composable
fun BoardSurface(viewer: PlayerColor, modifier: Modifier = Modifier) {
    val palette = LocalLudoPalette.current
    Box(
        modifier.drawWithCache {
            val unit = size.width / BoardGeometry.SIZE
            onDrawBehind { drawBoard(palette, viewer, unit) }
        },
    )
}

private fun DrawScope.drawBoard(palette: LudoPalette, viewer: PlayerColor, unit: Float) {
    drawRect(palette.track)
    val line = Stroke(unit * 0.035f)

    fun cellRect(c: Cell): Pair<Offset, Size> {
        val v = BoardGeometry.forViewer(c, viewer)
        return Offset(v.col * unit, v.row * unit) to Size(unit, unit)
    }

    fun point(p: GridPoint): Offset = BoardGeometry.forViewer(p, viewer).let { Offset(it.x * unit, it.y * unit) }

    fun cell(c: Cell, fill: Color) {
        val (tl, sz) = cellRect(c)
        drawRect(fill, tl, sz)
        drawRect(palette.trackLine, tl, sz, style = line)
    }

    // Yards: colored corner, white rounded inset, four light slots.
    for (seat in Seat.entries) {
        val colors = palette.of(seat)
        val origin = BoardGeometry.yardOrigin(seat)
        val a = point(GridPoint(origin.col.toFloat(), origin.row.toFloat()))
        val b = point(GridPoint(origin.col + 6f, origin.row + 6f))
        val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
        val yardSize = Size(unit * 6, unit * 6)
        drawRect(
            Brush.linearGradient(listOf(colors.main, colors.dark), topLeft, topLeft + Offset(yardSize.width, yardSize.height)),
            topLeft, yardSize,
        )
        drawRect(colors.main, topLeft + Offset(unit * 0.12f, unit * 0.12f), Size(unit * 5.76f, unit * 5.76f))
        val inset = topLeft + Offset(unit * 0.8f, unit * 0.8f)
        drawRoundRect(Color.Black.copy(alpha = 0.18f), inset + Offset(0f, unit * 0.08f), Size(unit * 4.4f, unit * 4.4f), CornerRadius(unit * 0.7f))
        drawRoundRect(Color.White, inset, Size(unit * 4.4f, unit * 4.4f), CornerRadius(unit * 0.7f))
        // Slots at the same points the tokens use (see BoardGeometry.yardSpot).
        for (i in 0 until 4) {
            val slot = topLeft + Offset(unit * (2f + (i % 2) * 2f), unit * (2f + (i / 2) * 2f))
            drawCircle(colors.light, unit * 0.62f, slot)
            drawCircle(colors.main.copy(alpha = 0.35f), unit * 0.62f, slot, style = Stroke(unit * 0.07f))
        }
    }

    // Shared track.
    BoardGeometry.track.forEach { cell(it, palette.track) }
    // Home columns in their colors.
    for (seat in Seat.entries) BoardGeometry.homeColumn(seat).forEach { cell(it, palette.of(seat).main) }
    // Start squares: colored, with a white arrow in the direction of travel.
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
            size = unit * 0.36f,
        )
    }
    // Safe squares: a clean outlined star.
    for (index in BoardGeometry.starSquares) {
        val c = BoardGeometry.forViewer(BoardGeometry.track[index], viewer)
        val center = Offset((c.col + 0.5f) * unit, (c.row + 0.5f) * unit)
        drawPath(starPath(center, unit * 0.34f), Color(0xFF9AA0A6), style = Stroke(unit * 0.06f, join = StrokeJoin.Round))
    }

    // Center: four triangles meeting in the middle, with a soft inner shadow.
    val mid = point(GridPoint(7.5f, 7.5f))
    val centerSquare = Path()
    for (seat in Seat.entries) {
        val (p1, p2) = BoardGeometry.centerTriangle(seat)
        val a = point(p1)
        val b = point(p2)
        val tri = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(mid.x, mid.y); close() }
        drawPath(tri, palette.of(seat).main)
        centerSquare.addPath(tri)
    }
    clipPath(centerSquare) {
        drawCircle(
            Brush.radialGradient(
                listOf(Color.Black.copy(alpha = 0.22f), Color.Transparent),
                center = mid, radius = unit * 1.6f,
            ),
            radius = unit * 1.6f, center = mid,
        )
    }
    val c0 = point(GridPoint(6f, 6f))
    val c1 = point(GridPoint(9f, 9f))
    val c2 = point(GridPoint(9f, 6f))
    val c3 = point(GridPoint(6f, 9f))
    val divider = Color.White.copy(alpha = 0.55f)
    drawLine(divider, c0, c1, unit * 0.05f)
    drawLine(divider, c2, c3, unit * 0.05f)
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
