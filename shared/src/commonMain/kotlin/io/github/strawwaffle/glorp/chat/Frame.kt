package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.Account
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * One line of the chat server's `EVENT {json}` format, read only as far as the
 * app needs it.
 *
 * Events are matched on their name before any JSON is decoded, and unknown
 * ones cost nothing but the string. The roster (`NAMES`, a few hundred
 * kilobytes at busy times) is passed on undecoded: only someone signed in,
 * who can type, needs its names, and then only those.
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

    /** The roster sent on connect, still as the server's JSON (see [rosterNicks]). */
    class Names(val payload: String) : ChatEvent

    /** A poll started, or the one running, sent on connect. */
    data class PollStart(val poll: Poll) : ChatEvent

    /** The poll ended; a hidden poll's results come with it. */
    data class PollStop(val totals: List<Long>?, val votes: Long?) : ChatEvent

    /** Someone voted: [quantity] more for [option] (from 1; 0 in a hidden poll). */
    data class VoteCast(val option: Int, val quantity: Long) : ChatEvent

    /** This account's vote went in. */
    data class VoteCounted(val option: Int) : ChatEvent

    /** The pinned message, or none when [line] is null (unpinned). */
    data class Pin(val uuid: String, val line: ChatLine?) : ChatEvent
}

internal val chatJson = Json { ignoreUnknownKeys = true; isLenient = true }

@Serializable
internal data class WireUser(
    val nick: String = "",
    val features: List<String> = emptyList(),
    val timestamp: Long? = null,
    val data: String? = null,
    val subscription: WireSubscription? = null,
    val roles: List<String> = emptyList(),
    /** Only on `PIN`. */
    val uuid: String? = null,
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

@Serializable
internal data class WirePollStart(
    val canvote: Boolean = false,
    val myvote: Int = 0,
    val weighted: Boolean = false,
    val hidden: Boolean = false,
    val start: JsonElement? = null,
    val now: JsonElement? = null,
    val time: Long = 30_000,
    val question: String = "",
    val options: List<String> = emptyList(),
    val totals: List<Long> = emptyList(),
    val nick: String = "",
    val totalvotes: Long = 0,
)

@Serializable
internal data class WirePollStop(val totals: List<Long>? = null, val totalvotes: Long? = null)

@Serializable
internal data class WireVote(val vote: JsonElement? = null, val quantity: Long = 1)

/** A time the server sends either as milliseconds or as an ISO date. */
private fun millis(value: JsonElement?): Long? {
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.longOrNull
    return primitive.content.toLongOrNull()
        ?: runCatching { Instant.parse(primitive.content).toEpochMilliseconds() }.getOrNull()
}

private fun option(value: JsonElement?): Int = (value as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: 0

internal fun parsePollStart(payload: String, localNow: Long): Poll? {
    val wire = chatJson.decodeFromString<WirePollStart>(payload)
    if (wire.options.isEmpty()) return null
    // Timed on the server's clock: shift it onto this device's, as chat-gui does.
    val serverNow = millis(wire.now)
    val start = millis(wire.start)
    val startedAt = if (start != null && serverNow != null) start - (serverNow - localNow) else start ?: localNow
    val totals = wire.totals.takeIf { it.size == wire.options.size } ?: List(wire.options.size) { 0L }
    return Poll(
        question = wire.question,
        options = wire.options,
        totals = totals,
        votes = wire.totalvotes,
        by = wire.nick,
        weighted = wire.weighted,
        hidden = wire.hidden,
        startedAt = startedAt,
        durationMs = wire.time,
        myVote = wire.myvote,
        canVote = wire.canvote && wire.myvote == 0,
    )
}

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
                    ?.let { Account(it.nick, it.features, it.subscription?.tier ?: 0, it.roles) },
            )
            "ERR" -> ChatEvent.Error(chatJson.decodeFromString<WireError>(payload).description)
            "PRIVMSG" -> {
                val whisper = chatJson.decodeFromString<WirePrivate>(payload)
                if (whisper.nick.isEmpty()) null
                else ChatEvent.Message(
                    ChatLine.whisper(whisper.nick, whisper.data, whisper.timestamp ?: now(), whisper.messageid?.toString()),
                )
            }
            "NAMES" -> ChatEvent.Names(payload)
            "SUBONLY" -> ChatEvent.Notice(subOnlyText(chatJson.decodeFromString<WireSubOnly>(payload)))
            "POLLSTART" -> parsePollStart(payload, now())?.let(ChatEvent::PollStart)
            "POLLSTOP" -> chatJson.decodeFromString<WirePollStop>(payload).let { ChatEvent.PollStop(it.totals, it.totalvotes) }
            "VOTECAST" -> chatJson.decodeFromString<WireVote>(payload).let { ChatEvent.VoteCast(option(it.vote), it.quantity) }
            "VOTECOUNTED" -> ChatEvent.VoteCounted(option(chatJson.decodeFromString<WireVote>(payload).vote))
            "PIN" -> {
                val pin = chatJson.decodeFromString<WireUser>(payload)
                val text = pin.data.orEmpty()
                val line = if (text.isBlank() || pin.nick.isEmpty()) null
                else ChatLine.user(pin.nick, pin.features, text, pin.timestamp ?: now(), pin.subscription?.tier ?: 0)
                ChatEvent.Pin(pin.uuid ?: line?.id.orEmpty(), line)
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
