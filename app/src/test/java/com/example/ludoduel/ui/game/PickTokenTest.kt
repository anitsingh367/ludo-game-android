package com.example.ludoduel.ui.game

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/** Smart tap, in board squares. */
class PickTokenTest {
    // Token 0 and 1 share a square (progress 5, fanned side by side), token 2 is on another square,
    // token 3 is in the yard.
    private val tokens = listOf(5, 5, 9, -1)
    private val positions = mapOf(0 to Offset(5.28f, 8.5f), 1 to Offset(5.72f, 8.5f), 2 to Offset(1.5f, 8.5f), 3 to Offset(2f, 11f))

    private fun pick(tap: Offset, movable: List<Int>) = pickToken(tap, movable, tokens) { positions.getValue(it) }

    @Test fun `a tap picks the nearest movable token`() {
        assertEquals(TapResult.Pick(2), pick(Offset(1.5f, 8.5f), listOf(0, 2)))
    }

    @Test fun `a tap up to one and a half squares away still picks it`() {
        assertEquals(TapResult.Pick(2), pick(Offset(1.5f + 1.45f, 8.5f), listOf(2)))
        assertEquals(TapResult.Miss, pick(Offset(1.5f + 1.6f, 8.5f), listOf(2)))
    }

    @Test fun `tokens that cannot move never steal a tap`() {
        // The tap is nearer the yard token (3), which cannot move, than token 2: token 2 is picked.
        assertEquals(TapResult.Pick(2), pick(Offset(1.9f, 9.8f), listOf(2)))
        assertEquals(TapResult.Miss, pick(Offset(2f, 11f), emptyList()))
    }

    @Test fun `two movable tokens on one square make the same move`() {
        val result = pick(Offset(5.5f, 8.5f), listOf(0, 1))
        assertEquals(true, result == TapResult.Pick(0) || result == TapResult.Pick(1))
    }

    @Test fun `about equally close tokens on different squares ask for a second tap`() {
        // Halfway between token 2 (x 1.5) and token 0 (x 5.28) is too far for both; use closer tokens.
        val near = mapOf(0 to Offset(4.5f, 8.5f), 2 to Offset(5.5f, 8.5f))
        val result = pickToken(Offset(5.05f, 8.5f), listOf(0, 2), listOf(5, -1, 6, -1)) { near.getValue(it) }
        assertEquals(TapResult.TooClose(listOf(2, 0)), result)
        // A clearer tap picks one.
        assertEquals(TapResult.Pick(2), pickToken(Offset(5.4f, 8.5f), listOf(0, 2), listOf(5, -1, 6, -1)) { near.getValue(it) })
    }
}
