package com.example.ludoduel.data

import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.WinReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests the transaction reducer: current game + action + uid -> new game or rejection. */
class GameReducerTest {
    private val host = "hostUid"
    private val guest = "guestUid"
    private val seats = Seats(host, guest)
    private val t0 = 1_000_000L

    private fun start() = GameReducer.initial(t0)

    @Test fun `initial game has host first and a 30 second deadline`() {
        val g = start()
        assertEquals(1L, g.version)
        assertEquals(1L, g.gameNumber)
        assertEquals(PlayerColor.RED, g.state.turn)
        assertEquals(t0 + 30_000, g.turnDeadline)
    }

    @Test fun `a valid action increases version by exactly one and resets the deadline`() {
        val g = GameReducer.reduce(start(), 1, Action.Roll(6), host, seats, t0 + 5_000).getOrThrow()
        assertEquals(2L, g.version)
        assertEquals(Phase.MOVE, g.state.phase)
        assertEquals(t0 + 35_000, g.turnDeadline)
    }

    @Test fun `stale version is rejected`() {
        val r = GameReducer.reduce(start(), 0, Action.Roll(6), host, seats, t0)
        assertTrue(r.exceptionOrNull() is GameReducer.Rejected)
    }

    @Test fun `double tap with the same expected version applies only once`() {
        val first = GameReducer.reduce(start(), 1, Action.Roll(6), host, seats, t0).getOrThrow()
        // The retried transaction now sees version 2 but the tap expected version 1.
        assertTrue(GameReducer.reduce(first, 1, Action.Roll(6), host, seats, t0).isFailure)
        assertTrue(GameReducer.reduce(first, 1, Action.Move(0), host, seats, t0).isFailure)
    }

    @Test fun `only the current player may roll or move`() {
        assertTrue(GameReducer.reduce(start(), 1, Action.Roll(3), guest, seats, t0).isFailure)
        assertTrue(GameReducer.reduce(start(), 1, Action.Roll(3), "stranger", seats, t0).isFailure)
    }

    @Test fun `missing game is rejected`() {
        assertTrue(GameReducer.reduce(null, 1, Action.Roll(3), host, seats, t0).isFailure)
    }

    @Test fun `opponent timeout only after deadline plus five seconds`() {
        val g = start()
        assertTrue(GameReducer.reduce(g, 1, Action.Timeout, guest, seats, g.turnDeadline).isFailure)
        assertTrue(GameReducer.reduce(g, 1, Action.Timeout, guest, seats, g.turnDeadline + 5_000).isFailure)
        val ok = GameReducer.reduce(g, 1, Action.Timeout, guest, seats, g.turnDeadline + 5_001).getOrThrow()
        assertEquals(PlayerColor.YELLOW, ok.state.turn)
        assertEquals(1, ok.state.missedRed)
        assertEquals(2L, ok.version)
    }

    @Test fun `current player cannot time out their own turn`() {
        val g = start()
        assertTrue(GameReducer.reduce(g, 1, Action.Timeout, host, seats, g.turnDeadline + 60_000).isFailure)
    }

    @Test fun `three opponent timeouts forfeit the game`() {
        var g = start()
        var now = t0
        repeat(2) {
            now = g.turnDeadline + 6_000
            g = GameReducer.reduce(g, g.version, Action.Timeout, guest, seats, now).getOrThrow()
            g = GameReducer.reduce(g, g.version, Action.Roll(2), guest, seats, now).getOrThrow()
        }
        now = g.turnDeadline + 6_000
        g = GameReducer.reduce(g, g.version, Action.Timeout, guest, seats, now).getOrThrow()
        assertEquals(Phase.OVER, g.state.phase)
        assertEquals(PlayerColor.YELLOW, g.state.winner)
        assertEquals(WinReason.FORFEIT, g.state.winReason)
        assertEquals(0L, g.turnDeadline)
    }

    @Test fun `either player can leave at any time`() {
        val g = GameReducer.reduce(start(), 1, Action.Forfeit, guest, seats, t0).getOrThrow()
        assertEquals(Phase.OVER, g.state.phase)
        assertEquals(PlayerColor.RED, g.state.winner)
        assertEquals(WinReason.LEFT, g.state.winReason)
    }

    @Test fun `rematch starts a fresh game with the loser first`() {
        val over = GameReducer.reduce(start(), 1, Action.Forfeit, host, seats, t0).getOrThrow()
        val next = GameReducer.rematch(over, 1, t0 + 10).getOrThrow()
        assertEquals(2L, next.gameNumber)
        assertEquals(over.version + 1, next.version)
        assertEquals(PlayerColor.RED, next.state.turn) // red left, so red lost and starts
        assertEquals(Phase.ROLL, next.state.phase)
        assertTrue(next.state.red.all { it == -1 } && next.state.yellow.all { it == -1 })
        // A second rematch request for the same game is rejected (both phones may try).
        assertTrue(GameReducer.rematch(next, 1, t0 + 20).isFailure)
    }

    @Test fun `rematch is rejected while the game is running`() {
        assertTrue(GameReducer.rematch(start(), 1, t0).isFailure)
    }
}
