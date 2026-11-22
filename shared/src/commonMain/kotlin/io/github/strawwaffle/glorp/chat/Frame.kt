package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.SerialName
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
