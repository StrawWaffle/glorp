package io.github.strawwaffle.glorp.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.ui.OnBack
import io.github.strawwaffle.glorp.ui.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import kotlin.time.Clock

/*
 * Community movie nights, read from dinkdonk.mov (by Mitch, used with his
 * permission and credited on screen). Nothing is fetched until the screen
 * opens, each list loads a page at a time as it is scrolled, and nothing
 * refreshes on its own: what's loaded is dropped when the screen closes.
 */

private enum class Tab(val label: String) { History("History"), Ratings("Ratings"), Polls("Polls"), Schedule("Schedule") }

private sealed interface Detail {
    data class Rating(val id: String) : Detail
    data class Poll(val id: String) : Detail
}

/** A list that loads a page at a time. [load] gets the page key and returns the items and the next key, if any. */
private class Paged<K, T>(
    private val scope: CoroutineScope,
    private val first: K,
    /** What tells items apart: the list shows each once (a new entry shifts the pages). */
    private val id: (T) -> Any,
    private val load: suspend (K) -> Pair<List<T>, K?>,
) {
    val items = mutableStateListOf<T>()
    private val seen = HashSet<Any>()

    // Bumped by reset: a page asked for before it is dropped, not added.
    private var generation = 0
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var started by mutableStateOf(false)
    private var next: K? = first
    private var job: Job? = null

    val more get() = next != null

    fun loadMore() {
        val key = next ?: return
        if (loading || failed) return
        started = true
        loading = true
        val asked = generation
        job = scope.launch {
            try {
                val (page, after) = load(key)
                if (asked != generation) return@launch
                items += page.filter { seen.add(id(it)) }
                next = after
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (asked == generation) failed = true
            } finally {
                if (asked == generation) loading = false
            }
        }
    }

    fun retry() { failed = false; loadMore() }

    fun reset() {
        generation++
        job?.cancel()
        items.clear()
        seen.clear()
        next = first
        loading = false
        failed = false
        loadMore()
    }
}

private const val HISTORY_PAGE = 30

private class DinkDonkState(val client: DinkDonkClient, scope: CoroutineScope) {
    var historySearch = ""
    val history = Paged<Int, Watched>(scope, 0, { it.id }) { offset ->
        val page = client.history(offset, HISTORY_PAGE, historySearch)
        page to (offset + page.size).takeIf { page.size == HISTORY_PAGE }
    }
    var ratingsSearch = ""
    var ratingsOrder by mutableStateOf(RatingOrder.Recent)
    val ratings = Paged<Int, RatingCard>(scope, 1, { it.id }) { page ->
        client.ratings(page, ratingsSearch, ratingsOrder.value).let { it.items to it.next?.takeIf { n -> n > page } }
    }
    val polls = Paged<Int, PollCard>(scope, 1, { it.id }) { page ->
        client.polls(page).let { it.items to it.next?.takeIf { n -> n > page } }
    }

    // Kept here so a list keeps its place while a result is open.
    val historyList = LazyListState()
    val ratingsList = LazyListState()
    val pollsList = LazyListState()
}

private enum class RatingOrder(val label: String, val value: String) {
    Recent("Recent", "endTime,DESC"),
    Highest("Highest", "average,DESC"),
    Lowest("Lowest", "average,ASC"),
}

/** A fetch made once while it is on screen. */
private sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data object Failed : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
}

@Composable
private fun <T> rememberLoad(key: Any, attempt: Int, fetch: suspend () -> T): Load<T> {
    var state by remember(key, attempt) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, attempt) {
        state = try {
            Load.Done(fetch())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Load.Failed
        }
    }
    return state
}

@Composable
fun DinkDonkScreen(env: CommunityEnv) {
    val scope = rememberCoroutineScope()
    val state = remember { DinkDonkState(DinkDonkClient(env.http), scope) }
    var tab by remember { mutableStateOf(Tab.History) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var scheduleAttempt by remember { mutableStateOf(0) }
    val schedule = rememberLoad("schedule", scheduleAttempt) { state.client.schedule() }

    OnBack(enabled = detail != null) { detail = null }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Header(schedule, onRetry = { scheduleAttempt++ })
        when (val d = detail) {
            is Detail.Rating -> RatingDetail(state.client, d.id)
            is Detail.Poll -> PollDetail(state.client, d.id)
            null -> {
                Tabs(tab) { tab = it }
                when (tab) {
                    Tab.History -> HistoryTab(state)
                    Tab.Ratings -> RatingsTab(state) { detail = Detail.Rating(it) }
                    Tab.Polls -> PollsTab(state) { detail = Detail.Poll(it) }
                    Tab.Schedule -> ScheduleTab(schedule, onRetry = { scheduleAttempt++ })
                }
            }
        }
    }
}

