package com.example.ludoduel.engine

import com.example.ludoduel.engine.PlayerColor.RED
import com.example.ludoduel.engine.PlayerColor.YELLOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LudoEngineTest {

    private fun state(
        red: List<Int> = listOf(-1, -1, -1, -1),
        yellow: List<Int> = listOf(-1, -1, -1, -1),
        turn: PlayerColor = RED,
        phase: Phase = Phase.ROLL,
        dice: Int? = null,
        sixes: Int = 0,
    ) = GameState.initial(turn).copy(red = red, yellow = yellow, phase = phase, dice = dice, sixesInRow = sixes)

    private fun GameState.act(action: Action, actor: PlayerColor = turn): GameState =
        LudoEngine.apply(this, action, actor).getOrThrow()

    private fun GameState.rejects(action: Action, actor: PlayerColor = turn): Boolean =
        LudoEngine.apply(this, action, actor).isFailure

    /** Yellow progress that puts a yellow token on absolute square [square]. */
    private fun yellowAt(square: Int) = (square - 26 + 52) % 52

    // 26. No token out of the yard and the roll isn't 6 -> auto pass.
    @Test fun `26 all tokens in yard and no six passes the turn`() {
        for (roll in 1..5) {
            val next = state().act(Action.Roll(roll))
            assertEquals(YELLOW, next.turn)
            assertEquals(Phase.ROLL, next.phase)
            assertNull(next.dice)
            assertEquals(roll, next.lastAction?.dice)
        }
    }

    // 27. Rolled 6 with all tokens in the yard -> only "bring out" moves are legal.
    @Test fun `27 six with all tokens in yard only allows bringing out`() {
        val s = state()
        assertEquals(listOf(0, 1, 2, 3), LudoEngine.legalMoves(s, RED, 6))
        val rolled = s.act(Action.Roll(6))
        assertEquals(Phase.MOVE, rolled.phase)
        val moved = rolled.act(Action.Move(2))
        assertEquals(listOf(-1, -1, 0, -1), moved.red)
        assertEquals(RED, moved.turn) // six gives an extra turn
        assertEquals(Phase.ROLL, moved.phase)
    }

    // 28. Exact roll needed to finish; overshoot is illegal for that token.
    @Test fun `28 exact roll needed to reach home`() {
        val s = state(red = listOf(53, -1, -1, -1))
        assertEquals(listOf(0), LudoEngine.legalMoves(s, RED, 3))
        assertTrue(LudoEngine.legalMoves(s, RED, 4).isEmpty())
        assertTrue(LudoEngine.legalMoves(s, RED, 5).isEmpty())
        // Overshoot with no other option passes the turn.
        assertEquals(YELLOW, s.act(Action.Roll(4)).turn)
        // A move request for the overshooting token is rejected.
        assertTrue(state(red = listOf(53, 10, -1, -1), phase = Phase.MOVE, dice = 4).rejects(Action.Move(0)))
        // Exact roll finishes and gives an extra turn.
        val home = s.act(Action.Roll(3)).act(Action.Move(0))
        assertEquals(56, home.red[0])
        assertEquals(RED, home.turn)
        assertEquals(Phase.ROLL, home.phase)
    }

    // 29. Three 6s in a row -> cancelled, turn passes, counter resets.
    @Test fun `29 third six is cancelled and passes the turn`() {
        var s = state(red = listOf(0, -1, -1, -1))
        s = s.act(Action.Roll(6)).act(Action.Move(0))
        assertEquals(1, s.sixesInRow)
        s = s.act(Action.Roll(6)).act(Action.Move(0))
        assertEquals(2, s.sixesInRow)
        val before = s.red
        s = s.act(Action.Roll(6))
        assertEquals(YELLOW, s.turn)
        assertEquals(Phase.ROLL, s.phase)
        assertEquals(0, s.sixesInRow)
        assertEquals(before, s.red)
        assertEquals(6, s.lastAction?.dice)
    }

    @Test fun `29b a non-six resets the six counter`() {
        var s = state(red = listOf(0, -1, -1, -1))
        s = s.act(Action.Roll(6)).act(Action.Move(0)) // extra turn, sixes = 1
        s = s.act(Action.Roll(6)).act(Action.Move(0)) // sixes = 2
        s = s.act(Action.Roll(3))
        assertEquals(0, s.sixesInRow)
        assertEquals(Phase.MOVE, s.phase)
    }

    // 30. Capture on a normal square; no capture on safe squares; no capture of a block.
    @Test fun `30 capture on a normal square sends the token home and gives an extra turn`() {
        val s = state(red = listOf(5, -1, -1, -1), yellow = listOf(yellowAt(10), -1, -1, -1))
        val next = s.act(Action.Roll(5)).act(Action.Move(0))
        assertEquals(10, next.red[0])
        assertEquals(-1, next.yellow[0])
        assertEquals(0, next.lastAction?.captured)
        assertEquals(RED, next.turn)
        assertEquals(Phase.ROLL, next.phase)
    }

    @Test fun `30 no capture on a safe square`() {
        val s = state(red = listOf(3, -1, -1, -1), yellow = listOf(yellowAt(8), -1, -1, -1))
        val next = s.act(Action.Roll(5)).act(Action.Move(0))
        assertEquals(8, next.red[0])
        assertEquals(yellowAt(8), next.yellow[0])
        assertNull(next.lastAction?.captured)
        assertEquals(YELLOW, next.turn)
    }

    @Test fun `30 a block cannot be captured`() {
        val s = state(red = listOf(5, -1, -1, -1), yellow = listOf(yellowAt(10), yellowAt(10), -1, -1))
        assertTrue(LudoEngine.legalMoves(s, RED, 5).isEmpty())
        val next = s.act(Action.Roll(5))
        assertEquals(YELLOW, next.turn)
        assertEquals(listOf(yellowAt(10), yellowAt(10), -1, -1), next.yellow)
    }

    // 31. Moving past or onto an opponent block is illegal.
    @Test fun `31 cannot move past or onto an opponent block`() {
        val s = state(red = listOf(5, 30, -1, -1), yellow = listOf(yellowAt(7), yellowAt(7), -1, -1))
        assertEquals(listOf(1), LudoEngine.legalMoves(s, RED, 4)) // token 0 would pass square 7
        assertEquals(listOf(1), LudoEngine.legalMoves(s, RED, 2)) // token 0 would land on 7
        assertEquals(listOf(0, 1), LudoEngine.legalMoves(s, RED, 1))
        assertTrue(s.copy(phase = Phase.MOVE, dice = 4).rejects(Action.Move(0)))
    }

    @Test fun `31 a block on the start square keeps tokens in the yard`() {
        val s = state(yellow = listOf(yellowAt(0), yellowAt(0), -1, -1))
        assertTrue(LudoEngine.legalMoves(s, RED, 6).isEmpty())
        assertEquals(YELLOW, s.act(Action.Roll(6)).turn)
    }

    @Test fun `31 a block of the mover's own color does not stop it`() {
        val s = state(red = listOf(5, 7, 7, -1))
        assertEquals(listOf(0, 1, 2), LudoEngine.legalMoves(s, RED, 4))
    }

    // 32. Own tokens can stack; two own tokens form a block.
    @Test fun `32 own tokens stack and form a block for the opponent`() {
        val s = state(red = listOf(3, 5, -1, -1), yellow = listOf(yellowAt(1), -1, -1, -1))
        val stacked = s.act(Action.Roll(2)).act(Action.Move(0))
        assertEquals(listOf(5, 5, -1, -1), stacked.red)
        assertEquals(YELLOW, stacked.turn)
        // Yellow at square 1 cannot pass or land on the red block at 5.
        assertTrue(LudoEngine.legalMoves(stacked, YELLOW, 4).isEmpty())
        assertEquals(listOf(1, 2, 3), LudoEngine.legalMoves(stacked, YELLOW, 6)) // only yard tokens
        assertEquals(listOf(0), LudoEngine.legalMoves(stacked, YELLOW, 3))
    }

    @Test fun `32 tokens in the home column never form a block`() {
        val s = state(red = listOf(52, 52, -1, -1), yellow = listOf(yellowAt(49), -1, -1, -1))
        assertEquals(listOf(0), LudoEngine.legalMoves(s, YELLOW, 5)) // passes squares 50, 51, 0, 1
    }

    // 33. Extra turn from capture + 6 in the same move is only one extra turn.
    @Test fun `33 capture with a six gives only one extra turn`() {
        val s = state(red = listOf(4, 20, -1, -1), yellow = listOf(yellowAt(10), -1, -1, -1))
        val afterCapture = s.act(Action.Roll(6)).act(Action.Move(0))
        assertEquals(-1, afterCapture.yellow[0])
        assertEquals(RED, afterCapture.turn)
        assertEquals(Phase.ROLL, afterCapture.phase)
        // The next plain move ends the turn: no second extra turn was banked.
        val after = afterCapture.act(Action.Roll(2)).act(Action.Move(1))
        assertEquals(YELLOW, after.turn)
    }

    // 34. Last token reaching home ends the game instantly - no extra turn.
    @Test fun `34 last token home ends the game`() {
        val s = state(red = listOf(56, 56, 56, 53))
        val over = s.act(Action.Roll(3)).act(Action.Move(3))
        assertEquals(Phase.OVER, over.phase)
        assertEquals(RED, over.winner)
        assertEquals(WinReason.ALL_HOME, over.winReason)
        assertNull(over.dice)
        assertTrue(over.rejects(Action.Roll(4)))
        assertTrue(over.rejects(Action.Roll(4), YELLOW))
    }

    // 35. Yellow crossing absolute square 51 -> 0 is calculated correctly.
    @Test fun `35 yellow wraps around from square 51 to 0`() {
        assertEquals(51, LudoEngine.absoluteSquare(YELLOW, 25))
        assertEquals(0, LudoEngine.absoluteSquare(YELLOW, 26))
        assertEquals(24, LudoEngine.absoluteSquare(YELLOW, 50))
        // Yellow at square 50 moves 3 -> square 1 and captures the red token there.
        val s = state(red = listOf(1, -1, -1, -1), yellow = listOf(yellowAt(50), -1, -1, -1), turn = YELLOW)
        val next = s.act(Action.Roll(3)).act(Action.Move(0))
        assertEquals(27, next.yellow[0])
        assertEquals(1, LudoEngine.absoluteSquare(YELLOW, next.yellow[0]))
        assertEquals(-1, next.red[0])
    }

    @Test fun `35 a red block after the wrap stops yellow`() {
        val s = state(red = listOf(1, 1, -1, -1), yellow = listOf(yellowAt(50), -1, -1, -1), turn = YELLOW)
        assertTrue(LudoEngine.legalMoves(s, YELLOW, 3).isEmpty())
        assertTrue(LudoEngine.legalMoves(s, YELLOW, 4).isEmpty())
        assertEquals(listOf(0), LudoEngine.legalMoves(s, YELLOW, 2))
    }

    // 36. Yellow never enters Red's home column and vice versa.
    @Test fun `36 each color only enters its own home column`() {
        // Yellow passes red's home entry (square 50) and stays on the shared track.
        val y = state(yellow = listOf(yellowAt(48), -1, -1, -1), turn = YELLOW)
        val movedY = y.act(Action.Roll(5)).act(Action.Move(0))
        assertEquals(yellowAt(1), movedY.yellow[0])
        assertTrue(movedY.yellow[0] <= LAST_TRACK)
        // Red passes yellow's home entry (square 24) and stays on the shared track.
        val r = state(red = listOf(22, -1, -1, -1))
        val movedR = r.act(Action.Roll(5)).act(Action.Move(0))
        assertEquals(27, movedR.red[0])
        // Each color turns into its home column after its own progress 50.
        val intoHome = state(red = listOf(49, -1, -1, -1)).act(Action.Roll(4)).act(Action.Move(0))
        assertEquals(53, intoHome.red[0])
        val intoHomeY = state(yellow = listOf(49, -1, -1, -1), turn = YELLOW).act(Action.Roll(4)).act(Action.Move(0))
        assertEquals(53, intoHomeY.yellow[0])
    }

    @Test fun `36 tokens in a home column are never captured`() {
        // Red in its home column at 51 and yellow landing on red's entry square area stays harmless.
        val s = state(red = listOf(51, -1, -1, -1), yellow = listOf(yellowAt(45), -1, -1, -1), turn = YELLOW)
        val next = s.act(Action.Roll(5)).act(Action.Move(0))
        assertEquals(51, next.red[0])
        assertNull(next.lastAction?.captured)
    }

    // 37. Neither player has any legal move -> turns keep passing, no loop.
    @Test fun `37 no legal moves for both players still alternates turns`() {
        var s = state()
        repeat(10) {
            val who = s.turn
            s = s.act(Action.Roll(3))
            assertEquals(who.opponent, s.turn)
            assertEquals(Phase.ROLL, s.phase)
        }
        // Both colors blocked: every roll still passes the turn.
        var blocked = state(red = listOf(0, 0, -1, -1), yellow = listOf(yellowAt(0 + 2), yellowAt(2), -1, -1))
        repeat(6) { i ->
            val roll = 1 + i
            val who = blocked.turn
            val next = blocked.act(Action.Roll(roll))
            if (next.phase == Phase.MOVE) {
                blocked = next.act(Action.Move(LudoEngine.legalMoves(next, who, roll).first()))
            } else {
                assertEquals(who.opponent, next.turn)
                blocked = next
            }
        }
    }

    // Timers and forfeits.
    @Test fun `timeout passes the turn and three in a row forfeit`() {
        var s = state()
        s = s.act(Action.Timeout, YELLOW)
        assertEquals(YELLOW, s.turn)
        assertEquals(1, s.missedRed)
        s = s.act(Action.Roll(2)) // yellow manual roll, passes
        s = s.act(Action.Timeout, YELLOW)
        assertEquals(2, s.missedRed)
        s = s.act(Action.Roll(2))
        s = s.act(Action.Timeout, YELLOW)
        assertEquals(Phase.OVER, s.phase)
        assertEquals(YELLOW, s.winner)
        assertEquals(WinReason.FORFEIT, s.winReason)
    }

    @Test fun `manual action resets missed turns and auto roll counts as missed`() {
        var s = state().act(Action.Timeout, YELLOW).act(Action.Roll(2))
        assertEquals(1, s.missedRed)
        s = s.act(Action.Roll(3)) // red manual
        assertEquals(0, s.missedRed)
        s = s.act(Action.Roll(2)) // yellow
        s = s.act(Action.Roll(3, auto = true))
        assertEquals(1, s.missedRed)
        s = s.act(Action.Roll(2)).act(Action.Roll(3, auto = true)).act(Action.Roll(2))
        val over = s.act(Action.Roll(3, auto = true))
        assertEquals(Phase.OVER, over.phase)
        assertEquals(YELLOW, over.winner)
        assertEquals(WinReason.FORFEIT, over.winReason)
    }

    @Test fun `auto move keeps the missed counter`() {
        val s = state(red = listOf(10, -1, -1, -1)).copy(missedRed = 1)
        val rolled = s.act(Action.Roll(3, auto = true))
        assertEquals(2, rolled.missedRed)
        val moved = rolled.act(Action.Move(0, auto = true))
        assertEquals(2, moved.missedRed)
        assertTrue(moved.lastAction!!.auto)
    }

    @Test fun `forfeit by leaving makes the other player win`() {
        val s = state()
        val over = s.act(Action.Forfeit, YELLOW)
        assertEquals(Phase.OVER, over.phase)
        assertEquals(RED, over.winner)
        assertEquals(WinReason.LEFT, over.winReason)
        assertEquals(ActionType.LEFT, over.lastAction?.type)
    }

    // Illegal actions return errors and never mutate the input.
    @Test fun `illegal actions are rejected`() {
        val s = state(red = listOf(10, -1, -1, -1))
        assertTrue(s.rejects(Action.Roll(3), YELLOW))
        assertTrue(s.rejects(Action.Roll(0)))
        assertTrue(s.rejects(Action.Roll(7)))
        assertTrue(s.rejects(Action.Move(0)))
        val rolled = s.act(Action.Roll(3))
        assertTrue(rolled.rejects(Action.Roll(3)))
        assertTrue(rolled.rejects(Action.Move(1))) // in yard, needs a 6
        assertTrue(rolled.rejects(Action.Move(9)))
        assertTrue(rolled.rejects(Action.Move(0), YELLOW))
    }

    @Test fun `apply never mutates its input`() {
        val s = state(red = listOf(5, -1, -1, -1), yellow = listOf(yellowAt(10), -1, -1, -1))
        val copy = s.copy()
        s.act(Action.Roll(5)).act(Action.Move(0))
        assertEquals(copy, s)
    }

    @Test fun `state validation`() {
        assertTrue(LudoEngine.isValid(state()))
        assertFalse(LudoEngine.isValid(state(red = listOf(-1, -1, -1))))
        assertFalse(LudoEngine.isValid(state(red = listOf(-2, -1, -1, -1))))
        assertFalse(LudoEngine.isValid(state(yellow = listOf(57, -1, -1, -1))))
        assertFalse(LudoEngine.isValid(state(phase = Phase.MOVE, dice = null)))
        assertFalse(LudoEngine.isValid(state(phase = Phase.MOVE, dice = 7)))
        assertFalse(LudoEngine.isValid(state(phase = Phase.OVER)))
    }

    @Test fun `dice rolls stay in range`() {
        val seen = (1..2000).map { Dice.roll() }.toSet()
        assertEquals((1..6).toSet(), seen)
    }

    // Only one token can move -> the app moves it by itself; two or more -> the player chooses.
    @Test fun `only movable token - one token out, others in the yard, no six`() {
        val s = state(red = listOf(10, -1, -1, -1)).act(Action.Roll(3))
        assertEquals(0, LudoEngine.onlyMovableToken(s, RED))
    }

    @Test fun `only movable token - near-home token would overshoot, the other moves`() {
        val s = state(red = listOf(53, 20, 56, 56)).act(Action.Roll(6))
        assertEquals(1, LudoEngine.onlyMovableToken(s, RED))
    }

    @Test fun `only movable token - a token behind an opponent block cannot move`() {
        val s = state(red = listOf(5, 30, 56, 56), yellow = listOf(yellowAt(7), yellowAt(7), -1, -1)).act(Action.Roll(4))
        assertEquals(1, LudoEngine.onlyMovableToken(s, RED))
    }

    @Test fun `player chooses when two or more tokens can move`() {
        assertNull(LudoEngine.onlyMovableToken(state(red = listOf(10, 20, -1, -1)).act(Action.Roll(3)), RED))
        // A six with all four in the yard: the player picks which one comes out.
        assertNull(LudoEngine.onlyMovableToken(state().act(Action.Roll(6)), RED))
        // Two tokens on the same square: the player taps the one to move.
        assertNull(LudoEngine.onlyMovableToken(state(red = listOf(10, 10, -1, -1)).act(Action.Roll(3)), RED))
    }

    @Test fun `only movable token is null when nothing can move or it is not the player's move`() {
        val passed = state().act(Action.Roll(3)) // no legal move: the turn passed
        assertNull(LudoEngine.onlyMovableToken(passed, RED))
        val s = state(red = listOf(10, -1, -1, -1)).act(Action.Roll(3))
        assertNull(LudoEngine.onlyMovableToken(s, YELLOW))
        assertNull(LudoEngine.onlyMovableToken(state(red = listOf(10, -1, -1, -1)), RED)) // still ROLL phase
    }
}
