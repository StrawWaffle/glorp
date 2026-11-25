package io.github.strawwaffle.glorp.community

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.diag.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

/** A message naming someone, as polecat.me logged it. */
data class PolecatLine(val id: Long, val at: Long, val nick: String, val flairs: List<String>, val text: String) {
    fun logLine() = LogLine(at, nick, text)
}

/**
 * polecat.me's chat logger: every destiny.gg message naming someone, back to
 * December 2025, with no sign-in. It allows 60 requests a minute and says so
 * in `RateLimit-*` headers; when it's used up (or answers 429) nothing more
 * is asked of it until it says it has refilled, and callers fall back on
 * Glorp's own mentions service.
 */
class Polecat(private val client: HttpClient) {
    /** A page of mentions of [nick], newest first; null when polecat can't be asked or didn't answer. */
    suspend fun mentions(nick: String, size: Int = PAGE, offset: Int = 0): List<PolecatLine>? {
        if (Clock.System.now().toEpochMilliseconds() < resting) return null
        return try {
            val response = client.get("$BASE/api/mentions/${nick.encodeURLPathPart()}") {
                header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
                parameter("size", size)
                parameter("offset", offset)
            }
            heed(response)
            if (!response.status.isSuccess()) null
            else parsePolecat(response.bodyAsText())?.sortedByDescending { it.at }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.note("polecat", "request failed", e)
            null
        }
    }

    /** Stops asking until polecat's limit refills, when it says it's used up. */
    private fun heed(response: HttpResponse) {
        val remaining = response.headers["RateLimit-Remaining"]?.toIntOrNull()
        val limited = response.status == HttpStatusCode.TooManyRequests || remaining == 0
        if (!limited) return
        val seconds = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()
            ?: response.headers["RateLimit-Reset"]?.toLongOrNull()
            ?: 60L
        resting = Clock.System.now().toEpochMilliseconds() + seconds.coerceIn(1, 3600) * 1000
    }

    companion object {
        const val BASE = "https://polecat.me"
        const val PAGE = 50

        /** Until when not to ask (epoch ms): shared by everything in the app that asks. */
        @kotlin.concurrent.Volatile
        private var resting = 0L
    }
}

/** A polecat.me mentions page: whose (the first of `usernames`, or `username`) and the message it points at, if any. */
data class PolecatLink(val nick: String, val message: Long?)

/** `https://polecat.me/mentions?usernames=a,b&message=123`, or null for any other address. */
fun polecatLinkOf(link: String): PolecatLink? {
    val url = runCatching { io.ktor.http.Url(link.trim()) }.getOrNull() ?: return null
    if (url.host.lowercase().removePrefix("www.") != "polecat.me" || url.encodedPath.trimEnd('/') != "/mentions") return null
    val p = url.parameters
    val nick = (p["usernames"] ?: p["username"])?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return PolecatLink(nick, p["message"]?.toLongOrNull())
}

/** `[{id, date (ms), nick, flairs ("a,b"), text}]`. */
internal fun parsePolecat(body: String): List<PolecatLine>? = runCatching {
    (chatJson.parseToJsonElement(body) as JsonArray).mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        PolecatLine(
            id = (o["id"] as? JsonPrimitive)?.longOrNull ?: 0L,
            at = (o["date"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            nick = (o["nick"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
            flairs = (o["flairs"] as? JsonPrimitive)?.contentOrNull.orEmpty().split(',').filter { it.isNotBlank() },
            text = (o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        )
    }
}.getOrNull()
