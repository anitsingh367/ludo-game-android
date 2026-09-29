package com.example.ludoduel.data

import com.example.ludoduel.engine.ActionType
import com.example.ludoduel.engine.GameState
import com.example.ludoduel.engine.LastAction
import com.example.ludoduel.engine.LudoEngine
import com.example.ludoduel.engine.Phase
import com.example.ludoduel.engine.PlayerColor
import com.example.ludoduel.engine.WinReason

/**
 * Converts [RoomGame] to and from the plain values the Realtime Database stores
 * (maps, lists, Long, String, Boolean). No Firebase types, so it can be unit-tested.
 * The engine works with colors; the database stores uids. [Seats] maps between them.
 */
object GameCodec {

    fun encode(game: RoomGame, seats: Seats): Map<String, Any> {
        val s = game.state
        return buildMap {
            put("version", game.version)
            put("gameNumber", game.gameNumber)
            put("turnUid", seats.uidOf(s.turn))
            put("phase", s.phase.name)
            s.dice?.let { put("dice", it.toLong()) }
            put("sixesInRow", s.sixesInRow.toLong())
            put("tokens", mapOf("red" to s.red.map(Int::toLong), "yellow" to s.yellow.map(Int::toLong)))
            put(
                "missedTurns",
                mapOf(seats.hostUid to s.missedRed.toLong(), seats.guestUid to s.missedYellow.toLong()),
            )
            put("turnDeadline", game.turnDeadline)
            s.lastAction?.let { put("lastAction", encodeAction(it, seats)) }
            s.winner?.let { put("winnerUid", seats.uidOf(it)) }
            s.winReason?.let { put("winReason", it.name.lowercase()) }
        }
    }

    private fun encodeAction(a: LastAction, seats: Seats): Map<String, Any> = buildMap {
        put("type", a.type.name.lowercase())
        put("byUid", seats.uidOf(a.by))
        a.dice?.let { put("dice", it.toLong()) }
        a.token?.let { put("token", it.toLong()) }
        a.from?.let { put("from", it.toLong()) }
        a.to?.let { put("to", it.toLong()) }
        a.captured?.let { put("captured", it.toLong()) }
        put("auto", a.auto)
    }

    /** Returns null when [raw] is missing or does not describe a valid game. */
    fun decode(raw: Any?, seats: Seats): RoomGame? {
        val m = raw as? Map<*, *> ?: return null
        val tokens = m["tokens"] as? Map<*, *> ?: return null
        val missed = m["missedTurns"] as? Map<*, *> ?: return null
        val state = GameState(
            red = intList(tokens["red"]) ?: return null,
            yellow = intList(tokens["yellow"]) ?: return null,
            turn = seats.colorOf(m["turnUid"] as? String ?: return null) ?: return null,
            phase = enumOrNull<Phase>(m["phase"] as? String) ?: return null,
            dice = int(m["dice"]),
            sixesInRow = int(m["sixesInRow"]) ?: return null,
            missedRed = int(missed[seats.hostUid]) ?: return null,
            missedYellow = int(missed[seats.guestUid]) ?: return null,
            winner = (m["winnerUid"] as? String)?.let { seats.colorOf(it) ?: return null },
            winReason = (m["winReason"] as? String)?.let { enumOrNull<WinReason>(it.uppercase()) ?: return null },
            lastAction = m["lastAction"]?.let { decodeAction(it, seats) ?: return null },
        )
        val game = RoomGame(
            version = long(m["version"]) ?: return null,
            gameNumber = long(m["gameNumber"]) ?: return null,
            turnDeadline = long(m["turnDeadline"]) ?: return null,
            state = state,
        )
        return if (game.version >= 1 && game.gameNumber >= 1 && LudoEngine.isValid(state)) game else null
    }

    private fun decodeAction(raw: Any, seats: Seats): LastAction? {
        val m = raw as? Map<*, *> ?: return null
        return LastAction(
            type = enumOrNull<ActionType>((m["type"] as? String)?.uppercase()) ?: return null,
            by = seats.colorOf(m["byUid"] as? String ?: return null) ?: return null,
            dice = int(m["dice"]),
            token = int(m["token"]),
            from = int(m["from"]),
            to = int(m["to"]),
            captured = int(m["captured"]),
            auto = m["auto"] as? Boolean ?: return null,
        )
    }

    private fun long(v: Any?): Long? = (v as? Number)?.toLong()

    private fun int(v: Any?): Int? = (v as? Number)?.toInt()

    private fun intList(v: Any?): List<Int>? {
        val list = v as? List<*> ?: return null
        return list.map { int(it) ?: return null }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
