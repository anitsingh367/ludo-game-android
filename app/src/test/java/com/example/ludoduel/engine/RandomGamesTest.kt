package com.example.ludoduel.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Plays 1,000 full games with random legal moves and checks the invariants after every action. */
class RandomGamesTest {

    @Test fun `1000 random games all finish with valid states`() {
        val random = Random(20260929)
        var totalActions = 0L
        repeat(1000) { game ->
            var state = GameState.initial(if (game % 2 == 0) PlayerColor.RED else PlayerColor.YELLOW)
            var actions = 0
            while (state.phase != Phase.OVER) {
                val actor = state.turn
                val action: Action = when {
                    // Rare timeouts exercise the missed-turn path without ending most games.
                    random.nextInt(200) == 0 -> Action.Timeout
                    state.phase == Phase.ROLL -> Action.Roll(random.nextInt(1, 7))
                    else -> {
                        val legal = LudoEngine.legalMoves(state, actor, state.dice!!)
                        assertTrue("MOVE phase must have a legal move", legal.isNotEmpty())
                        Action.Move(legal[random.nextInt(legal.size)])
                    }
                }
                val next = LudoEngine.apply(state, action, actor)
                assertTrue("game $game action $action failed: ${next.exceptionOrNull()}", next.isSuccess)
                state = next.getOrThrow()
                assertTrue("invalid state in game $game: $state", LudoEngine.isValid(state))
                assertEquals(TOKENS_PER_PLAYER, state.red.size)
                assertEquals(TOKENS_PER_PLAYER, state.yellow.size)
                actions++
                assertTrue("game $game did not end", actions < 100_000)
            }
            assertNotNull(state.winner)
            if (state.winReason == WinReason.ALL_HOME) {
                assertTrue(state.tokensOf(state.winner!!).all { it == HOME })
            }
            totalActions += actions
        }
        assertTrue(totalActions > 0)
    }
}
