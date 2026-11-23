package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatPrefs
import io.github.strawwaffle.glorp.chat.ChatSettings
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.community.LogMessage
import io.github.strawwaffle.glorp.community.siteLink
import io.github.strawwaffle.glorp.site.Messages
import io.github.strawwaffle.glorp.site.Site
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * What the platform does for notifications: asking for permission, keeping
 * the background check in step with the options, and its own settings for
 * each kind's sound. Null where there are no notifications yet (iOS).
 */
interface NotifyControls {
    val permitted: Boolean
    fun ask(onResult: (Boolean) -> Unit)
    /** The options changed: schedule, move or cancel the check. */
    fun changed()
    fun openSystemSettings()
}

/** Where a tapped notification asked the app to go (an [Alert.open]), until the app takes it. */
object OpenRequests {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}

/** Something worth a notification, and where tapping it goes. */
data class Alert(val kind: Kind, val title: String, val text: String, val open: String) {
    enum class Kind { Live, Mention, Whisper }
}

/**
 * What notifications are wanted and how often to look. Mentions and whispers
 * are chat-gui's own settings (`notificationhighlight`, `notificationwhisper`)
 * and follow the account; the live one and how often are this phone's.
 */
class NotifyOptions(private val prefs: ChatPrefs, private val settings: ChatSettings) {
    val live: Boolean get() = prefs.get(LIVE) == "true"
    val mentions: Boolean get() = (settings["notificationhighlight"] as? JsonPrimitive)?.booleanOrNull == true
    val whispers: Boolean get() = (settings["notificationwhisper"] as? JsonPrimitive)?.booleanOrNull == true
    val any: Boolean get() = live || mentions || whispers

    /** Minutes between looks; Android allows no less than 15. */
    val intervalMinutes: Int get() = prefs.get(INTERVAL)?.toIntOrNull()?.takeIf { it in INTERVALS } ?: 30

    fun setLive(on: Boolean) = prefs.put(LIVE, on.toString())
    fun setInterval(minutes: Int) = prefs.put(INTERVAL, minutes.toString())

    companion object {
        val INTERVALS = listOf(15, 30, 60)

        /** Left by the running app for the background check: the site's address and the signed-in name. */
        const val ORIGIN = "notify.origin"
        const val NICK = "notify.nick"
        private const val LIVE = "notify.live"
        private const val INTERVAL = "notify.interval"
    }
}

/**
 * One look at the site, made in the background with the app closed: whether
 * Destiny went live, and with the site's cookies, new mentions and whispers.
 * What was already seen is remembered in [prefs], and the very first look
 * only takes note, so turning notifications on doesn't bring up old ones.
 */
class AlertCheck(
    private val client: HttpClient,
    private val site: Site,
    private val prefs: ChatPrefs,
    private val options: NotifyOptions,
    private val cookies: Session.Cookies?,
    /** The signed-in name, for mentions. */
    private val nick: String?,
) {
    suspend fun run(): List<Alert> = buildList {
        if (options.live) live()?.let(::add)
        if (cookies != null && options.whispers) addAll(whispers(cookies))
        if (cookies != null && nick != null && options.mentions) addAll(mentions(nick))
    }

    private suspend fun live(): Alert? {
        val body = get("${site.origin}/api/info/stream") ?: return null
        val streams = runCatching { chatJson.parseToJsonElement(body).jsonObject["data"]!!.jsonObject["streams"]!!.jsonObject }.getOrNull()
            ?: return null
        val live = streams.values.mapNotNull { it as? JsonObject }
            .firstOrNull { (it["live"] as? JsonPrimitive)?.booleanOrNull == true }
        // Remembered: the stream that was live, so each stream notifies once.
        val now = live?.let { (it["started_at"] as? JsonPrimitive)?.contentOrNull ?: "live" }.orEmpty()
        val before = prefs.get(LIVE_SEEN)
        prefs.put(LIVE_SEEN, now)
        if (live == null || before == null || before == now) return null
        val title = (live["status_text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val platform = (live["platform"] as? JsonPrimitive)?.contentOrNull ?: ""
        return Alert(Alert.Kind.Live, "Destiny is live" + if (platform.isNotEmpty()) " on ${platformName(platform)}" else "", title ?: "Tap to watch", OPEN_LIVE)
    }

    private suspend fun whispers(cookies: Session.Cookies): List<Alert> {
        val list = Messages(client, site, cookies).conversations() ?: return emptyList()
        val newest = list.maxOfOrNull { it.lastAt } ?: 0
        val seen = prefs.get(WHISPERS_SEEN)?.toLongOrNull()
        prefs.put(WHISPERS_SEEN, maxOf(newest, seen ?: 0).toString())
        if (seen == null) return emptyList()
        return list.filter { it.unread > 0 && !it.previewMine && it.lastAt > seen }.take(MAX_EACH).map {
            Alert(Alert.Kind.Whisper, "Whisper from ${it.nick}", it.preview, "$OPEN_WHISPER${it.nick}")
        }
    }

    private suspend fun mentions(nick: String): List<Alert> {
        val body = get("${site.origin}/api/chat/mentions") {
            parameter("username", nick)
            parameter("limit", MENTIONS_LIMIT)
        } ?: return emptyList()
        val found = parseMentions(body) ?: return emptyList()
        val seen = prefs.get(MENTIONS_SEEN)?.toLongOrNull()
        prefs.put(MENTIONS_SEEN, maxOf(found.maxOfOrNull { it.atSeconds } ?: 0, seen ?: 0).toString())
        if (seen == null) return emptyList()
        return found.filter { it.atSeconds > seen && !it.nick.equals(nick, ignoreCase = true) }
            .sortedBy { it.atSeconds }.takeLast(MAX_EACH).map { m ->
                // Tapping it opens the chat around it in the log search: by then
                // it may have scrolled out of what chat keeps.
                val iso = Instant.fromEpochMilliseconds(m.atSeconds * 1000).toString()
                val link = LogMessage("Destinygg", m.text, iso, m.nick, m.atSeconds * 1000).siteLink()
                Alert(Alert.Kind.Mention, "${m.nick} mentioned you", m.text, link)
            }
    }

    private suspend fun get(url: String, more: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): String? = try {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            cookies?.let { header(HttpHeaders.Cookie, it.cookieHeader) }
            more()
        }
        if (response.status.isSuccess()) response.bodyAsText() else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** What tapping a live notification opens: the app, on what bigscreen shows. */
        const val OPEN_LIVE = "glorp:live"

        /** Prefix of a whisper notification's target; the nick follows. */
        const val OPEN_WHISPER = "glorp:whisper:"

        private const val LIVE_SEEN = "notify.live.seen"
        private const val WHISPERS_SEEN = "notify.whispers.seen"
        private const val MENTIONS_SEEN = "notify.mentions.seen"
        private const val MENTIONS_LIMIT = 10
        private const val MAX_EACH = 5
    }
}

/** One mention as the site's `/api/chat/mentions` gives it: `{date (seconds), nick, text}`. */
internal data class Mention(val atSeconds: Long, val nick: String, val text: String)

internal fun parseMentions(body: String): List<Mention>? = runCatching {
    val root = chatJson.parseToJsonElement(body).jsonObject
    if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
    root["data"]!!.jsonArray.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        val at = (o["date"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
        val nick = (o["nick"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        Mention(at, nick, (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
    }
}.getOrNull()

private fun platformName(platform: String) = when (platform.lowercase()) {
    "youtube" -> "YouTube"
    "twitch" -> "Twitch"
    "kick" -> "Kick"
    "rumble" -> "Rumble"
    "facebook" -> "Facebook"
    else -> platform
}
