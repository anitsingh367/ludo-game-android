package com.example.ludoduel.ui.game

import androidx.compose.ui.geometry.Rect
import com.example.ludoduel.engine.HOME
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.YARD
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every token's drawing (head, base disc, shadow, bobbing, glow ring) must stay inside its own
 * square and inside the board, at every position, in both players' views.
 */
class TokenBoundsTest {
    private val board = Rect(0f, 0f, BoardGeometry.SIZE.toFloat(), BoardGeometry.SIZE.toFloat())

    private fun Rect.within(outer: Rect) =
        left >= outer.left - 1e-4f && top >= outer.top - 1e-4f && right <= outer.right + 1e-4f && bottom <= outer.bottom + 1e-4f

    private fun drawn(spot: TokenSpot): Rect = PawnShape.envelope(spot.scale).translate(spot.point.x, spot.point.y)

    /** The square a token at rest stands on: 1x1 around its square's center (or yard slot). */
    private fun ownSquare(color: PlayerColor, index: Int, progress: Int, viewer: PlayerColor): Rect {
        val c = BoardGeometry.forViewer(BoardGeometry.point(color, index, progress), viewer)
        return Rect(c.x - 0.5f, c.y - 0.5f, c.x + 0.5f, c.y + 0.5f)
    }

    @Test fun `a single token stays inside its square everywhere`() {
        var checked = 0
        for (viewer in PlayerColor.entries) {
            for (color in PlayerColor.entries) {
                for (index in 0 until 4) {
                    for (progress in YARD until HOME) {
                        if (progress == YARD || index == 0) {
                            val spot = BoardGeometry.layout(mapOf(TokenKey(color, index) to progress), viewer).getValue(TokenKey(color, index))
                            val drawn = drawn(spot)
                            val square = ownSquare(color, index, progress, viewer)
                            assertTrue("$color#$index at $progress (viewer $viewer): $drawn not in $square", drawn.within(square))
                            assertTrue("$color#$index at $progress outside the board", drawn.within(board))
                            checked++
                        }
                    }
                }
            }
        }
        // 4 yard slots + 56 squares (52 track + 5 home column - 1 counted as index 0) per color and view.
        assertTrue(checked >= 2 * 2 * (4 + 56))
    }

    @Test fun `stacked tokens stay inside their square`() {
        for (n in 2..8) {
            for (i in 0 until n) {
                val (dx, dy, scale) = BoardGeometry.stackSlot(n, i)
                val drawn = PawnShape.envelope(scale).translate(dx, dy)
                assertTrue("stack of $n, slot $i: $drawn", drawn.within(Rect(-0.5f, -0.5f, 0.5f, 0.5f)))
            }
        }
    }

    @Test fun `tokens in a stack do not cover each other's heads`() {
        for (n in 2..8) {
            val heads = (0 until n).map { i ->
                val (dx, dy, scale) = BoardGeometry.stackSlot(n, i)
                Rect(dx - PawnShape.HEAD_R * scale, dy + (PawnShape.HEAD_Y - PawnShape.HEAD_R) * scale, dx + PawnShape.HEAD_R * scale, dy + (PawnShape.HEAD_Y + PawnShape.HEAD_R) * scale)
            }
            for (a in heads.indices) for (b in heads.indices) if (a < b) {
                assertTrue("stack of $n: heads $a and $b overlap", !heads[a].overlaps(heads[b]))
            }
        }
    }

    @Test fun `finished tokens are drawn inside the board`() {
        for (viewer in PlayerColor.entries) for (color in PlayerColor.entries) {
            val spot = BoardGeometry.layout(mapOf(TokenKey(color, 0) to HOME), viewer).getValue(TokenKey(color, 0))
            assertTrue(drawn(spot).within(board))
        }
    }
}
