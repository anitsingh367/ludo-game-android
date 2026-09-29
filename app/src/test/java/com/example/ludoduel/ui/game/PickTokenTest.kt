package com.example.ludoduel.ui.game

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickTokenTest {
    private val unit = 60f          // pixels per board square
    private val minRadius = 72f     // 24 dp at density 3

    // Tokens 0 and 1 stacked on progress 5 (fanned slightly), token 2 elsewhere, token 3 in the yard.
    private val tokens = listOf(5, 5, 9, -1)
    private val positions = mapOf(0 to Offset(6.35f, 8.5f), 1 to Offset(6.65f, 8.6f), 2 to Offset(3.5f, 8.5f), 3 to Offset(2f, 11f))

    private fun pick(tap: Offset, movable: List<Int>) =
        pickToken(tap, unit, minRadius, movable, tokens) { positions.getValue(it) }

    @Test fun `tap near a movable token picks it`() {
        assertEquals(2, pick(Offset(3.5f * unit, 8.5f * unit), listOf(0, 2)))
    }

    @Test fun `touch area is at least 48 dp even for small tokens`() {
        // 70 px away: outside 0.6 of a square (36 px) but inside the 24 dp minimum radius.
        assertEquals(2, pick(Offset(3.5f * unit + 70f, 8.5f * unit), listOf(2)))
        assertNull(pick(Offset(3.5f * unit + 80f, 8.5f * unit), listOf(2)))
    }

    @Test fun `tap on a stack picks the token that can move`() {
        // Tap right on token 0 but only token 1 (same square) can move.
        assertEquals(1, pick(Offset(6.35f * unit, 8.5f * unit), listOf(1)))
    }

    @Test fun `when several tokens in a stack can move the first one is picked`() {
        assertEquals(0, pick(Offset(6.65f * unit, 8.6f * unit), listOf(0, 1)))
    }

    @Test fun `taps on tokens that cannot move do nothing`() {
        assertNull(pick(Offset(2f * unit, 11f * unit), listOf(2)))
        assertNull(pick(Offset(2f * unit, 11f * unit), emptyList()))
    }
}
