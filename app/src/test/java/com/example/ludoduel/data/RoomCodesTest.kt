package com.example.ludoduel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoomCodesTest {

    @Test fun `generated codes have 6 allowed characters`() {
        val random = Random(1)
        repeat(5000) {
            val code = RoomCodes.generate(random)
            assertEquals(6, code.length)
            assertTrue(code, RoomCodes.isComplete(code))
            assertFalse(code.any { it in "0O1IL" })
        }
    }

    @Test fun `generated codes use the whole alphabet`() {
        val random = Random(2)
        val seen = (1..2000).flatMap { RoomCodes.generate(random).toList() }.toSet()
        assertEquals(RoomCodes.ALPHABET.toSet(), seen)
    }

    @Test fun `default generator uses secure random`() {
        assertTrue(RoomCodes.isComplete(RoomCodes.generate()))
    }

    @Test fun `clean uppercases and trims spaces`() {
        assertEquals("ABC234", RoomCodes.clean("  abc 234 "))
        assertEquals("ABC234", RoomCodes.clean("a\tb\nc-2_3.4"))
    }

    @Test fun `clean drops confusable and invalid characters`() {
        assertEquals("ABC", RoomCodes.clean("A0B1CIOL"))
        assertEquals("", RoomCodes.clean("0O1IL!@#"))
    }

    @Test fun `clean keeps at most six characters`() {
        assertEquals("ABCDEF", RoomCodes.clean("abcdefgh"))
    }

    @Test fun `length is validated before contacting the server`() {
        assertFalse(RoomCodes.isComplete(""))
        assertFalse(RoomCodes.isComplete("ABC23"))
        assertFalse(RoomCodes.isComplete("ABC2345"))
        assertFalse(RoomCodes.isComplete("ABC23O"))
        assertTrue(RoomCodes.isComplete("ABC234"))
    }
}
