package com.example.ludoduel.data

import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor

/**
 * The logic that runs inside the Realtime Database transaction on `rooms/{code}/game`.
 * Pure Kotlin (no Firebase types) so it can be unit-tested. A rejection means the transaction
 * aborts and nothing is written.
 */
object GameReducer {
    const val TURN_MILLIS = 30_000L
    /** The opponent may write a Timeout only this long after the deadline. */
    const val TIMEOUT_GRACE_MILLIS = 5_000L

    class Rejected(message: String) : Exception(message)

    fun initial(serverNow: Long): RoomGame = RoomGame(
        version = 1,
        gameNumber = 1,
        turnDeadline = serverNow + TURN_MILLIS,
        state = GameState.initial(PlayerColor.RED),
    )

    /**
     * Applies [action] by [actorUid] to [current].
     * [expectedVersion] is the version the player saw when they acted; if the game has moved on
     * since then (someone else wrote, or the tap is stale) the action is rejected.
     */
    fun reduce(
        current: RoomGame?,
        expectedVersion: Long,
        action: Action,
        actorUid: String,
        seats: Seats,
        serverNow: Long,
    ): Result<RoomGame> {
        if (current == null) return reject("No game")
        if (current.version != expectedVersion) return reject("Stale action")
        val actor = seats.colorOf(actorUid) ?: return reject("Not a player in this room")

        if (action == Action.Timeout) {
            if (actor == current.state.turn) return reject("A player cannot time out their own turn")
            if (serverNow <= current.turnDeadline + TIMEOUT_GRACE_MILLIS) return reject("Turn time is not over")
        }

        val next = LudoEngine.apply(current.state, action, actor).getOrElse { return Result.failure(it) }
        return Result.success(
            RoomGame(
                version = current.version + 1,
                gameNumber = current.gameNumber,
                turnDeadline = if (next.phase == Phase.OVER) 0 else serverNow + TURN_MILLIS,
                state = next,
            )
        )
    }

    /** Starts the next game in the room. The loser of the finished game moves first. */
    fun rematch(current: RoomGame?, expectedGameNumber: Long, serverNow: Long): Result<RoomGame> {
        if (current == null) return reject("No game")
        if (current.gameNumber != expectedGameNumber) return reject("Rematch already started")
        val winner = current.state.winner
        if (current.state.phase != Phase.OVER || winner == null) return reject("Game is not over")
        return Result.success(
            RoomGame(
                version = current.version + 1,
                gameNumber = current.gameNumber + 1,
                turnDeadline = serverNow + TURN_MILLIS,
                state = GameState.initial(winner.opponent),
            )
        )
    }

    private fun reject(message: String): Result<RoomGame> = Result.failure(Rejected(message))
}
