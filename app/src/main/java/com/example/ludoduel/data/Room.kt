package com.example.ludoduel.data

import com.example.ludoduel.engine.PlayerColor

const val SCHEMA_VERSION = 1L
const val ROOM_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L

enum class RoomStatus { WAITING, PLAYING, FINISHED, ABANDONED }

data class Player(
    val color: PlayerColor,
    val name: String,
    val connected: Boolean,
    val lastSeen: Long,
)

/** Everything under `rooms/{code}` as the app sees it. */
data class Room(
    val code: String,
    val schemaVersion: Long,
    val expiresAt: Long,
    val hostUid: String,
    val guestUid: String?,
    val status: RoomStatus,
    val players: Map<String, Player>,
    /** Null while waiting for a guest, or when [gameCorrupt] is true. */
    val game: RoomGame?,
    /** True when a `game` node exists but fails validation. */
    val gameCorrupt: Boolean,
    /** uid -> the gameNumber that player asked a rematch for. */
    val rematch: Map<String, Long>,
) {
    val seats: Seats? get() = guestUid?.let { Seats(hostUid, it) }

    companion object {
        /** Returns null when the room does not exist or its basic fields are missing. */
        fun decode(code: String, raw: Any?): Room? {
            val m = raw as? Map<*, *> ?: return null
            val hostUid = m["hostUid"] as? String ?: return null
            val guestUid = m["guestUid"] as? String
            val status = (m["status"] as? String)?.uppercase()
                ?.let { s -> RoomStatus.entries.firstOrNull { it.name == s } } ?: return null
            val players = (m["players"] as? Map<*, *>).orEmpty().mapNotNull { (uid, p) ->
                val pm = p as? Map<*, *> ?: return@mapNotNull null
                val color = when (pm["color"]) {
                    "red" -> PlayerColor.RED
                    "yellow" -> PlayerColor.YELLOW
                    else -> return@mapNotNull null
                }
                uid as String to Player(
                    color = color,
                    name = pm["name"] as? String ?: return@mapNotNull null,
                    connected = pm["connected"] as? Boolean ?: return@mapNotNull null,
                    lastSeen = (pm["lastSeen"] as? Number)?.toLong() ?: return@mapNotNull null,
                )
            }.toMap()
            val expiresAt = (m["expiresAt"] as? Number)?.toLong() ?: return null
            val rawGame = m["game"]
            val game = guestUid?.let { GameCodec.decode(rawGame, Seats(hostUid, it)) }
            return Room(
                code = code,
                // A missing version is treated as "unknown", which shows the "please update" message.
                schemaVersion = (m["schemaVersion"] as? Number)?.toLong() ?: 0L,
                expiresAt = expiresAt,
                hostUid = hostUid,
                guestUid = guestUid,
                status = status,
                players = players,
                game = game,
                gameCorrupt = rawGame != null && game == null,
                rematch = (m["rematch"] as? Map<*, *>).orEmpty()
                    .mapNotNull { (k, v) -> (v as? Number)?.let { k as String to it.toLong() } }.toMap(),
            )
        }
    }
}
