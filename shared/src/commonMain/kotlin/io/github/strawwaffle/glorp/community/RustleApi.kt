package io.github.strawwaffle.glorp.community

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** What the search screen asks rustlesearch for; a fake stands in for it in tests. */
interface RustleSource {
    /** One page (up to [RustleApi.PAGE] messages, newest first) of [query], continuing after [searchAfter] when given. */
    suspend fun search(query: RustleQuery, searchAfter: Long? = null): RustleReply<List<LogMessage>>

    /** The lines logged around [message]. */
    suspend fun surrounds(message: LogMessage): RustleReply<Surrounds>

    /** The channels rustlesearch logs. */
    suspend fun channels(): RustleReply<List<String>>
}

/**
 * rustlesearch.dev's anonymous API: chat log search, the lines around a
 * message, and the channels it logs. Anonymous use is limited per address
 * (200 requests a day when written), and every reply says how many are left.
 */
class RustleApi(private val http: HttpClient, private val base: String = BASE) : RustleSource {

    override suspend fun search(query: RustleQuery, searchAfter: Long?): RustleReply<List<LogMessage>> =
        call("/anon/search", buildList {
            add("channel" to query.channel)
            if (query.username.isNotBlank()) add("username" to query.username.trim())
            if (query.text.isNotBlank()) add("text" to query.text.trim())
            query.startDate?.let { add("start_date" to it) }
            query.endDate?.let { add("end_date" to it) }
            searchAfter?.let { add("search_after" to it.toString()) }
        }, ::parseSearch)

    override suspend fun surrounds(message: LogMessage): RustleReply<Surrounds> =
        call("/anon/surrounds", listOf(
            "channel" to message.channel,
            "username" to message.username,
            "datetime" to message.ts,
        ), ::parseSurrounds)

    /** Asked once per run of the app. */
    override suspend fun channels(): RustleReply<List<String>> {
        cachedChannels?.let { return RustleReply.Ok(it, null) }
        val reply = call("/anon/channels", emptyList(), ::parseChannels)
        if (reply is RustleReply.Ok && reply.value.isNotEmpty()) cachedChannels = reply.value
        return reply
    }

    private suspend fun <T> call(path: String, params: List<Pair<String, String>>, parse: (String) -> T): RustleReply<T> = try {
        val response = http.get(base + path) {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            for ((k, v) in params) parameter(k, v)
        }
        val quota = parseQuota(response.headers["Ratelimit"])
        val body = response.bodyAsText()
        when {
            response.status.value == 429 -> RustleReply.Limited(quota)
            response.status.isSuccess() -> RustleReply.Ok(parse(body), quota)
            else -> RustleReply.Failed(errorOf(body) ?: "rustlesearch.dev answered ${response.status.value}", quota)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        println("Glorp: rustlesearch $path failed: $e")
        RustleReply.Failed("Couldn't reach rustlesearch.dev", null)
    }

    companion object {
        const val BASE = "https://api-v2.rustlesearch.dev"
        const val SITE = "https://rustlesearch.dev/"

        /** Messages per page; a shorter page is the last. */
        const val PAGE = 100

        private var cachedChannels: List<String>? = null
    }
}

/** What to search for. Dates are `YYYY-MM-DD`, in UTC. */
data class RustleQuery(
    val username: String = "",
    val text: String = "",
    val channel: String = "Destinygg",
    val startDate: String? = null,
    val endDate: String? = null,
)

sealed interface RustleReply<out T> {
    val quota: Quota?

    data class Ok<T>(val value: T, override val quota: Quota?) : RustleReply<T>
    data class Failed(val message: String, override val quota: Quota?) : RustleReply<Nothing>

    /** Out of requests until [quota]'s reset. */
    data class Limited(override val quota: Quota?) : RustleReply<Nothing>
}

/** The `Ratelimit` header: `limit=200, remaining=190, reset=38705` (seconds until it refills). */
data class Quota(val limit: Int, val remaining: Int, val resetSeconds: Long)

@Serializable
data class LogMessage(
    val channel: String,
    val text: String,
    /** ISO time, as the API wrote it; surrounds wants it back unchanged. */
    val ts: String,
    val username: String,
    /** The epoch milliseconds to continue the search from (the message's own time). */
    val searchAfter: Long,
) {
    val at: Long get() = searchAfter
}

/** A logged line: `[2026-11-18 22:46:56.801 UTC] nick: text`. */
data class LogLine(val at: Long, val nick: String, val text: String)

data class Surrounds(val lines: List<LogLine>, val matched: Int?)

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private class Envelope<T>(val type: String = "", val error: String? = null, val data: T? = null)

@Serializable
private class SearchData(val messages: List<LogMessage> = emptyList())

@Serializable
private class SurroundsData(val lines: List<String> = emptyList(), val matched: String? = null)

@Serializable
private class ChannelsData(val channels: List<String> = emptyList())

internal fun parseSearch(body: String): List<LogMessage> =
    json.decodeFromString<Envelope<SearchData>>(body).data?.messages.orEmpty()

internal fun parseChannels(body: String): List<String> =
    json.decodeFromString<Envelope<ChannelsData>>(body).data?.channels.orEmpty()

internal fun parseSurrounds(body: String): Surrounds {
    val data = json.decodeFromString<Envelope<SurroundsData>>(body).data ?: return Surrounds(emptyList(), null)
    var matched: Int? = null
    val lines = ArrayList<LogLine>(data.lines.size)
    for (raw in data.lines) {
        val line = parseLogLine(raw) ?: continue
        if (matched == null && raw == data.matched) matched = lines.size
        lines += line
    }
    return Surrounds(lines, matched)
}

internal fun errorOf(body: String): String? =
    runCatching { json.decodeFromString<Envelope<kotlinx.serialization.json.JsonElement>>(body).error }.getOrNull()

private val logLine = Regex("""^\[(\d{4}-\d\d-\d\d) (\d\d:\d\d:\d\d(?:\.\d+)?) UTC] ([^:]+): ?(.*)$""", RegexOption.DOT_MATCHES_ALL)

internal fun parseLogLine(line: String): LogLine? {
    val m = logLine.find(line) ?: return null
    val (date, time, nick, text) = m.destructured
    val at = runCatching { Instant.parse("${date}T${time}Z").toEpochMilliseconds() }.getOrDefault(0L)
    return LogLine(at, nick, text)
}

internal fun parseQuota(header: String?): Quota? {
    header ?: return null
    val fields = header.split(',').mapNotNull { part ->
        val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        k.trim() to v.trim()
    }.toMap()
    val remaining = fields["remaining"]?.toIntOrNull() ?: return null
    return Quota(fields["limit"]?.toIntOrNull() ?: 0, remaining, fields["reset"]?.toLongOrNull() ?: 0L)
}