@Composable
private fun Header(schedule: Load<MovieSchedule>, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Movie nights", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Credit()
        Spacer(Modifier.height(6.dp))
        when (schedule) {
            Load.Loading -> Text("Checking the schedule…", color = Palette.TextDim, fontSize = 13.sp)
            Load.Failed -> Text(
                "Couldn't read the schedule. Tap to try again.",
                color = Palette.TextDim,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = onRetry),
            )
            is Load.Done -> {
                val now = Clock.System.now().epochSeconds
                val session = DinkDonkParse.current(schedule.value, now)
                when {
                    session == null -> Text("No session scheduled.", color = Palette.TextDim, fontSize = 13.sp)
                    session.live -> {
                        Text("On now", color = Palette.Orange, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        session.end?.let {
                            Text("Until ${localDateTime(it)} (${relative(it - now)} left)", color = Palette.White, fontSize = 13.sp)
                        }
                    }
                    else -> {
                        Text("Next session in ${relative(session.start - now)}", color = Palette.Glorp, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(span(session), color = Palette.White, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Credit() {
    val text = buildAnnotatedString {
        append("Movie nights by ")
        withLink(LinkAnnotation.Url("$DINKDONK/", TextLinkStyles(SpanStyle(color = Palette.Blue)))) { append("dinkdonk.mov") }
    }
    Text(text, color = Palette.TextDim, fontSize = 12.sp)
}

@Composable
private fun Tabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Navy)) {
        for (t in Tab.entries) {
            val on = t == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    t.label,
                    color = if (on) Palette.White else Palette.TextDim,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Palette.Blue else Palette.Navy))
            }
        }
    }
}

@Composable
private fun HistoryTab(state: DinkDonkState) {
    val list = state.history
    LaunchedEffect(Unit) { if (!list.started) list.loadMore() }
    val listState = state.historyList
    LoadWhenNearEnd(listState, list)
    Column(Modifier.fillMaxSize()) {
        SearchField("Search the history", state.historySearch) {
            state.historySearch = it
            list.reset()
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(list.items, key = { "h" + it.id }) { HistoryRow(it) }
            pagedFooter(list, empty = "Nothing watched matches that.")
        }
    }
}

@Composable
private fun HistoryRow(w: Watched) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            buildString {
                append(w.title)
                w.episodeTag?.let { append(" ").append(it) }
                w.year?.let { append(" (").append(it).append(")") }
            },
            color = Palette.White,
            fontSize = 15.sp,
        )
        val people = listOfNotNull(w.conductor?.let { "conducted by $it" }, w.picker?.let { "picked by $it" }).joinToString(", ")
        val line = listOfNotNull(w.watchedAt?.let(::localDateTime), people.ifEmpty { null }).joinToString(" · ")
        if (line.isNotEmpty()) Text(line, color = Palette.TextDim, fontSize = 12.sp)
        w.notes?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
    }
}

