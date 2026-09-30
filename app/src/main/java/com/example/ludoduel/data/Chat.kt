package com.example.ludoduel.data

enum class ChatType(val key: String) { TEXT("text"), EMOJI("emoji"), PHRASE("phrase") }

/** One entry of `rooms/{code}/chat`: a text, a quick phrase or an emoji reaction. */
data class ChatMessage(
    val id: String,
    val uid: String,
    val type: ChatType,
    /** For [ChatType.TEXT] and [ChatType.PHRASE]. */
    val text: String?,
    /** For [ChatType.EMOJI]: one of [ChatRules.EMOJI_IDS]. */
    val emojiId: String?,
    /** Sequence number: 1, 2, 3, … in sending order (see `chatSeq` in the security rules). */
    val seq: Long,
    /** Server time of the write. */
    val sentAt: Long,
) {
    companion object {
        /** Null when the entry does not have the shape the security rules allow. */
        fun decode(id: String, raw: Any?): ChatMessage? {
            val m = raw as? Map<*, *> ?: return null
            val type = ChatType.entries.firstOrNull { it.key == m["type"] } ?: return null
            val text = m["text"] as? String
            val emojiId = m["emojiId"] as? String
            if (type == ChatType.EMOJI && emojiId !in ChatRules.EMOJI_IDS) return null
            if (type != ChatType.EMOJI && text == null) return null
            return ChatMessage(
                id = id,
                uid = m["uid"] as? String ?: return null,
                type = type,
                text = text,
                emojiId = emojiId,
                seq = (m["seq"] as? Number)?.toLong() ?: return null,
                sentAt = (m["sentAt"] as? Number)?.toLong() ?: return null,
            )
        }
    }
}

/** The chat limits, shared by the app and the security rules. */
object ChatRules {
    const val MAX_LENGTH = 100
    /** Only the last 50 messages are kept in a room. */
    const val KEEP = 50
    const val TEXT_INTERVAL_MILLIS = 1_000L
    const val EMOJI_INTERVAL_MILLIS = 2_000L

    /** The 16 bundled animated emojis (Unicode code points; files `res/raw/emoji_<id>.json`). */
    val EMOJI_IDS = listOf(
        "1f602", "1f60d", "1f60e", "1f914",
        "1f62e", "1f622", "1f621", "1f608",
        "1f44e", "1f44f", "1f64f", "1f525",
        "1f389", "1f480", "1f91e", "1f44b",
    )

    /**
     * The text to send: spaces at both ends removed and runs of spaces inside turned into one.
     * Null when nothing is left or it is longer than [MAX_LENGTH].
     */
    fun clean(raw: String): String? {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }
    }

    /** Ids of the messages to delete when a message with sequence number [newSeq] is added. */
    fun toTrim(messages: List<ChatMessage>, newSeq: Long): List<String> =
        messages.filter { it.seq <= newSeq - KEEP }.map { it.id }
}

/** At most one send per [intervalMillis]. */
class SendLimiter(private val intervalMillis: Long) {
    private var last: Long? = null

    /** True (and remembers the time) when a send is allowed at [nowMillis]. */
    fun tryAcquire(nowMillis: Long): Boolean {
        val previous = last
        if (previous != null && nowMillis - previous < intervalMillis) return false
        last = nowMillis
        return true
    }
}

/**
 * Tells which messages are new since the last snapshot. The first snapshot after opening the
 * screen, and the first one after reconnecting ([reset]), is only the history: nothing in it is
 * new, so old emoji flights are never replayed.
 */
class NewMessages {
    private var known: Set<String>? = null

    fun reset() {
        known = null
    }

    fun of(messages: List<ChatMessage>): List<ChatMessage> {
        val before = known
        known = messages.mapTo(HashSet()) { it.id }
        return if (before == null) emptyList() else messages.filter { it.id !in before }
    }
}
