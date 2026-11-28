package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.community.LogLine
import io.github.strawwaffle.glorp.community.RustleApi
import io.github.strawwaffle.glorp.community.RustleQuery
import io.github.strawwaffle.glorp.community.RustleReply
import io.github.strawwaffle.glorp.diag.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * chat-gui's `/stalk` (someone's last messages) and `/mentions` (the last
 * messages naming someone). The site answers both only with its cookies; with
 * a login key, or signed out, they come from rustlesearch's logs instead, and
 * mentions first from Glorp's mentions service, which keeps the last hours.
 */
class LookBack(
    private val client: HttpClient,
    private val site: Site,
    private val cookies: Session.Cookies?,
    /** Glorp's mentions service, when this build has one. */
    private val mentionsServer: String?,
) {
    sealed interface Result {
        /** Oldest first; [source] names where they came from when it isn't the site. */
        data class Lines(val lines: List<LogLine>, val source: String?) : Result
        data class Failed(val why: String) : Result
    }

    suspend fun stalk(nick: String, limit: Int): Result {
        if (cookies != null) {
            val body = siteGet("/api/chat/stalk", nick, limit) ?: return Result.Failed("No messages from $nick received. Try again later.")
            return parseStalk(body)?.let { Result.Lines(it.takeLast(limit), null) } ?: Result.Failed("No messages from $nick received. Try again later.")
        }
        return fromLogs(RustleQuery(username = nick), limit)
    }

    suspend fun mentions(nick: String, limit: Int): Result {
        // polecat.me's logger first: any sign-in, and it keeps everything.
        io.github.strawwaffle.glorp.community.Polecat(client).mentions(nick, size = limit.coerceAtLeast(1))?.let { found ->
            return Result.Lines(found.take(limit).map { it.logLine() }.reversed(), "polecat.me")
        }
        if (cookies != null) {
            val body = siteGet("/api/chat/mentions", nick, limit) ?: return Result.Failed("No mentions for $nick received. Try again later.")
            return parseSiteMentions(body)?.let { Result.Lines(it.takeLast(limit), null) } ?: Result.Failed("No mentions for $nick received. Try again later.")
        }
        if (mentionsServer != null) {
            val found = text("${mentionsServer.trimEnd('/')}/mentions") {
                parameter("nick", nick)
                parameter("since", 0)
            }?.let(::parseServiceMentions)
            if (!found.isNullOrEmpty()) return Result.Lines(found.takeLast(limit), "the last hours of chat")
        }
        return fromLogs(RustleQuery(text = nick), limit)
    }

    private suspend fun fromLogs(query: RustleQuery, limit: Int): Result =
        when (val reply = RustleApi(client).search(query)) {
            // Newest first from the search; shown oldest first, as chat reads.
            is RustleReply.Ok -> Result.Lines(reply.value.take(limit).map { LogLine(it.at, it.username, it.text) }.reversed(), "rustlesearch")
            is RustleReply.Limited -> Result.Failed("rustlesearch's daily limit is used up. Sign in with destiny.gg to ask the site instead.")
            is RustleReply.Failed -> Result.Failed("rustlesearch didn't answer: ${reply.message}")
        }

    private suspend fun siteGet(path: String, nick: String, limit: Int): String? = text(site.origin + path) {
        header(HttpHeaders.Cookie, cookies!!.cookieHeader)
        parameter("username", nick)
        parameter("limit", limit)
    }

    private suspend fun text(url: String, more: io.ktor.client.request.HttpRequestBuilder.() -> Unit): String? = try {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            more()
        }
        if (response.status.isSuccess()) response.bodyAsText() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.note("lookback", "request failed", e)
        null
    }

    companion object {
        /** chat-gui asks for 3 unless told; the site gives at most this many. */
        const val DEFAULT_LIMIT = 3
        const val MAX_LIMIT = 50
    }
}

private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

/** `/api/chat/stalk`: `{success, data: {nick, lines: [{timestamp (s), text}]}}`, oldest first. */
internal fun parseStalk(body: String): List<LogLine>? = runCatching {
    val root = chatJson.parseToJsonElement(body).jsonObject
    if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val data = root["data"] as? JsonObject ?: return emptyList()
    val nick = data.string("nick") ?: return null
    (data["lines"] as? JsonArray).orEmpty().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        LogLine((o.long("timestamp") ?: return@mapNotNull null) * 1000, nick, o.string("text").orEmpty())
    }
}.getOrNull()

/** `/api/chat/mentions`: `{success, data: [{date (s), nick, text}]}`, oldest first. */
internal fun parseSiteMentions(body: String): List<LogLine>? = runCatching {
    val root = chatJson.parseToJsonElement(body).jsonObject
    if ((root["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
    (root["data"] as? JsonArray).orEmpty().mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        LogLine((o.long("date") ?: return@mapNotNull null) * 1000, o.string("nick") ?: return@mapNotNull null, o.string("text").orEmpty())
    }
}.getOrNull()

/** Glorp's mentions service: `{mentions: [{nick, text, at (ms)}]}`, oldest first. */
internal fun parseServiceMentions(body: String): List<LogLine>? = runCatching {
    chatJson.parseToJsonElement(body).jsonObject["mentions"]!!.jsonArray.mapNotNull { item ->
        val o = item as? JsonObject ?: return@mapNotNull null
        LogLine(o.long("at") ?: return@mapNotNull null, o.string("nick") ?: return@mapNotNull null, o.string("text").orEmpty())
    }
}.getOrNull()
