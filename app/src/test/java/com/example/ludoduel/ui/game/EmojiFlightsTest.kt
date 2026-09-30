package com.example.ludoduel.ui.game

import com.example.ludoduel.data.ChatRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiFlightsTest {

    private fun emoji(n: Int) = ChatEvent.Emoji(id = "m$n", emojiId = "1f602", fromMe = n % 2 == 0)

    @Test fun `at most 3 fly at once, each in its own slot, the rest wait in order`() {
        val flights = EmojiFlights()
        (1..5).forEach { flights.add(emoji(it)) }
        assertEquals(listOf(0 to emoji(1), 1 to emoji(2), 2 to emoji(3)), flights.flying.toList())
        // The second one lands and leaves: the next waiting one takes its free slot.
        flights.done(emoji(2))
        assertEquals(listOf(0 to emoji(1), 2 to emoji(3), 1 to emoji(4)), flights.flying.toList())
        flights.done(emoji(1))
        flights.done(emoji(3))
        assertEquals(listOf(1 to emoji(4), 0 to emoji(5)), flights.flying.toList())
        flights.done(emoji(4))
        flights.done(emoji(5))
        assertEquals(emptyList<Pair<Int, ChatEvent.Emoji>>(), flights.flying.toList())
    }

    @Test fun `every emoji has a preview frame and an animation file`() {
        for (id in ChatRules.EMOJI_IDS) {
            val p = EmojiArt.previewProgress(id)
            assertTrue("$id preview $p", p in 0f..1f)
            EmojiArt.rawRes(id)
        }
    }
}
