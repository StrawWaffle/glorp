package io.github.strawwaffle.glorp.community

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What the archive is filtered by. */
data class PollQuery(val search: String = "", val startedBy: String? = null, val oldestFirst: Boolean = false)

/**
 * The polls on screen, a page at a time. Lives as long as the screen: nothing
 * is fetched once it closes. At most [MAX_PAGES] pages are kept; reading on
 * past that drops the earliest page, and the list offers to start over.
 */
internal class PollFeed(
    private val scope: CoroutineScope,
    /** One page of [PollQuery]'s results from a cursor: [PollApi.page]. */
    private val fetch: suspend (PollCursor?, PollQuery) -> PollPage,
) {
    var query by mutableStateOf(PollQuery())
        private set
    val polls = mutableStateListOf<Poll>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var total by mutableStateOf<Int?>(null)
        private set

    /** Whether the earliest pages were dropped to keep memory bounded. */
    var trimmed by mutableStateOf(false)
        private set
    var done by mutableStateOf(false)
        private set

    private var next: PollCursor? = null
    private val pageSizes = ArrayDeque<Int>()
    private var job: Job? = null

    // Bumped on every new query, so a cancelled request can't touch the new list.
    private var generation = 0

    fun show(query: PollQuery) {
        if (query == this.query && (polls.isNotEmpty() || loading)) return
        this.query = query
        restart()
    }

    fun restart() {
        job?.cancel()
        generation++
        polls.clear()
        pageSizes.clear()
        next = null
        done = false
        trimmed = false
        loading = false
        error = null
        total = null
        loadMore()
    }

    fun loadMore() {
        if (loading || done) return
        val gen = generation
        val q = query
        loading = true
        error = null
        job = scope.launch {
            try {
                val page = fetch(next, q)
                if (gen != generation) return@launch
                val seen = polls.mapTo(HashSet()) { it.id }
                val fresh = page.polls.filter { seen.add(it.id) }
                polls.addAll(fresh)
                pageSizes.addLast(fresh.size)
                while (pageSizes.size > MAX_PAGES) {
                    polls.removeRange(0, pageSizes.removeFirst())
                    trimmed = true
                }
                next = page.next
                done = page.next == null || page.polls.isEmpty()
                total = page.totalCount
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = e.message ?: "Couldn't reach mitchdev.net"
            } finally {
                if (gen == generation) loading = false
            }
        }
    }

    companion object {
        const val MAX_PAGES = 8
    }
}

/**
 * What the archive says about everyone, fetched at most once per run of the
 * app: who starts polls (with counts) and the YEE vs PEPE totals.
 */
internal object PollSession {
    var makers by mutableStateOf<List<PollMaker>?>(null)
    var yeePepe by mutableStateOf<YeePepe?>(null)
    private var makersJob: Job? = null
    private var yeePepeJob: Job? = null

    /** Asks for what isn't in yet. A request cut short by the screen closing is asked again next time. */
    fun load(api: PollApi, scope: CoroutineScope) {
        if (makers == null && makersJob?.isActive != true) {
            makersJob = scope.launch { runCatching { api.makers() }.onSuccess { makers = it } }
        }
        if (yeePepe == null && yeePepeJob?.isActive != true) {
            yeePepeJob = scope.launch { runCatching { api.yeePepe() }.onSuccess { yeePepe = it } }
        }
    }
}

/** 18040 as "18,040". */
internal fun grouped(n: Int): String {
    val digits = kotlin.math.abs(n).toString()
    val out = StringBuilder()
    digits.forEachIndexed { i, c ->
        if (i > 0 && (digits.length - i) % 3 == 0) out.append(',')
        out.append(c)
    }
    return if (n < 0) "-$out" else out.toString()
}

private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** A UTC date from epoch milliseconds, as "22 Nov 2026", with "03:37 UTC" after it if [withTime]. */
internal fun utcDate(ms: Long, withTime: Boolean = false): String {
    val days = ms.floorDiv(86_400_000L)
    val minuteOfDay = ms.mod(86_400_000L) / 60_000
    // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    val date = "$day ${months[(month - 1).toInt()]} $year"
    if (!withTime) return date
    val h = (minuteOfDay / 60).toString().padStart(2, '0')
    val m = (minuteOfDay % 60).toString().padStart(2, '0')
    return "$date, $h:$m UTC"
}

/** How long ago [ms] was, for recent polls, else its date. */
internal fun whenText(ms: Long, now: Long): String {
    val ago = (now - ms) / 1000
    return when {
        ago < 0 -> utcDate(ms)
        ago < 60 -> "just now"
        ago < 3600 -> "${ago / 60} min ago"
        ago < 86_400 -> "${ago / 3600} h ago"
        ago < 7 * 86_400 -> (ago / 86_400).let { if (it == 1L) "yesterday" else "$it days ago" }
        else -> utcDate(ms)
    }
}