@Composable
private fun RatingsTab(state: DinkDonkState, onOpen: (String) -> Unit) {
    val list = state.ratings
    LaunchedEffect(Unit) { if (!list.started) list.loadMore() }
    val listState = state.ratingsList
    LoadWhenNearEnd(listState, list)
    Column(Modifier.fillMaxSize()) {
        SearchField("Search ratings", state.ratingsSearch) {
            state.ratingsSearch = it
            list.reset()
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (order in RatingOrder.entries) {
                val on = order == state.ratingsOrder
                Text(
                    order.label,
                    color = if (on) Palette.White else Palette.TextDim,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (on) Palette.NavyLight else Palette.Black)
                        .clickable {
                            if (!on) {
                                state.ratingsOrder = order
                                list.reset()
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            items(list.items, key = { "r" + it.id }) { card ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(card.id) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Poster(card.poster)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.title, color = Palette.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        card.average?.let { avg ->
                            Text(
                                "${decimals(avg, 2)} / 5" + (card.votes?.let { " from ${it} votes" } ?: ""),
                                color = Palette.Orange,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            pagedFooter(list, empty = "No ratings match that.")
        }
    }
}

@Composable
private fun PollsTab(state: DinkDonkState, onOpen: (String) -> Unit) {
    val list = state.polls
    LaunchedEffect(Unit) { if (!list.started) list.loadMore() }
    val listState = state.pollsList
    LoadWhenNearEnd(listState, list)
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(list.items, key = { "p" + it.id }) { card ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(card.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(card.title, color = Palette.White, fontSize = 15.sp)
                    card.byline?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
                    card.winner?.let { Text("Winner: $it", color = Palette.Glorp, fontSize = 13.sp) }
                }
                if (card.winnerPoster != null) {
                    Spacer(Modifier.width(12.dp))
                    Poster(card.winnerPoster)
                }
            }
        }
        pagedFooter(list, empty = "No polls yet.")
    }
}

@Composable
private fun ScheduleTab(schedule: Load<MovieSchedule>, onRetry: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        when (schedule) {
            Load.Loading -> item { Spinner() }
            Load.Failed -> item { Problem("Couldn't read the schedule.", onRetry, "$DINKDONK/schedule") }
            is Load.Done -> {
                val now = Clock.System.now().epochSeconds
                val upcoming = schedule.value.sessions.filter { (it.end ?: it.start) > now }
                item {
                    Text(
                        "Regular sessions, in your time zone.",
                        color = Palette.TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                items(upcoming.distinctBy { it.start }, key = { "s" + it.start }) { s ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(span(s), color = Palette.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (s.start <= now) Text("On now", color = Palette.Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (upcoming.isEmpty()) item { Note("No sessions on the schedule.") }
                item { SiteLink("Full schedule on dinkdonk.mov", "$DINKDONK/schedule") }
            }
        }
    }
}

@Composable
private fun RatingDetail(client: DinkDonkClient, id: String) {
    var attempt by remember { mutableStateOf(0) }
    val url = "$DINKDONK/rate/$id/results"
    val load = rememberLoad(id, attempt) { client.ratingResult(id) }
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize()) {
        when (load) {
            Load.Loading -> item { Spinner() }
            Load.Failed -> item { Problem("Couldn't read this rating.", { attempt++ }, url) }
            is Load.Done -> {
                val r = load.value
                if (r == null) {
                    item { Problem("This page couldn't be read.", null, url) }
                    return@LazyColumn
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Poster(r.poster, big = true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                r.title,
                                color = if (r.link != null) Palette.Blue else Palette.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = if (r.link != null) Modifier.clickable { runCatching { uri.openUri(r.link) } } else Modifier,
                            )
                            val meta = listOfNotNull(r.createdBy?.let { "Rating poll by $it" }, r.endedAt?.let { "ended ${localDateTime(it)}" })
                            if (meta.isNotEmpty()) Text(meta.joinToString(", "), color = Palette.TextDim, fontSize = 12.sp)
                            r.average?.let {
                                Spacer(Modifier.height(8.dp))
                                Text("${decimals(it, 2)} / 5", color = Palette.Orange, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                            val counts = listOfNotNull(r.votes?.let { "$it votes" }, r.didntWatch?.let { "$it didn't watch" })
                            if (counts.isNotEmpty()) Text(counts.joinToString(", "), color = Palette.TextDim, fontSize = 13.sp)
                        }
                    }
                }
                r.overview?.let { item { Text(it, color = Palette.White, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp)) } }
                if (r.histogram.any { it > 0 }) {
                    item { Histogram(r.histogram) }
                }
                item { SiteLink("Open on dinkdonk.mov", url) }
            }
        }
    }
}

@Composable
private fun Histogram(buckets: List<Int>) {
    val top = buckets.max().coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        for (i in buckets.indices.reversed()) {
            val stars = (i + 1) / 2.0
            Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (i % 2 == 1) "${(i + 1) / 2}" else decimals(stars, 1), color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.width(32.dp))
                Box(Modifier.weight(1f).fillMaxHeight().padding(vertical = 3.dp)) {
                    Box(
                        Modifier.fillMaxWidth(buckets[i].toFloat() / top).fillMaxHeight()
                            .background(Palette.Orange, RoundedCornerShape(2.dp)),
                    )
                }
                Text("${buckets[i]}", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.width(36.dp).padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun PollDetail(client: DinkDonkClient, id: String) {
    var attempt by remember { mutableStateOf(0) }
    val url = "$DINKDONK/poll/$id/results"
    val load = rememberLoad(id, attempt) { client.pollResult(id) }
    val uri = LocalUriHandler.current
    LazyColumn(Modifier.fillMaxSize()) {
        when (load) {
            Load.Loading -> item { Spinner() }
            Load.Failed -> item { Problem("Couldn't read this poll.", { attempt++ }, url) }
            is Load.Done -> {
                val p = load.value
                if (p == null) {
                    item { Problem("This page couldn't be read.", null, url) }
                    return@LazyColumn
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(p.title, color = Palette.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        val meta = listOfNotNull(p.byline, p.endedAt?.let { "ended ${localDateTime(it)}" })
                        if (meta.isNotEmpty()) Text(meta.joinToString(", "), color = Palette.TextDim, fontSize = 12.sp)
                    }
                }
                if (p.options.isEmpty()) {
                    item {
                        Note(
                            if (p.type == "instant_runoff") "Instant-runoff results go round by round; see them on dinkdonk.mov."
                            else "No results to show here.",
                        )
                    }
                }
                items(p.options) { o ->
                    Row(
                        Modifier.fillMaxWidth()
                            .then(if (o.link != null) Modifier.clickable { runCatching { uri.openUri(o.link) } } else Modifier)
                            .padding(start = if (o.sub) 40.dp else 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (o.poster != null) {
                            Poster(o.poster)
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(o.title, color = Palette.White, fontSize = if (o.sub) 13.sp else 15.sp)
                            val count = o.count?.let { "${whole(it)} ${if (p.countsPoints) "points" else "votes"}" }
                            val share = o.percent?.let { "${decimals(it, 1)}%" }
                            val line = listOfNotNull(count, share).joinToString(", ")
                            if (line.isNotEmpty()) Text(line, color = Palette.TextDim, fontSize = 12.sp)
                            o.percent?.let { pct ->
                                Box(Modifier.fillMaxWidth().height(6.dp).padding(top = 2.dp).background(Palette.Navy, RoundedCornerShape(3.dp))) {
                                    Box(
                                        Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).fillMaxHeight()
                                            .background(Palette.Blue, RoundedCornerShape(3.dp)),
                                    )
                                }
                            }
                        }
                    }
                }
                p.footer?.let { item { Note(it) } }
                item { SiteLink("Open on dinkdonk.mov", url) }
            }
        }
    }
}

@Composable
private fun SearchField(hint: String, initial: String, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = LocalFocusManager.current
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(100) },
        singleLine = true,
        textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
        cursorBrush = SolidColor(Palette.Blue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSubmit(text.trim()) }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .background(Palette.Navy, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { field ->
            Box {
                if (text.isEmpty()) Text(hint, color = Palette.TextDim, fontSize = 15.sp)
                field()
            }
        },
    )
}

@Composable
private fun LoadWhenNearEnd(listState: LazyListState, list: Paged<*, *>) {
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(listState, list) {
        // Waits for each page to land, so a short page right away asks for the next.
        snapshotFlow { nearEnd && !list.loading }.filter { it }.collect { if (list.started && list.more) list.loadMore() }
    }
}

private fun LazyListScope.pagedFooter(list: Paged<*, *>, empty: String) {
    // No key: a keyed footer would hold the list's place as rows arrive above it and scroll past them.
    item {
        when {
            list.loading -> Spinner()
            list.failed -> Problem("Couldn't reach dinkdonk.mov.", list::retry, "$DINKDONK/")
            list.items.isEmpty() && list.started -> Note(empty)
        }
    }
}

@Composable
private fun Poster(url: String?, big: Boolean = false) {
    val (w, h, size) = if (big) Triple(92.dp, 138.dp, "w185") else Triple(40.dp, 60.dp, "w154")
    Box(Modifier.size(w, h).clip(RoundedCornerShape(3.dp)).background(Palette.Navy)) {
        if (url != null) {
            AsyncImage(
                model = tmdbPoster(url, size),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Blue, strokeWidth = 2.dp)
    }
}

@Composable
private fun Note(text: String) =
    Text(text, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))

@Composable
private fun Problem(text: String, onRetry: (() -> Unit)?, url: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(text, color = Palette.TextDim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (onRetry != null) {
                Text("Try again", color = Palette.Blue, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 10.dp))
            }
            SiteLinkText("Open on dinkdonk.mov", url)
        }
    }
}

@Composable
private fun SiteLink(text: String, url: String) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { SiteLinkText(text, url) }
}

@Composable
private fun SiteLinkText(text: String, url: String) {
    val uri = LocalUriHandler.current
    Text(text, color = Palette.Blue, fontSize = 14.sp, modifier = Modifier.clickable { runCatching { uri.openUri(url) } }.padding(vertical = 10.dp))
}

/** "Sat 28 Nov, 03:00 – 15:00", or with the end's date when it falls on another day. */
private fun span(s: MovieSession): String {
    val start = localDateTime(s.start)
    val end = s.end?.let(::localDateTime) ?: return start
    val (startDay, endDay) = start.substringBeforeLast(", ") to end.substringBeforeLast(", ")
    return if (startDay == endDay) "$start – ${end.substringAfterLast(", ")}" else "$start – $end"
}

private fun relative(seconds: Long): String {
    val minutes = (seconds.coerceAtLeast(0) + 59) / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        days >= 2 -> "$days days"
        days == 1L -> "1 day ${hours % 24} h"
        hours >= 1 -> "$hours h ${minutes % 60} min"
        else -> "$minutes min"
    }
}

private fun decimals(value: Double, places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val n = (value * scale).roundToLong()
    val whole = n / scale
    if (places == 0) return whole.toString()
    val frac = kotlin.math.abs(n % scale).toString().padStart(places, '0')
    return "${if (n < 0 && whole == 0L) "-" else ""}$whole.$frac"
}

private fun whole(value: Double): String = if (value == kotlin.math.floor(value)) value.toLong().toString() else decimals(value, 1)
