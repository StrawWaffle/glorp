package io.github.strawwaffle.glorp.community

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/** A destiny.gg chat poll as mitchdev.net archives it. */
@Serializable
data class Poll(
    val id: Long,
    val question: String = "",
    val options: List<String> = emptyList(),
    val votes: List<Int> = emptyList(),
    @SerialName("total_votes") val totalVotes: Int = 0,
    /** Non-zero for a poll whose votes count by subscription tier (`/spoll`). */
    val weighted: Int = 0,
    @SerialName("started_by") val startedBy: String = "",
    /** Milliseconds. */
    val duration: Long = 0,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
) {
    val isWeighted: Boolean get() = weighted != 0

    /** When the poll started, in epoch milliseconds. The archive's `created_at` is hours off, so it isn't used. */
    val startedAt: Long? get() = (startTime ?: endTime)?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

    fun votesFor(option: Int): Int = votes.getOrElse(option) { 0 }

    /** Out of all votes cast; [totalVotes] when the archive has it, else the sum. */
    val sum: Int get() = if (totalVotes > 0) totalVotes else votes.sum()

    /** The options with the most votes; none if nobody voted. */
    val winners: Set<Int>
        get() {
            val top = votes.maxOrNull() ?: return emptySet()
            if (top <= 0) return emptySet()
            return votes.indices.filter { votes[it] == top }.toSet()
        }
}

/** Where the next page starts: `before` an id when newest first, `after` one when oldest first. */
data class PollCursor(val key: String, val value: String)

data class PollPage(val polls: List<Poll>, val next: PollCursor?, val totalCount: Int)

data class PollMaker(val name: String, val count: Int)

data class YeePepe(val yee: Int, val pepe: Int)

/**
 * The poll archive at api.mitchdev.net, used with its maintainer's
 * permission. Anonymous GETs, one per page the reader asks for.
 */
class PollApi(private val http: HttpClient) {
    suspend fun page(cursor: PollCursor?, search: String, startedBy: String?, oldestFirst: Boolean): PollPage =
        parsePage(get("polls") {
            cursor?.let { parameter(it.key, it.value) }
            if (search.isNotBlank()) parameter("search", search.trim())
            if (!startedBy.isNullOrBlank()) parameter("startedBy", startedBy)
            if (oldestFirst) parameter("sort", "asc")
        })

    suspend fun poll(id: Long): Poll = parsePoll(get("poll/$id"))

    suspend fun makers(): List<PollMaker> = parseMakers(get("polls/makers"))

    suspend fun yeePepe(): YeePepe = parseYeePepe(get("polls/yeevpepe"))

    private suspend fun get(path: String, params: HttpRequestBuilder.() -> Unit = {}): String {
        val response = http.get("$BASE/$path") {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            params()
        }
        if (!response.status.isSuccess()) error("mitchdev.net answered ${response.status.value}")
        return response.bodyAsText()
    }

    companion object {
        const val BASE = "https://api.mitchdev.net/dgg"
        const val SITE = "https://mitchdev.net/dgg/"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

        @Serializable
        private data class PageJson(
            val nextCursor: Map<String, String?>? = null,
            val totalCount: Int = 0,
            val data: List<Poll> = emptyList(),
        )

        @Serializable
        private data class OneJson(val data: Poll)

        @Serializable
        private data class MakerJson(val name: String = "", val count: Int = 0)

        // The points (one per YEE vs PEPE poll, about 100 kB) are skipped.
        @Serializable
        private data class YeePepeJson(val totalYee: Int = 0, val totalPepe: Int = 0)

        internal fun parsePage(text: String): PollPage {
            val page = json.decodeFromString(PageJson.serializer(), text)
            val next = page.nextCursor?.entries?.firstOrNull { !it.value.isNullOrEmpty() }?.let { PollCursor(it.key, it.value!!) }
            return PollPage(page.data, next, page.totalCount)
        }

        internal fun parsePoll(text: String): Poll = json.decodeFromString(OneJson.serializer(), text).data

        internal fun parseMakers(text: String): List<PollMaker> =
            json.decodeFromString(ListSerializer(MakerJson.serializer()), text)
                .filter { it.name.isNotEmpty() }
                .map { PollMaker(it.name, it.count) }

        internal fun parseYeePepe(text: String): YeePepe =
            json.decodeFromString(YeePepeJson.serializer(), text).let { YeePepe(it.totalYee, it.totalPepe) }
    }
}
