package io.github.strawwaffle.glorp.chat

import kotlin.random.Random

/**
 * A line in chat. Immutable, so the UI can hold a snapshot of the log while
 * the next batch is applied.
 */
data class ChatLine(
    /**
     * destiny.gg messages carry no id, so one is made from who said what when.
     * That also tells the backlog sent on every reconnect apart from lines
     * already shown.
     */
    val id: String,
    val kind: Kind,
    /** The sender for [Kind.User] and [Kind.Whisper]; null otherwise. */
    val nick: String?,
    val features: List<String>,
    val text: String,
    val at: Long,
    val action: Boolean,
    val green: Boolean,
    /** The sender's subscription tier, which decides the emotes they may use. */
    val subTier: Int = 0,
    /** For [Kind.Combo]: how many messages it stands for. */
    val comboCount: Int = 0,
    /** For [Kind.Whisper]: the site's id for it, to mark it read. */
    val messageId: String? = null,
) {
    enum class Kind {
        /** A chat message. */
        User,

        /** A site-wide announcement. */
        Broadcast,

        /** Something the app or the server tells only you: errors, sign-in changes. */
        Info,

        /** A private message to you. */
        Whisper,

        /** Several people in a row sending the same single emote: [text] is the emote. */
        Combo,
    }

    companion object {
        fun user(nick: String, features: List<String>, raw: String, at: Long, subTier: Int = 0): ChatLine {
            val action = raw.startsWith("/me ")
            val text = if (action) raw.removePrefix("/me ") else raw
            return ChatLine(
                id = "$nick\u0000$at\u0000$raw",
                kind = Kind.User,
                nick = nick,
                features = features,
                text = text,
                at = at,
                action = action,
                green = text.startsWith(">"),
                subTier = subTier,
            )
        }

        fun broadcast(text: String, at: Long) = ChatLine(
            id = "broadcast\u0000$at\u0000$text",
            kind = Kind.Broadcast,
            nick = null,
            features = emptyList(),
            text = text,
            at = at,
            action = false,
            green = false,
        )

        fun whisper(nick: String, text: String, at: Long, messageId: String?) = ChatLine(
            id = "whisper\u0000${messageId ?: "$nick\u0000$at\u0000$text"}",
            kind = Kind.Whisper,
            nick = nick,
            features = emptyList(),
            text = text,
            at = at,
            action = false,
            green = false,
            // A whisper doesn't say the sender's tier; draw every emote
            // rather than none.
            subTier = Int.MAX_VALUE,
            messageId = messageId,
        )

        /** A combo that began with [first]; its id stays the first message's, so it keeps its place. */
        fun combo(emote: String, first: ChatLine, count: Int) = ChatLine(
            id = "combo:${first.id}",
            kind = Kind.Combo,
            nick = null,
            features = emptyList(),
            text = emote,
            at = first.at,
            action = false,
            green = false,
            comboCount = count,
        )

        /** Each notice is its own line, even when the same one repeats. */
        fun info(text: String, at: Long) = ChatLine(
            id = "info\u0000$at\u0000${Random.nextLong()}",
            kind = Kind.Info,
            nick = null,
            features = emptyList(),
            text = text,
            at = at,
            action = false,
            green = false,
        )
    }
}
