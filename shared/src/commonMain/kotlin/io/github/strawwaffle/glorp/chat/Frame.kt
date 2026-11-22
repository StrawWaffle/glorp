package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.Account
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of the chat server's `EVENT {json}` format, read only as far as the
 * app needs it.
 *
 * The roster (`NAMES`) runs to a few hundred kilobytes at busy times and the
 * app doesn't use it yet, so events are matched on their name before any JSON
 * is decoded, and unknown ones cost nothing but the string.
 */
sealed interface ChatEvent {
    data class Message(val line: ChatLine) : ChatEvent
    data class History(val lines: List<ChatLine>) : ChatEvent
    data class Removal(val nick: String) : ChatEvent

    /** Who the server thinks you are, sent first on every connect; null when signed out. */
    data class Me(val account: Account?) : ChatEvent

    /** The server refused something, by chat-gui's error code (`throttled`, `muted`…). */
    data class Error(val code: String) : ChatEvent

    data class Notice(val text: String) : ChatEvent
}

internal val chatJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
internal data class WireUser(
    val nick: String = "",
    val features: List<String> = emptyList(),
    val timestamp: Long? = null,
    val data: String? = null,
    val subscription: WireSubscription? = null,
)

@Serializable
internal data class WireSubscription(val tier: Int = 0)

@Serializable
internal data class WireText(
    val data: String = "",
    val timestamp: Long? = null,
)

@Serializable
internal data class WireSubOnly(val data: String = "", val nick: String? = null, val tier: Int? = null)

/** chat-gui's wording: "Subscriber only mode (Tier 2+) enabled by nick." */
internal fun subOnlyText(event: WireSubOnly): String {
    val on = event.data == "on"
    val tier = if (on && (event.tier ?: 1) > 1) " (Tier ${event.tier}+)" else ""
    val by = event.nick?.takeIf { it.isNotEmpty() }?.let { " by $it" } ?: ""
    return "Subscriber only mode$tier ${if (on) "enabled" else "disabled"}$by."
}

@Serializable
internal data class WireError(val description: String = "")

@Serializable
internal data class WirePrivate(
    val nick: String = "",
    val data: String = "",
    val timestamp: Long? = null,
    val messageid: Long? = null,
)

/** Parses one text frame. Returns null for events the app ignores or can't read. */
fun parseFrame(raw: String, now: () -> Long): ChatEvent? {
    val space = raw.indexOf(' ')
    if (space <= 0) return null
    val event = raw.substring(0, space).uppercase()
    val payload = raw.substring(space + 1)
    return try {
        when (event) {
            "MSG" -> parseMessage(payload, now)?.let(ChatEvent::Message)
            "BROADCAST" -> {
                val text = chatJson.decodeFromString<WireText>(payload)
                if (text.data.isBlank()) null
                else ChatEvent.Message(ChatLine.broadcast(text.data, text.timestamp ?: now()))
            }
            "HISTORY" -> {
                val lines = chatJson.decodeFromString<List<String>>(payload)
                ChatEvent.History(lines.mapNotNull { (parseFrame(it, now) as? ChatEvent.Message)?.line })
            }
            "MUTE", "BAN" -> {
                val target = chatJson.decodeFromString<WireText>(payload).data
                if (target.isEmpty()) null else ChatEvent.Removal(target)
            }
            "ME" -> ChatEvent.Me(
                chatJson.decodeFromString<WireUser?>(payload)?.takeIf { it.nick.isNotEmpty() }
                    ?.let { Account(it.nick, it.features, it.subscription?.tier ?: 0) },
            )
            "ERR" -> ChatEvent.Error(chatJson.decodeFromString<WireError>(payload).description)
            "PRIVMSG" -> {
                val whisper = chatJson.decodeFromString<WirePrivate>(payload)
                if (whisper.nick.isEmpty()) null
                else ChatEvent.Message(
                    ChatLine.whisper(whisper.nick, whisper.data, whisper.timestamp ?: now(), whisper.messageid?.toString()),
                )
            }
            "SUBONLY" -> ChatEvent.Notice(subOnlyText(chatJson.decodeFromString<WireSubOnly>(payload)))
            else -> null
        }
    } catch (_: IllegalArgumentException) {
        // A malformed payload is skipped rather than fatal: the server is
        // outside our control and one odd frame shouldn't drop the connection.
        null
    }
}

private fun parseMessage(payload: String, now: () -> Long): ChatLine? {
    val user = chatJson.decodeFromString<WireUser>(payload)
    val text = user.data ?: return null
    if (user.nick.isEmpty()) return null
    return ChatLine.user(user.nick, user.features, text, user.timestamp ?: now(), user.subscription?.tier ?: 0)
}

/** chat-gui's wording for the server's error codes. */
fun errorText(code: String): String = when (code) {
    "throttled" -> "Throttled! You were trying to send messages too fast."
    "duplicate" -> "The message is identical to the last one you sent."
    "muted" -> "You are muted."
    "banned" -> "You have been banned. Your profile on the site says why and for how long."
    "submode" -> "Chat is in subscriber only mode."
    "needlogin" -> "You have to be signed in to do that."
    "nopermission" -> "You don't have the permissions to do that."
    "msgtoolong" -> "Messages can't be longer than 512 characters."
    "invalidmsg" -> "The message was invalid."
    "notfound" -> "That user was not found."
    "privmsgbanned" -> "You can't send whispers while banned."
    "privmsgaccounttooyoung" -> "Your account is too new to send whispers."
    "nochatting" -> "You aren't allowed to chat: either you haven't picked a username, or a moderator turned chatting off for you."
    "toomanyconnections" -> "Too many connections from your account. Close chat somewhere else and come back."
    else -> "The server said: $code"
}

/** After these, chat-gui stops reconnecting, and so does this app. */
fun isFatal(code: String) = code == "banned" || code == "toomanyconnections"
