package com.example.ludoduel.ui.game

import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.SAFE_SQUARES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BoardGeometryTest {

    private fun inCenter(c: Cell) = c.col in 6..8 && c.row in 6..8
    private fun inYard(c: Cell) = (c.col <= 5 || c.col >= 9) && (c.row <= 5 || c.row >= 9)

    @Test fun `track has 52 distinct squares on the arms`() {
        val track = BoardGeometry.track
        assertEquals(52, track.size)
        assertEquals(52, track.toSet().size)
        track.forEach { assertFalse(inCenter(it)); assertFalse(inYard(it)) }
    }

    @Test fun `consecutive squares touch each other`() {
        val track = BoardGeometry.track
        for (i in track.indices) {
            val a = track[i]
            val b = track[(i + 1) % track.size]
            assertTrue("$a -> $b", abs(a.col - b.col) <= 1 && abs(a.row - b.row) <= 1)
        }
    }

    @Test fun `start squares are next to their own yards`() {
        assertEquals(Cell(6, 13), BoardGeometry.track[0])
        assertEquals(Cell(8, 1), BoardGeometry.track[26])
    }

    @Test fun `home columns lead from each color's last square to the center`() {
        val redLast = BoardGeometry.cellOf(PlayerColor.RED, 50)!!
        assertEquals(Cell(7, 14), redLast)
        assertEquals(Cell(7, 13), BoardGeometry.cellOf(PlayerColor.RED, 51))
        assertEquals(Cell(7, 9), BoardGeometry.cellOf(PlayerColor.RED, 55))
        assertEquals(Cell(7, 0), BoardGeometry.cellOf(PlayerColor.YELLOW, 50))
        assertEquals(Cell(7, 1), BoardGeometry.cellOf(PlayerColor.YELLOW, 51))
        assertEquals(Cell(7, 5), BoardGeometry.cellOf(PlayerColor.YELLOW, 55))
        val track = BoardGeometry.track.toSet()
        for (p in 51..55) {
            assertFalse(BoardGeometry.cellOf(PlayerColor.RED, p) in track)
            assertFalse(BoardGeometry.cellOf(PlayerColor.YELLOW, p) in track)
        }
    }

    @Test fun `safe squares are on the track`() {
        assertEquals(8, SAFE_SQUARES.map { BoardGeometry.track[it] }.toSet().size)
    }

    @Test fun `yellow view is the red view rotated half a turn`() {
        val p = BoardGeometry.center(BoardGeometry.track[0])
        assertEquals(GridPoint(15 - p.x, 15 - p.y), BoardGeometry.forViewer(p, PlayerColor.YELLOW))
        // Yellow's own start square appears where Red's start is in Red's view.
        val yellowStart = BoardGeometry.forViewer(BoardGeometry.track[26], PlayerColor.YELLOW)
        assertEquals(Cell(6, 13), yellowStart)
    }

    @Test fun `layout spreads tokens that share a square`() {
        val s = GameState.initial(PlayerColor.RED).copy(red = listOf(5, 5, -1, 56), yellow = listOf(56, 56, 56, -1))
        val spots = BoardGeometry.layout(s, PlayerColor.RED)
        assertEquals(8, spots.size)
        assertEquals(8, spots.values.map { it.point }.toSet().size)
        assertEquals(1f, spots.getValue(TokenKey(PlayerColor.RED, 2)).scale)
        assertTrue(spots.getValue(TokenKey(PlayerColor.RED, 0)).scale < 1f)
    }

    @Test fun `layout handles eight tokens on one safe square`() {
        // Red progress 26 and yellow progress 0 are both absolute square 26.
        val s = GameState.initial(PlayerColor.RED).copy(red = List(4) { 26 }, yellow = List(4) { 0 })
        val spots = BoardGeometry.layout(s, PlayerColor.YELLOW)
        assertEquals(8, spots.values.map { it.point }.toSet().size)
    }

    @Test fun `path steps through every square`() {
        val path = BoardGeometry.path(PlayerColor.RED, 0, 3, 7)
        assertEquals(4, path.size)
        assertEquals(BoardGeometry.center(BoardGeometry.track[7]), path.last())
        assertEquals(1, BoardGeometry.path(PlayerColor.RED, 0, -1, 0).size)
    }
}
