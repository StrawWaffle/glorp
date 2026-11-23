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
    /** A moderator muted or banned the sender after this was sent; `showremoved` says how it shows. */
    val moderated: Boolean = false,
    /** What the sender was watching on bigscreen when they sent it, as `platform/id` in lower case. */
    val watching: String? = null,
    /** For [Kind.Event]: what happened (a subscription, a donation…). */
    val event: EventLine? = null,
    /** A moderator spotlighted this message (chat-gui's framed messages); who did. */
    val spotlightedBy: String? = null,
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

        /** A paid or site event chat-gui shows as a card: a subscription, a donation, a death… [event] says which. */
        Event,
    }

    companion object {
        fun user(nick: String, features: List<String>, raw: String, at: Long, subTier: Int = 0, watching: String? = null): ChatLine {
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
                watching = watching,
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

        /** A moderation notice from the server ("x muted by y."), the same line again when the backlog replays it. */
        fun notice(text: String, at: Long) = ChatLine(
            id = "notice\u0000$at\u0000$text",
            kind = Kind.Info,
            nick = null,
            features = emptyList(),
            text = text,
            at = at,
            action = false,
            green = false,
        )

        /** An event card: [nick] did [EventLine.header], with [text] as their message, if any. */
        fun event(event: EventLine, nick: String?, features: List<String>, text: String, at: Long, uuid: String?) = ChatLine(
            id = "event\u0000${uuid ?: "$at\u0000${event.header}"}",
            kind = Kind.Event,
            nick = nick,
            features = features,
            text = text,
            at = at,
            action = false,
            green = false,
            event = event,
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

/**
 * One of chat-gui's event cards, as a line: who did what ([header], after
 * the name if there is one) and a link to follow, if any.
 */
data class EventLine(val kind: Kind, val header: String, val link: String? = null) {
    enum class Kind { Subscription, Gift, MassGift, Donation, Death, XPost, Vestaboard }
}
