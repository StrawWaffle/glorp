package io.github.strawwaffle.glorp.community

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * dinkdonk.mov, read-only. Watch history comes from the site's one public
 * JSON endpoint. Everything else is read from the server-rendered pages and
 * the fragments their lists page in with (`/api/page/ratings`,
 * `/api/page/polls`): the `data-*` attributes and a few class names. Each
 * parser returns what it could read and leaves out what it couldn't, so a
 * change on the site shows less rather than something wrong.
 */

const val DINKDONK = "https://dinkdonk.mov"

/** A movie-night session. Times are seconds since the epoch. */
data class MovieSession(val start: Long, val end: Long?, val live: Boolean = false)

/** The site's banner (the session on now, or the next one) and the schedule after it. */
data class MovieSchedule(val banner: MovieSession?, val sessions: List<MovieSession>)

/** One entry of the watch history. */
data class Watched(
    val id: Long,
    val title: String,
    val year: String?,
    val episodeTag: String?,
    val imdbId: String?,
    val watchedAt: Long?,
    val conductor: String?,
    val picker: String?,
    val notes: String?,
)

/** A rating poll in the ratings list; [id] is the site's short id, as in `/rate/{id}/results`. */
data class RatingCard(val id: String, val title: String, val poster: String?, val average: Double?, val votes: Int?)

/** A poll in the polls list; [byline] is the site's own line, such as "Ranked Choice (Borda) Poll by Pizza". */
data class PollCard(val id: String, val title: String, val byline: String?, val winner: String?, val winnerPoster: String?)

/** One page of a list, and the page after it if there is one. */
data class ListPage<T>(val items: List<T>, val next: Int?)

data class RatingResult(
    val title: String,
    val link: String?,
    val poster: String?,
    val overview: String?,
    val createdBy: String?,
    val endedAt: Long?,
    val average: Double?,
    val votes: Int?,
    val didntWatch: Int?,
    /** Votes per half star, 0.5 to 5 (ten buckets). Empty if the page had none. */
    val histogram: List<Int>,
)

data class PollOption(
    val title: String,
    val link: String?,
    val poster: String?,
    val count: Double?,
    val percent: Double?,
    /** A sub-option of the one before it (e.g. "With New Conductor" under "Continue Conducting"). */
    val sub: Boolean,
)

data class PollResult(
    val title: String,
    val byline: String?,
    val endedAt: Long?,
    /** The site's poll type: single_choice, multi_choice, ranked_choice, point_assignment, instant_runoff. */
    val type: String?,
    /** Empty for instant-runoff polls, whose round-by-round results stay on the site. */
    val options: List<PollOption>,
    val footer: String?,
) {
    val countsPoints: Boolean get() = type == "ranked_choice" || type == "point_assignment"
}

object DinkDonkParse {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private class HistoryResponse(val results: List<HistoryEntry> = emptyList())

    @Serializable
    private class HistoryEntry(
        val id: Long = 0,
        val title: String? = null,
        val year: String? = null,
        val episodeTag: String? = null,
        val imdbId: String? = null,
        val watchedAtTs: Long? = null,
        val conductor: String? = null,
        val picker: String? = null,
        val notes: String? = null,
    )

    fun history(body: String): List<Watched> = json.decodeFromString<HistoryResponse>(body).results.mapNotNull { e ->
        val title = e.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        Watched(
            id = e.id,
            title = title,
            year = e.year.blankToNull(),
            episodeTag = e.episodeTag.blankToNull(),
            imdbId = e.imdbId.blankToNull(),
            watchedAt = e.watchedAtTs?.takeIf { it > 0 },
            conductor = e.conductor.blankToNull(),
            picker = e.picker.blankToNull(),
            notes = e.notes.blankToNull(),
        )
    }

