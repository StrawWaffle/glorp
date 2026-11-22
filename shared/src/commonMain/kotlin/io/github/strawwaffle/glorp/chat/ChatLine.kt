package io.github.strawwaffle.glorp.chat

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
    val nick: String?,
    val features: List<String>,
    val text: String,
    val at: Long,
    val action: Boolean,
    val green: Boolean,
    /** The sender's subscription tier, which decides the emotes they may use. */
    val subTier: Int = 0,
) {
    val isBroadcast: Boolean get() = nick == null

    companion object {
        fun user(nick: String, features: List<String>, raw: String, at: Long, subTier: Int = 0): ChatLine {
            val action = raw.startsWith("/me ")
            val text = if (action) raw.removePrefix("/me ") else raw
            return ChatLine(
                id = "$nick\u0000$at\u0000$raw",
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
            nick = null,
            features = emptyList(),
            text = text,
            at = at,
            action = false,
            green = false,
        )
    }
}
