package com.example.ludoduel.ui.game

import com.example.ludoduel.ui.game.RollMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RollMachineTest {

    @Test fun `normal roll - tap, confirmed, animation, ready again`() {
        val m = RollMachine()
        assertTrue(m.canTap)
        assertTrue(m.tap(0))
        assertFalse(m.canTap) // disabled at once
        assertFalse(m.tap(10)) // a second tap does nothing
        assertTrue(m.rollConfirmed(5))
        assertEquals(State.Showing(5), m.state)
        m.animationFinished(5)
        assertEquals(State.Idle, m.state)
        assertTrue(m.canTap)
    }

    @Test fun `slow confirmation within the timeout still plays`() {
        val m = RollMachine()
        m.tap(0)
        assertFalse(m.tick(4_900))
        assertTrue(m.rollConfirmed(5))
        m.animationFinished(5)
        assertTrue(m.canTap)
    }

    @Test fun `no confirmation within 5 seconds gives up and the die works again`() {
        val m = RollMachine()
        m.tap(0)
        assertTrue(m.tick(5_000)) // show "Couldn't roll - tap again"
        assertEquals(State.Idle, m.state)
        assertFalse(m.tick(9_000)) // only once
        // If the roll did go through after all, it is still shown when it arrives.
        assertTrue(m.rollConfirmed(5))
        m.animationFinished(5)
        assertTrue(m.canTap)
    }

    @Test fun `confirmation arriving before the tap is registered as waiting`() {
        // The turn timer rolled for me (or the update beat the tap handling): it is shown anyway.
        val m = RollMachine()
        assertTrue(m.rollConfirmed(7))
        assertFalse(m.canTap)
        assertFalse(m.tap(0)) // tapping during the animation does nothing
        m.animationFinished(7)
        assertTrue(m.canTap)
    }

    @Test fun `duplicate state updates play only once`() {
        val m = RollMachine()
        m.tap(0)
        assertTrue(m.rollConfirmed(5))
        assertFalse(m.rollConfirmed(5))
        assertFalse(m.rollConfirmed(4)) // older
        m.animationFinished(5)
        assertFalse(m.rollConfirmed(5))
        assertTrue(m.canTap)
    }

    @Test fun `transaction failing re-enables the die`() {
        val m = RollMachine()
        m.tap(0)
        assertTrue(m.sendFailed())
        assertTrue(m.canTap)
        assertFalse(m.sendFailed()) // nothing to fail any more
    }

    @Test fun `a failure report after the roll was confirmed is ignored`() {
        val m = RollMachine()
        m.tap(0)
        m.rollConfirmed(5)
        assertFalse(m.sendFailed())
        assertEquals(State.Showing(5), m.state)
    }

    @Test fun `app switch during a roll - back after the timeout, the die works again`() {
        val m = RollMachine()
        m.tap(0)
        // The app was in the background for 20 s; on return the safety net runs.
        assertTrue(m.tick(20_000))
        assertTrue(m.canTap)
    }

    @Test fun `app switch during the animation - a snap to the latest state ends it`() {
        val m = RollMachine()
        m.tap(0)
        m.rollConfirmed(5)
        m.snapped(9)
        assertTrue(m.canTap)
        assertFalse(m.rollConfirmed(8)) // already past it
        assertTrue(m.rollConfirmed(10))
    }

    @Test fun `animation finished for another version does not end the current one`() {
        val m = RollMachine()
        m.rollConfirmed(5)
        m.animationFinished(4)
        assertEquals(State.Showing(5), m.state)
    }
}
