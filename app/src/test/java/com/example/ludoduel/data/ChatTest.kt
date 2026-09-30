package com.example.ludoduel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTest {

    private fun msg(seq: Long, type: ChatType = ChatType.TEXT, uid: String = "a") =
        ChatMessage("m$seq", uid, type, if (type == ChatType.EMOJI) null else "hi", if (type == ChatType.EMOJI) "1f602" else null, seq, seq * 1000)

    // Validation: length, empty, trimming.
    @Test fun `text is trimmed and inner spaces are collapsed`() {
        assertEquals("Good luck!", ChatRules.clean("   Good    luck!  "))
        assertEquals("two lines", ChatRules.clean("two\n\n lines"))
    }

    @Test fun `empty or blank text is not sent`() {
        assertNull(ChatRules.clean(""))
        assertNull(ChatRules.clean("    "))
        assertNull(ChatRules.clean("\n\t"))
    }

    @Test fun `text may be at most 100 characters`() {
        assertEquals(100, ChatRules.clean("x".repeat(100))?.length)
        assertNull(ChatRules.clean("x".repeat(101)))
        // Spaces removed by trimming do not count.
        assertEquals(100, ChatRules.clean("  " + "x".repeat(100) + "  ")?.length)
    }

    // Send-rate limits.
    @Test fun `at most one emoji every 2 seconds`() {
        val limit = SendLimiter(ChatRules.EMOJI_INTERVAL_MILLIS)
        assertTrue(limit.tryAcquire(10_000))
        assertFalse(limit.tryAcquire(10_500))
        assertFalse(limit.tryAcquire(11_999))
        assertTrue(limit.tryAcquire(12_000))
        assertFalse(limit.tryAcquire(12_001))
    }

    @Test fun `at most one message per second`() {
        val limit = SendLimiter(ChatRules.TEXT_INTERVAL_MILLIS)
        assertTrue(limit.tryAcquire(0))
        assertFalse(limit.tryAcquire(999))
        assertTrue(limit.tryAcquire(1_000))
    }

    @Test fun `a refused send does not push the next allowed time back`() {
        val limit = SendLimiter(ChatRules.EMOJI_INTERVAL_MILLIS)
        assertTrue(limit.tryAcquire(0))
        assertFalse(limit.tryAcquire(1_900))
        assertTrue(limit.tryAcquire(2_000))
    }

    // Old emojis are not replayed.
    @Test fun `messages that exist when the screen opens are history, not new`() {
        val feed = NewMessages()
        assertEquals(emptyList<ChatMessage>(), feed.of(listOf(msg(1), msg(2, ChatType.EMOJI))))
        val next = listOf(msg(1), msg(2, ChatType.EMOJI), msg(3, ChatType.EMOJI))
        assertEquals(listOf(msg(3, ChatType.EMOJI)), feed.of(next))
        assertEquals(emptyList<ChatMessage>(), feed.of(next)) // the same snapshot again
    }

    @Test fun `after reconnecting, messages sent meanwhile are history, not new`() {
        val feed = NewMessages()
        feed.of(listOf(msg(1)))
        feed.reset() // the connection came back
        assertEquals(emptyList<ChatMessage>(), feed.of(listOf(msg(1), msg(2, ChatType.EMOJI), msg(3, ChatType.EMOJI))))
        assertEquals(listOf(msg(4)), feed.of(listOf(msg(1), msg(2, ChatType.EMOJI), msg(3, ChatType.EMOJI), msg(4))))
    }

    @Test fun `a trimmed message is not counted as new`() {
        val feed = NewMessages()
        feed.of((1L..50L).map { msg(it) })
        assertEquals(listOf(msg(51)), feed.of((2L..51L).map { msg(it) }))
    }

    // Trimming to the last 50.
    @Test fun `sending message 51 deletes message 1, so 50 are kept`() {
        val chat = (1L..50L).map { msg(it) }
        assertEquals(listOf("m1"), ChatRules.toTrim(chat, 51))
        assertEquals(emptyList<String>(), ChatRules.toTrim(chat.take(49), 50))
        assertEquals(emptyList<String>(), ChatRules.toTrim(emptyList(), 1))
    }

    @Test fun `trimming catches up when more than 50 are left over`() {
        val chat = (1L..53L).map { msg(it) }
        assertEquals(listOf("m1", "m2", "m3", "m4"), ChatRules.toTrim(chat, 54))
    }

    // Decoding.
    @Test fun `only the shapes the rules allow are decoded`() {
        val base = mapOf("uid" to "a", "type" to "text", "text" to "hi", "seq" to 1L, "sentAt" to 5L)
        assertEquals(ChatMessage("k", "a", ChatType.TEXT, "hi", null, 1, 5), ChatMessage.decode("k", base))
        assertEquals(ChatType.EMOJI, ChatMessage.decode("k", base - "text" + ("type" to "emoji") + ("emojiId" to "1f44b"))?.type)
        assertNull(ChatMessage.decode("k", base - "text" + ("type" to "emoji") + ("emojiId" to "1f4a9")))
        assertNull(ChatMessage.decode("k", base + ("type" to "shout")))
        assertNull(ChatMessage.decode("k", base - "text"))
        assertNull(ChatMessage.decode("k", base - "seq"))
    }

    @Test fun `there are 16 different emojis`() {
        assertEquals(16, ChatRules.EMOJI_IDS.toSet().size)
    }
}
