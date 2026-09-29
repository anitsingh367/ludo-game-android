package com.example.ludoduel.data

import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.PlayerColor

/** The `game` node of a room: the engine state plus the sync bookkeeping stored next to it. */
data class RoomGame(
    /** Increases by exactly 1 on every write. */
    val version: Long,
    /** 1 for the first game in the room, +1 for every rematch. */
    val gameNumber: Long,
    /** Server time (ms) when the current player's turn runs out. 0 when the game is over. */
    val turnDeadline: Long,
    val state: GameState,
)

/** Who sits where. The host is always Red and the guest is always Yellow. */
data class Seats(val hostUid: String, val guestUid: String) {
    fun colorOf(uid: String): PlayerColor? = when (uid) {
        hostUid -> PlayerColor.RED
        guestUid -> PlayerColor.YELLOW
        else -> null
    }

    fun uidOf(color: PlayerColor): String = if (color == PlayerColor.RED) hostUid else guestUid
}
