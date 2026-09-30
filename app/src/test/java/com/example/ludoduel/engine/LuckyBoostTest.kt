package com.example.ludoduel.engine

import com.example.ludoduel.engine.PlayerColor.RED
import com.example.ludoduel.engine.PlayerColor.YELLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class LuckyBoostTest {
    private val n = 100_000

    private fun frequencies(roll: () -> Int): DoubleArray {
        val counts = IntArray(7)
        repeat(n) { counts[roll()]++ }
        return DoubleArray(7) { counts[it].toDouble() / n }
    }

    private fun assertNear(expected: Double, actual: Double, what: String) =
        assertTrue("$what: expected about $expected, got $actual", abs(expected - actual) < 0.01)

    @Test fun `normal rolls are fair - 100,000 rolls are about 1 in 6 each`() {
        val random = Random(1)
        val f = frequencies { Dice.roll(streak = 0, boost = true, random = random) }
        for (face in 1..6) assertNear(1.0 / 6, f[face], "face $face")
        // The real SecureRandom path too.
        val g = frequencies { Dice.roll() }
        for (face in 1..6) assertNear(1.0 / 6, g[face], "secure face $face")
    }

    @Test fun `boost probabilities match the table and the other numbers stay equal`() {
        val table = mapOf(0 to 1.0 / 6, 1 to 1.0 / 6, 2 to 1.0 / 6, 3 to 1.0 / 3, 4 to 1.0 / 2, 5 to 1.0, 9 to 1.0)
        for ((streak, sixChance) in table) {
            val random = Random(streak + 10)
            val f = frequencies { Dice.roll(streak, boost = true, random = random) }
            assertNear(sixChance, f[6], "streak $streak, a six")
            for (face in 1..5) assertNear((1 - sixChance) / 5, f[face], "streak $streak, face $face")
        }
    }

    @Test fun `boost off means normal rolls whatever the streak`() {
        val random = Random(4)
        val f = frequencies { Dice.roll(streak = 9, boost = false, random = random) }
        for (face in 1..6) assertNear(1.0 / 6, f[face], "face $face")
    }

    @Test fun `the boost never applies with a token on the board`() {
        val onTrack = GameState.initial(RED).copy(red = listOf(12, -1, -1, -1), noSixRed = 8)
        val inHomeColumn = GameState.initial(RED).copy(red = listOf(53, -1, -1, -1), noSixRed = 8)
        for (state in listOf(onTrack, inHomeColumn)) {
            assertEquals(0, Dice.luckyStreak(state, RED))
            val random = Random(5)
            val f = frequencies { Dice.rollFor(state, RED, boost = true, random = random) }
            assertNear(1.0 / 6, f[6], "six with a token on the board")
        }
        // Tokens that are all in the yard or finished do count as "cannot play".
        val stuck = GameState.initial(RED).copy(red = listOf(56, -1, -1, 56), noSixRed = 3)
        assertEquals(3, Dice.luckyStreak(stuck, RED))
    }

    @Test fun `the engine counts rolls without a six only while no token is on the board`() {
        var s = GameState.initial(RED)
        s = LudoEngine.apply(s, Action.Roll(3), RED).getOrThrow()
        assertEquals(1, s.noSixRed)
        s = LudoEngine.apply(s, Action.Roll(2), YELLOW).getOrThrow()
        s = LudoEngine.apply(s, Action.Roll(4), RED).getOrThrow()
        assertEquals(2, s.noSixRed)
        s = LudoEngine.apply(s, Action.Roll(2), YELLOW).getOrThrow()
        s = LudoEngine.apply(s, Action.Roll(6), RED).getOrThrow() // a six resets
        assertEquals(0, s.noSixRed)
        s = LudoEngine.apply(s, Action.Move(0), RED).getOrThrow()
        s = LudoEngine.apply(s, Action.Roll(3), RED).getOrThrow() // a token is on the board now
        assertEquals(0, s.noSixRed)
        // A timeout is not a roll: the count does not change.
        val t = LudoEngine.apply(GameState.initial(RED).copy(noSixRed = 2), Action.Timeout, YELLOW).getOrThrow()
        assertEquals(2, t.noSixRed)
    }

    @Test fun `with the boost on nobody waits more than 6 turns in the yard for a six`() {
        val random = Random(20260930)
        var longestWait = 0
        repeat(1_000) { game ->
            var state = GameState.initial(if (game % 2 == 0) RED else YELLOW)
            val waiting = mutableMapOf(RED to 0, YELLOW to 0)
            var actions = 0
            while (state.phase != Phase.OVER && actions < 100_000) {
                val actor = state.turn
                val action: Action = if (state.phase == Phase.ROLL) {
                    Action.Roll(Dice.rollFor(state, actor, boost = true, random = random))
                } else {
                    val legal = LudoEngine.legalMoves(state, actor, state.dice!!)
                    Action.Move(legal[random.nextInt(legal.size)])
                }
                if (action is Action.Roll) {
                    val stuck = !state.hasTokenOnBoard(actor) && state.tokensOf(actor).any { it == YARD }
                    waiting[actor] = if (stuck && action.value != 6) waiting.getValue(actor) + 1 else 0
                    longestWait = maxOf(longestWait, waiting.getValue(actor))
                }
                state = LudoEngine.apply(state, action, actor).getOrThrow()
                actions++
            }
            assertEquals(Phase.OVER, state.phase)
        }
        // At most 5 turns without a six, so the six comes on the 6th turn at the latest.
        assertTrue("longest wait was $longestWait turns without a six", longestWait <= 5)
    }
}
