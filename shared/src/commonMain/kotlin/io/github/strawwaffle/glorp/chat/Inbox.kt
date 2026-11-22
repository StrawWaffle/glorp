package io.github.strawwaffle.glorp.chat

/** One whisper in a conversation. */
data class Whisper(
    /** The site's message id when known; whispers sent from here have none. */
    val id: String?,
    val from: String,
    val text: String,
    val at: Long,
    val mine: Boolean,
)

/**
 * Whispers with one person. [messages] holds what this session heard and,
 * once opened with a site login, the site's history; [preview] is the last
 * message as the conversation list shows it, which can come from the site
 * before any message is loaded.
 */
data class Conversation(
    val nick: String,
    val messages: List<Whisper> = emptyList(),
    val unread: Int = 0,
    val lastAt: Long = 0,
    val preview: String = "",
    val previewMine: Boolean = false,
    /** The site's history has been loaded into [messages]. */
    val loaded: Boolean = false,
    /** Why the last whisper sent here was refused, in chat-gui's words. */
    val problem: String? = null,
) {
    val key: String get() = nick.lowercase()
}

/**
 * Every conversation, as chat-gui's whisper list keeps them: live whispers
 * in and out, folded together with what the site's messages API returns. It
 * lives only in memory; whispers are never written to the device.
 */
data class Inbox(
    val conversations: Map<String, Conversation> = emptyMap(),
    /** The conversation on screen, whose whispers don't count as unread. */
    val open: String? = null,
) {
    val unread: Int get() = conversations.values.sumOf { it.unread }

    /** Newest first, as the list shows them. */
    val sorted: List<Conversation> get() = conversations.values.sortedByDescending { it.lastAt }

    operator fun get(nick: String): Conversation? = conversations[nick.lowercase()]

    private fun with(nick: String, change: (Conversation) -> Conversation): Inbox {
        val key = nick.lowercase()
        val current = conversations[key] ?: Conversation(nick)
        return copy(conversations = conversations + (key to change(current)))
    }

    fun received(nick: String, text: String, at: Long, id: String?): Inbox = with(nick) {
        val whisper = Whisper(id, nick, text, at, mine = false)
        it.copy(
            nick = nick,
            messages = add(it.messages, whisper),
            unread = if (open == it.key) 0 else it.unread + 1,
            lastAt = maxOf(it.lastAt, at),
            preview = text,
            previewMine = false,
        )
    }

    fun sent(nick: String, me: String, text: String, at: Long): Inbox = with(nick) {
        it.copy(
            messages = add(it.messages, Whisper(null, me, text, at, mine = true)),
            lastAt = maxOf(it.lastAt, at),
            preview = text,
            previewMine = true,
            problem = null,
        )
    }

    fun refused(nick: String, why: String): Inbox = with(nick) { it.copy(problem = why) }

    /** The site's conversation list: adds what this session hadn't heard of, keeps what it had. */
    fun listed(entries: List<Conversation>): Inbox = entries.fold(this) { inbox, entry ->
        val known = inbox.conversations[entry.key]
        if (known == null) inbox.copy(conversations = inbox.conversations + (entry.key to entry))
        else if (entry.lastAt > known.lastAt) inbox.with(entry.nick) {
            it.copy(lastAt = entry.lastAt, preview = entry.preview, previewMine = entry.previewMine, unread = maxOf(it.unread, entry.unread))
        }
        else inbox
    }

    /** The site's history with [nick], merged with what arrived live. */
    fun history(nick: String, messages: List<Whisper>): Inbox = with(nick) {
        val all = messages.fold(it.messages) { list, whisper -> add(list, whisper) }
        it.copy(messages = all, loaded = true, lastAt = maxOf(it.lastAt, all.lastOrNull()?.at ?: 0))
    }

    fun opened(nick: String): Inbox = with(nick) { it.copy(unread = 0) }.copy(open = nick.lowercase())

    fun closed(): Inbox = copy(open = null)

    private companion object {
        const val CAP = 200

        /**
         * Adds [whisper] in time order unless it's already there: the same id,
         * or (for whispers sent from here, which have none) the same sender and
         * text within a minute.
         */
        fun add(list: List<Whisper>, whisper: Whisper): List<Whisper> {
            val duplicate = list.any { known ->
                if (known.id != null && whisper.id != null) known.id == whisper.id
                else known.from.equals(whisper.from, true) && known.text == whisper.text &&
                    kotlin.math.abs(known.at - whisper.at) < 60_000
            }
            if (duplicate) return list
            return (list + whisper).sortedBy { it.at }.takeLast(CAP)
        }
    }
}
