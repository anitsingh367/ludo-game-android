package com.example.ludoduel.ui.game

import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor.RED
import com.example.ludoduel.ui.game.RollMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The stress test (formerly a debug "×200" button): I (Red) roll 200 times in a row through my die's
 * state machine while the game is played by the rules engine, and the network misbehaves now and
 * then: a refused write, no answer within 5 s, a repeated update, a jump after reconnecting. After
 * every roll the die must be ready again; it never gets stuck.
 */
class RollStressTest {

    @Test fun `200 rolls in a row never get stuck`() {
        val random = Random(200)
        val die = RollMachine()
        var state = GameState.initial(RED)
        var version = 0L
        var now = 0L
        var rolls = 0
        while (rolls < 200) {
            now += 1_000
            if (state.phase == Phase.OVER) state = GameState.initial(state.winner!!.opponent) // rematch
            val actor = state.turn
            if (state.phase == Phase.MOVE) {
                val legal = LudoEngine.legalMoves(state, actor, state.dice!!)
                state = LudoEngine.apply(state, Action.Move(legal.random(random)), actor).getOrThrow()
                version++
                continue
            }
            if (actor == RED) {
                assertTrue("roll $rolls: the die must be ready", die.canTap)
                assertTrue(die.tap(now))
                rolls++
                when (random.nextInt(10)) {
                    0 -> { // The write was refused: "Couldn't roll", and the die works again.
                        assertTrue(die.sendFailed())
                        continue
                    }
                    1 -> { // No answer within 5 s: the safety net gives up.
                        now += RollMachine.TIMEOUT_MILLIS
                        assertTrue(die.tick(now))
                        continue
                    }
                }
            }
            state = LudoEngine.apply(state, Action.Roll(random.nextInt(1, 7)), actor).getOrThrow()
            version++
            if (actor == RED) {
                if (random.nextInt(10) == 0) {
                    die.snapped(version) // reconnected: the board jumped without animating
                } else {
                    assertTrue(die.rollConfirmed(version))
                    die.animationFinished(version)
                    assertFalse(die.rollConfirmed(version)) // a repeated update plays nothing
                }
                assertEquals("roll $rolls", State.Idle, die.state)
            }
        }
    }
}
