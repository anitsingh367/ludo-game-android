package com.example.ludoduel.data

import com.example.ludoduel.engine.Action
import com.example.ludoduel.engine.PlayerColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCodecTest {
    private val seats = Seats("h", "g")

    private fun played(): RoomGame {
        var g = GameReducer.initial(100)
        g = GameReducer.reduce(g, g.version, Action.Roll(6), "h", seats, 200).getOrThrow()
        g = GameReducer.reduce(g, g.version, Action.Move(1), "h", seats, 300).getOrThrow()
        return g
    }

    @Test fun `encode then decode returns the same game`() {
        val g = played()
        assertEquals(g, GameCodec.decode(GameCodec.encode(g, seats), seats))
        val over = GameReducer.reduce(g, g.version, Action.Forfeit, "g", seats, 400).getOrThrow()
        assertEquals(over, GameCodec.decode(GameCodec.encode(over, seats), seats))
    }

    @Test fun `encoded shape uses uids and lowercase names`() {
        val m = GameCodec.encode(played(), seats)
        assertEquals("h", m["turnUid"])
        assertEquals("ROLL", m["phase"])
        assertEquals(mapOf("h" to 0L, "g" to 0L), m["missedTurns"])
        assertEquals(mapOf("h" to 0L, "g" to 0L), m["noSixStreak"])
        val last = m["lastAction"] as Map<*, *>
        assertEquals("move", last["type"])
        assertEquals("h", last["byUid"])
        assertFalse(m.containsKey("dice"))
        assertFalse(m.containsKey("winnerUid"))
    }

    @Test fun `decode rejects wrong token count or out of range positions`() {
        val m = GameCodec.encode(played(), seats).toMutableMap()
        m["tokens"] = mapOf("red" to listOf(-1L, -1L, -1L), "yellow" to listOf(-1L, -1L, -1L, -1L))
        assertNull(GameCodec.decode(m, seats))
        m["tokens"] = mapOf("red" to listOf(-1L, -1L, -1L, 57L), "yellow" to listOf(-1L, -1L, -1L, -1L))
        assertNull(GameCodec.decode(m, seats))
        m["tokens"] = mapOf("red" to listOf(-2L, -1L, -1L, 0L), "yellow" to listOf(-1L, -1L, -1L, -1L))
        assertNull(GameCodec.decode(m, seats))
    }

    @Test fun `decode rejects unknown uids and missing fields`() {
        val m = GameCodec.encode(played(), seats).toMutableMap()
        assertNull(GameCodec.decode(m.toMutableMap().also { it["turnUid"] = "x" }, seats))
        assertNull(GameCodec.decode(m.toMutableMap().also { it.remove("version") }, seats))
        assertNull(GameCodec.decode(m.toMutableMap().also { it.remove("noSixStreak") }, seats))
        assertNull(GameCodec.decode(m.toMutableMap().also { it["phase"] = "DANCE" }, seats))
        assertNull(GameCodec.decode(m.toMutableMap().also { it["dice"] = 9L; it["phase"] = "MOVE" }, seats))
        assertNull(GameCodec.decode(null, seats))
        assertNull(GameCodec.decode("garbage", seats))
    }

    @Test fun `room decode flags a corrupt game`() {
        val raw = mapOf(
            "schemaVersion" to 2L, "expiresAt" to 5L, "hostUid" to "h", "guestUid" to "g",
            "status" to "playing", "luckyBoost" to true,
            "players" to mapOf(
                "h" to mapOf("color" to "red", "name" to "Ann", "connected" to true, "lastSeen" to 1L),
                "g" to mapOf("color" to "yellow", "name" to "Bo", "connected" to false, "lastSeen" to 2L),
            ),
            "game" to mapOf("version" to 3L),
            "rematch" to mapOf("h" to 1L),
        )
        val room = Room.decode("ABC234", raw)
        assertNotNull(room)
        assertTrue(room!!.gameCorrupt)
        assertNull(room.game)
        assertEquals(RoomStatus.PLAYING, room.status)
        assertEquals(PlayerColor.YELLOW, room.players["g"]?.color)
        assertEquals(mapOf("h" to 1L), room.rematch)

        val ok = Room.decode("ABC234", raw + ("game" to GameCodec.encode(played(), seats)))!!
        assertFalse(ok.gameCorrupt)
        assertNotNull(ok.game)
    }

    @Test fun `waiting room without guest decodes`() {
        val raw = mapOf(
            "schemaVersion" to 2L, "expiresAt" to 5L, "hostUid" to "h", "status" to "waiting", "luckyBoost" to false,
            "players" to mapOf("h" to mapOf("color" to "red", "name" to "Ann", "connected" to true, "lastSeen" to 1L)),
        )
        val room = Room.decode("ABC234", raw)!!
        assertNull(room.guestUid)
        assertNull(room.seats)
        assertFalse(room.gameCorrupt)
        assertNull(Room.decode("ABC234", null))
        assertNull(Room.decode("ABC234", raw - "hostUid"))
    }

    @Test fun `a new room needs the Lucky Boost setting, an older room still decodes`() {
        val raw = mapOf(
            "schemaVersion" to 2L, "expiresAt" to 5L, "hostUid" to "h", "status" to "waiting", "luckyBoost" to true,
            "players" to mapOf("h" to mapOf("color" to "red", "name" to "Ann", "connected" to true, "lastSeen" to 1L)),
        )
        assertTrue(Room.decode("ABC234", raw)!!.luckyBoost)
        assertNull(Room.decode("ABC234", raw - "luckyBoost"))
        // A room from the previous app version decodes (so the app can say "Please update").
        val old = Room.decode("ABC234", raw - "luckyBoost" + ("schemaVersion" to 1L))!!
        assertEquals(1L, old.schemaVersion)
    }
}
