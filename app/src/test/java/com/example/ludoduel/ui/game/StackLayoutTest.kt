package com.example.ludoduel.ui.game

import com.example.ludoduel.engine.PlayerColor.RED
import com.example.ludoduel.engine.PlayerColor.YELLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Stacks are laid out from the tokens at rest only (the animator leaves out tokens that are moving).
 * No boxes or count badges: tokens are just shrunk and placed side by side (2) or in a 2x2 (3-4).
 */
class StackLayoutTest {
    private val r0 = TokenKey(RED, 0)
    private val r1 = TokenKey(RED, 1)
    private val r2 = TokenKey(RED, 2)
    private val y0 = TokenKey(YELLOW, 0)
    private val y1 = TokenKey(YELLOW, 1)

    private fun layout(vararg tokens: Pair<TokenKey, Int>) = BoardGeometry.layout(tokens.toMap(), RED)
    private fun squareCenter(progress: Int) = BoardGeometry.center(BoardGeometry.cellOf(RED, progress)!!)

    @Test fun `a stack forms when a second token arrives`() {
        val alone = layout(r0 to 5).getValue(r0)
        assertEquals(1f, alone.scale)
        val stack = layout(r0 to 5, r1 to 5)
        val a = stack.getValue(r0)
        val b = stack.getValue(r1)
        assertEquals(0.6f, a.scale)
        assertEquals(0.6f, b.scale)
        assertEquals(a.point.y, b.point.y) // side by side
        assertNotEquals(a.point.x, b.point.x)
    }

    @Test fun `when one token leaves a stack the other is alone and full size at once`() {
        // r1 is moving, so it is left out: r0 is back to the middle of its square at full size.
        val spot = layout(r0 to 5).getValue(r0)
        assertEquals(1f, spot.scale)
        assertEquals(squareCenter(5), spot.point)
    }

    @Test fun `a capture landing on the square briefly stacks attacker and victim, then the attacker is alone`() {
        // Red lands on yellow's square (red progress 10 is yellow progress 36).
        val both = layout(r0 to 10, y0 to 36)
        assertEquals(0.6f, both.getValue(r0).scale)
        assertEquals(0.6f, both.getValue(y0).scale)
        // The victim is knocked out (moving): the attacker has the square to itself.
        val after = layout(r0 to 10)
        assertEquals(1f, after.getValue(r0).scale)
        assertEquals(squareCenter(10), after.getValue(r0).point)
    }

    @Test fun `a safe square with both colors uses a 2x2 arrangement inside the square`() {
        // Absolute square 26 is Yellow's start (safe): red progress 26, yellow progress 0.
        val spots = layout(r0 to 26, r1 to 26, y0 to 0, y1 to 0)
        val center = squareCenter(26)
        assertTrue(spots.values.all { it.scale == 0.5f })
        assertEquals(4, spots.values.map { it.point }.toSet().size)
        for (spot in spots.values) {
            assertTrue(abs(spot.point.x - center.x) < 0.5f && abs(spot.point.y - center.y) < 0.5f)
        }
    }

    @Test fun `three tokens also use the 2x2 arrangement`() {
        val spots = layout(r0 to 7, r1 to 7, r2 to 7)
        assertTrue(spots.values.all { it.scale == 0.5f })
        assertEquals(3, spots.values.map { it.point }.toSet().size)
    }
}
