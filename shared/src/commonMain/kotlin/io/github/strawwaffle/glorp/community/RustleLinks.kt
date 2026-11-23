package io.github.strawwaffle.glorp.community

import io.ktor.http.URLBuilder
import io.ktor.http.Url
import kotlin.time.Instant

/** A rustlesearch.dev page a link points at, as the app can open it. */
sealed interface RustleLink {
    /** `/?username=…&text=…&channel=…&start_date=…&end_date=…` */
    data class Search(val query: RustleQuery) : RustleLink

    /** `/surrounds?channel=…&date=…&username=…`: the chat around one message. */
    data class Around(val message: LogMessage) : RustleLink
}

/**
 * Reads a rustlesearch.dev link, so one posted in chat opens in the app
 * instead of the browser. Null for anything else.
 */
fun rustleLinkOf(link: String): RustleLink? {
    val url = runCatching { Url(link.trim()) }.getOrNull() ?: return null
    if (url.host.lowercase().removePrefix("www.") != SITE_HOST) return null
    val p = url.parameters
    fun value(name: String) = p[name]?.trim()?.takeIf { it.isNotEmpty() }
    return when (url.encodedPath.trim('/')) {
        "" -> RustleLink.Search(
            RustleQuery(
                username = value("username").orEmpty(),
                text = value("text").orEmpty(),
                channel = value("channel") ?: "Destinygg",
                startDate = value("start_date")?.takeIf { DATE.matches(it) },
                endDate = value("end_date")?.takeIf { DATE.matches(it) },
            ),
        )
        "surrounds" -> {
            val date = value("date") ?: return null
            val at = runCatching { Instant.parse(date).toEpochMilliseconds() }.getOrNull() ?: return null
            RustleLink.Around(LogMessage(value("channel") ?: "Destinygg", "", date, value("username") ?: return null, at))
        }
        else -> null
    }
}

/** The same search on rustlesearch.dev. */
fun RustleQuery.siteLink(): String = URLBuilder("https://$SITE_HOST/").apply {
    if (username.isNotBlank()) parameters.append("username", username.trim())
    if (text.isNotBlank()) parameters.append("text", text.trim())
    startDate?.let { parameters.append("start_date", it) }
    endDate?.let { parameters.append("end_date", it) }
    parameters.append("channel", channel)
}.buildString()

/** The chat around [this] on rustlesearch.dev. */
fun LogMessage.siteLink(): String = URLBuilder("https://$SITE_HOST/surrounds").apply {
    parameters.append("channel", channel)
    parameters.append("date", ts)
    parameters.append("username", username)
}.buildString()

/** The site's quick ranges: today, the last month, the last year (UTC days, as its search takes them). */
enum class DateRange { Today, Month, Year }

/** [range] ending on the day of [nowMs], as `start` to `end`. */
fun dateRange(range: DateRange, nowMs: Long): Pair<String, String> {
    val today = Instant.fromEpochMilliseconds(nowMs).toString().take(10)
    val (y, m, d) = today.split('-').map { it.toInt() }
    val start = when (range) {
        DateRange.Today -> today
        DateRange.Month -> if (m == 1) day(y - 1, 12, d) else day(y, m - 1, d)
        DateRange.Year -> day(y - 1, m, d)
    }
    return start to today
}

/** A date written as the search takes it, the day kept within its month (31 March less a month is 28 or 29 February). */
private fun day(y: Int, m: Int, d: Int): String {
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)[m - 1]
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${minOf(d, days).toString().padStart(2, '0')}"
}

private const val SITE_HOST = "rustlesearch.dev"
private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