    private val sessionTag = Regex("""<div\s+class="(tv-guide__session[^"]*)"\s+data-start="([^"]+)"(?:\s+data-end="([^"]*)")?""")
    private val bannerTag = Regex("""data-controller="schedule-banner"\s+data-start="([^"]+)"\s+data-end="([^"]*)"\s+data-timezone="([^"]+)"""")

    /** The `/schedule` page (or `/`, for the banner alone). The guide's times are New York time. */
    fun schedule(html: String): MovieSchedule {
        val banner = bannerTag.find(html)?.let { m ->
            val start = zonedEpochSeconds(m.groupValues[1], m.groupValues[3]) ?: return@let null
            MovieSession(start, zonedEpochSeconds(m.groupValues[2], m.groupValues[3]))
        }
        val sessions = sessionTag.findAll(html).mapNotNull { m ->
            val start = zonedEpochSeconds(m.groupValues[2], NEW_YORK) ?: return@mapNotNull null
            MovieSession(start, m.groupValues[3].takeIf { it.isNotEmpty() }?.let { zonedEpochSeconds(it, NEW_YORK) }, live = "--active" in m.groupValues[1])
        }.toList()
        return MovieSchedule(banner, sessions)
    }

    /** The banner's session as of [now]: on now, or still to come. Null when it has ended. */
    fun current(schedule: MovieSchedule, now: Long): MovieSession? {
        val banner = schedule.banner
        if (banner != null && (banner.end ?: banner.start) > now) return banner.copy(live = banner.start <= now)
        return schedule.sessions.firstOrNull { (it.end ?: it.start) > now }?.let { it.copy(live = it.start <= now) }
    }

    /** A page of `/api/page/ratings`. Placeholder cards (no link) are skipped. */
    fun ratings(html: String): ListPage<RatingCard> {
        val cards = html.split("<a class=\"rating-card\" href=\"/rate/").drop(1).mapNotNull { chunk ->
            val id = chunk.substringBefore('/').takeIf { it.isShortId() } ?: return@mapNotNull null
            val title = between(chunk, "rating-card__title\">", "</h3>")?.let(::text) ?: return@mapNotNull null
            val numbers = numbers(chunk.substringAfter("rating-card__details", ""))
            RatingCard(id, title, imgSrc(chunk), numbers.getOrNull(0), numbers.getOrNull(1)?.toInt())
        }
        return ListPage(cards, nextPage(html, cards.isNotEmpty()))
    }

    /** A page of `/api/page/polls`. */
    fun polls(html: String): ListPage<PollCard> {
        val cards = html.split("<a class=\"poll-card\" href=\"/poll/").drop(1).mapNotNull { chunk ->
            val id = chunk.substringBefore('/').takeIf { it.isShortId() } ?: return@mapNotNull null
            val card = chunk.substringBefore("</a>")
            val title = between(card, "poll-card__title\">", "</h3>")?.let(::text) ?: return@mapNotNull null
            val byline = between(card.substringAfter("</h3>"), "<span>", "</span>")?.let(::text)
            val winnerBlock = card.substringAfter("poll-card__winner\">", "").substringBefore("poll-card__winner\">")
            val winner = between(winnerBlock, "<span>", "</span>")?.let(::text)
            PollCard(id, title, byline.blankToNull(), winner.blankToNull(), if (winner != null) imgSrc(winnerBlock) else null)
        }
        return ListPage(cards, nextPage(html, cards.isNotEmpty()))
    }

    /** `/rate/{id}/results`. */
    fun ratingResult(html: String): RatingResult? {
        val main = html.substringAfter("<main", "").ifEmpty { return null }
        val option = main.substringAfter("<div class=\"option\">", "")
        val link = Regex("""<a class="link" href="([^"]+)"[^>]*>([^<]+)</a>""").find(option)
        val title = link?.groupValues?.get(2)?.let(::text)
            ?: between(option, "<span>", "</span>")?.let(::text)
            ?: return null
        val stars = main.substringAfter("class=\"rating-stars\"", "").substringBefore("</div>")
        val numbers = numbers(stars)
        val ratings = Regex("""data-ratings="([^"]*)"""").find(main)?.groupValues?.get(1)?.let(::unescape)
        return RatingResult(
            title = title,
            link = link?.groupValues?.get(1)?.takeIf { it.startsWith("https://") },
            poster = imgSrc(option.substringBefore("option__details")),
            overview = between(option, "class=\"option__overview\"", "</p>")?.substringAfter('>')?.let(::text).blankToNull(),
            createdBy = footers(main).firstNotNullOfOrNull { it.substringAfter("Created by ", "").blankToNull() },
            endedAt = endedAt(main),
            average = numbers.getOrNull(0),
            votes = numbers.getOrNull(1)?.toInt(),
            didntWatch = numbers.getOrNull(2)?.toInt(),
            histogram = ratings?.let(::histogram) ?: emptyList(),
        )
    }

    /** `/poll/{id}/results`. */
    fun pollResult(html: String): PollResult? {
        val main = html.substringAfter("<main", "").ifEmpty { return null }
        val title = between(main, "section-header__heading\">", "</h2>")?.let(::text) ?: return null
        val type = Regex("""data-poll-result-type-value="([a-z_]+)"""").find(main)?.groupValues?.get(1)
        val options = if (type == "instant_runoff") emptyList() else {
            val list = main.substringAfter("data-poll-result-target=\"options\"", "").substringBefore("class=\"section-footer\"")
            Regex("""<div class="option( option-group__sub)?">""").findAll(list).toList().let { starts ->
                starts.mapIndexedNotNull { i, start ->
                    val chunk = list.substring(start.range.last + 1, starts.getOrNull(i + 1)?.range?.first ?: list.length)
                    option(chunk, sub = start.groupValues[1].isNotEmpty())
                }
            }
        }
        return PollResult(
            title = title,
            byline = footers(main).firstOrNull { "Poll" in it },
            endedAt = endedAt(main),
            type = type,
            options = options,
            footer = between(main, "class=\"section-footer\"", "</main>")?.substringAfter('>')?.let(::text).blankToNull(),
        )
    }

    private fun option(chunk: String, sub: Boolean): PollOption? {
        val details = chunk.substringAfter("option__details", "").substringBefore("option__end")
        val link = Regex("""<a class="link" href="([^"]+)"[^>]*>([^<]+)</a>""").find(details)
        val title = link?.groupValues?.get(2)?.let(::text)
            ?: between(details, "<span>", "</span>")?.let(::text)
            ?: return null
        if (title.isBlank()) return null
        val counts = numbers(chunk.substringAfter("option__end", "").substringBefore("option__bottom"))
        return PollOption(
            title = title,
            link = link?.groupValues?.get(1)?.takeIf { it.startsWith("https://") },
            poster = imgSrc(chunk.substringBefore("option__details")),
            count = counts.getOrNull(0),
            percent = counts.getOrNull(1),
            sub = sub,
        )
    }

    /** Ratings in half stars, counted into ten buckets from 0.5 to 5. */
    internal fun histogram(array: String): List<Int> {
        val values = try {
            (json.parseToJsonElement(array) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val buckets = IntArray(10)
        for (v in values) {
            val i = kotlin.math.round(v * 2).toInt() - 1
            if (i in 0..9) buckets[i]++
        }
        return buckets.toList()
    }

    private fun footers(main: String): List<String> =
        Regex("""section-header__footer">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).findAll(main).map { text(it.groupValues[1]) }.toList()

    private fun endedAt(main: String): Long? =
        Regex("""data-unix-timestamp-unix-value="(\d+)"""").find(main)?.groupValues?.get(1)?.toLongOrNull()

    private val pageInput = Regex("""name="page"[^>]*value="(\d+)"""")
    private fun nextPage(html: String, any: Boolean): Int? =
        if (!any) null else pageInput.find(html)?.groupValues?.get(1)?.toIntOrNull()

    private val numberValue = Regex("""data-format-number-value-value="(-?[0-9.]+)"""")
    private fun numbers(html: String): List<Double> = numberValue.findAll(html).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()

    private val imgTag = Regex("""<img src="([^"]+)"""")
    private fun imgSrc(html: String): String? = imgTag.find(html)?.groupValues?.get(1)?.takeIf { it.startsWith("https://") }

    private fun between(s: String, start: String, end: String): String? {
        val i = s.indexOf(start)
        if (i < 0) return null
        val from = i + start.length
        val j = s.indexOf(end, from)
        return if (j < 0) null else s.substring(from, j)
    }

    private fun String.isShortId() = isNotEmpty() && length <= 32 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private val tag = Regex("<[^>]*>")
    private val space = Regex("\\s+")
    internal fun text(html: String): String = unescape(html.replace(tag, " ")).replace(space, " ").trim()

    private val entity = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    internal fun unescape(s: String): String = s.replace(entity) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint) ?: m.value
            else -> when (e) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"; "nbsp" -> " "
                else -> m.value
            }
        }
    }

    private fun codePoint(c: Int): String = when {
        c in 0..0xFFFF -> c.toChar().toString()
        c in 0x10000..0x10FFFF -> (c - 0x10000).let { charArrayOf((0xD800 + (it shr 10)).toChar(), (0xDC00 + (it and 0x3FF)).toChar()).concatToString() }
        else -> ""
    }

    private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    const val NEW_YORK = "America/New_York"
}

/** TMDB posters come at w92 in the pages; [width] picks another of TMDB's sizes (w154, w185, w342). */
fun tmdbPoster(url: String?, width: String): String? =
    url?.let { if (it.startsWith("https://image.tmdb.org/t/p/w92/")) it.replace("/t/p/w92/", "/t/p/$width/") else it }

/** Fetches from dinkdonk.mov, naming Glorp in the User-Agent. Each call is one request, made when a screen asks. */
class DinkDonkClient(private val http: HttpClient) {
    private suspend fun get(path: String, params: Map<String, Any?> = emptyMap()): String {
        val response = http.get(DINKDONK + path) {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            params.forEach { (k, v) -> if (v != null) parameter(k, v) }
        }
        if (!response.status.isSuccess()) throw IllegalStateException("dinkdonk.mov answered ${response.status.value}")
        return response.bodyAsText()
    }

    suspend fun schedule(): MovieSchedule = DinkDonkParse.schedule(get("/schedule"))

    suspend fun history(offset: Int, limit: Int, search: String?): List<Watched> =
        DinkDonkParse.history(get("/api/page/history/full", mapOf("offset" to offset, "limit" to limit, "search" to search?.takeIf { it.isNotBlank() })))

    suspend fun ratings(page: Int, search: String?, order: String): ListPage<RatingCard> =
        DinkDonkParse.ratings(get("/api/page/ratings", mapOf("search" to (search ?: ""), "order" to order, "type" to "", "page" to page)))

    suspend fun polls(page: Int): ListPage<PollCard> =
        DinkDonkParse.polls(get("/api/page/polls", mapOf("type" to "", "creator" to "", "page" to page)))

    suspend fun ratingResult(id: String): RatingResult? = DinkDonkParse.ratingResult(get("/rate/$id/results"))

    suspend fun pollResult(id: String): PollResult? = DinkDonkParse.pollResult(get("/poll/$id/results"))
}
