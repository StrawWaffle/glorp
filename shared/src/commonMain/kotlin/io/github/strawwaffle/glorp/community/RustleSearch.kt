package io.github.strawwaffle.glorp.community

import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.ui.ChatRow
import io.github.strawwaffle.glorp.ui.Palette
import io.github.strawwaffle.glorp.ui.continues
import io.github.strawwaffle.glorp.ui.mentionPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Search destiny.gg chat logs, from rustlesearch.dev. Requests go out only
 * when a search is sent, a page is scrolled to or a line is opened; nothing
 * is fetched in the background, and at most [MAX_KEPT] messages are kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RustleSearchScreen(env: CommunityEnv) {
    val scope = rememberCoroutineScope()
    val search = remember(env.http) { SearchState(RustleApi(env.http), scope) }
    // Opened from a rustlesearch.dev link: its search, or the chat around its message.
    val link = remember(env.argument) { env.argument?.let(::rustleLinkOf) }
    val linked = (link as? RustleLink.Search)?.query
    var username by remember {
        mutableStateOf(linked?.username ?: (link as? RustleLink.Around)?.message?.username ?: env.argument.orEmpty())
    }
    var words by remember { mutableStateOf(linked?.text.orEmpty()) }
    var channel by remember { mutableStateOf(linked?.channel ?: (link as? RustleLink.Around)?.message?.channel ?: "Destinygg") }
    var start by remember { mutableStateOf(linked?.startDate) }
    var end by remember { mutableStateOf(linked?.endDate) }
    // Times as the site shows them by default: the phone's own; or UTC.
    var local by rememberSaveable { mutableStateOf(true) }
    val uri = LocalUriHandler.current
    var picking by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()

    // The form folds to one line once a search has run, leaving the room to the results.
    var editing by remember { mutableStateOf(link == null && env.argument.isNullOrBlank()) }
    fun send() {
        keyboard?.hide()
        search.search(RustleQuery(username, words, channel, start, end))
        if (search.query != null && search.error == null) editing = false
    }
    fun searchUser(nick: String) {
        username = nick
        words = ""
        send()
    }

    // Opened for someone (a name in chat) or from a link: straight away.
    LaunchedEffect(Unit) {
        when (link) {
            is RustleLink.Around -> search.openAround(link.message)
            else -> if (!env.argument.isNullOrBlank()) send()
        }
    }
    // A new search, or the next set past the cap, starts at the top.
    LaunchedEffect(search.generation) { list.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chat log search", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Credit()
            }
            TextButton(onClick = { runCatching { uri.openUri(RustleQuery(username, words, channel, start, end).siteLink()) } }) {
                Text("Open on site", color = Palette.Blue, fontSize = 13.sp)
            }
            TextButton(onClick = env.onClose) { Text("Close", color = Palette.TextDim) }
        }
        if (!editing && search.query != null) {
            val dates = when {
                start == null -> "any date"
                start == end -> "on $start"
                else -> "$start to ${end ?: "today"}"
            }
            Row(
                Modifier.fillMaxWidth().clickable { editing = true }.background(Palette.Navy)
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOfNotNull(username.trim().ifBlank { null }, words.trim().ifBlank { null }?.let { "\"$it\"" }, channel, dates)
                        .joinToString(" · "),
                    color = Palette.White,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { local = !local }) { Text(if (local) "Local time" else "UTC", color = Palette.Blue, fontSize = 13.sp) }
                TextButton(onClick = { editing = true }) { Text("Edit", color = Palette.Blue, fontSize = 13.sp) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(username, { username = it }, "Name", Modifier.weight(1f), ::send)
                Field(words, { words = it }, "Words (a|b for either)", Modifier.weight(1.4f), ::send)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                ChannelPicker(channel, search) { channel = it }
                TextButton(onClick = { local = !local }) {
                    Text(if (local) "Local time" else "UTC", color = Palette.Blue, fontSize = 13.sp)
                }
                Box(Modifier.weight(1f))
                Button(onClick = ::send, enabled = !search.loading, modifier = Modifier.padding(end = 8.dp)) { Text("Search") }
            }
            // The site's quick ranges, and any other by picking the days.
            val now = remember { Clock.System.now().toEpochMilliseconds() }
            val ranges = remember(now) { DateRange.entries.associateWith { dateRange(it, now) } }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((range, dates) in ranges) {
                    Chip(range.name, start == dates.first && end == dates.second) { start = dates.first; end = dates.second }
                }
                Chip("All", start == null) { start = null; end = null }
                Chip("Dates…", start != null && ranges.values.none { it.first == start && it.second == end }) { picking = true }
            }
            start?.let { from ->
                Text(
                    if (from == end) "On $from (UTC)" else "From $from to ${end ?: "today"} (UTC)",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                )
            }
        }
        Status(search)
        Results(env, search, list, local, ::searchUser)
    }

    if (picking) DateRange(
        onPick = { from, to -> start = from; end = to; picking = false },
        onDismiss = { picking = false },
    )
    search.context?.let { context ->
        ContextSheet(
            env, context, local,
            onSite = { runCatching { uri.openUri(context.message.siteLink()) } },
            onClose = search::closeContext,
            onNick = { search.closeContext(); searchUser(it) },
        )
    }
}

/** At most this many messages are kept; past it the next ones replace them. */
internal const val MAX_KEPT = 5 * RustleApi.PAGE

/** Below this many requests left, the screen says how many. */
private const val LOW_QUOTA = 20

internal class SearchState(
    private val api: RustleSource,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    var results by mutableStateOf<List<LogMessage>>(emptyList())
        private set
    /** The search the results are for; null before the first. */
    var query by mutableStateOf<RustleQuery?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    var quota by mutableStateOf<Quota?>(null)
        private set
    /** When rustlesearch's quota refills, after a 429; no requests go out before then. */
    var limitedUntil by mutableStateOf<Long?>(null)
        private set
    /** Bumped when the list is replaced, so it scrolls to the top. */
    var generation by mutableIntStateOf(0)
        private set
    var context by mutableStateOf<ContextState?>(null)
        private set
    var channels by mutableStateOf(listOf("Destinygg"))
        private set

    private var job: Job? = null
    private var channelsAsked = false

    val capped: Boolean get() = results.size >= MAX_KEPT

    fun search(q: RustleQuery) {
        if (q.username.isBlank() && q.text.isBlank()) {
            error = "Enter a name, some words, or both."
            return
        }
        job?.cancel()
        query = q
        results = emptyList()
        hasMore = false
        generation++
        fetch(q, after = null, replace = true)
    }

    /** The next page, from where the list ends. Past [MAX_KEPT] it replaces the list. */
    fun more() {
        val q = query ?: return
        if (loading || !hasMore) return
        fetch(q, after = results.lastOrNull()?.searchAfter, replace = capped)
    }

    private fun fetch(q: RustleQuery, after: Long?, replace: Boolean) {
        if (blocked()) return
        loading = true
        error = null
        job = scope.launch {
            val reply = api.search(q, after)
            loading = false
            noteQuota(reply)
            when (reply) {
                is RustleReply.Ok -> {
                    val page = reply.value
                    if (replace) {
                        results = page
                        if (after != null) generation++
                    } else {
                        results = results + page
                    }
                    hasMore = page.size >= RustleApi.PAGE
                }
                is RustleReply.Failed -> error = reply.message
                is RustleReply.Limited -> Unit
            }
        }
    }

    fun open(message: LogMessage) {
        if (blocked()) return
        val state = ContextState(message)
        context = state
        scope.launch {
            val reply = api.surrounds(message)
            noteQuota(reply)
            when (reply) {
                is RustleReply.Ok -> state.surrounds = reply.value
                is RustleReply.Failed -> state.error = reply.message
                is RustleReply.Limited -> state.error = limitText()
            }
        }
    }

    /**
     * The chat around [message], which may be known only to the second (the
     * site's mentions, for one): rustlesearch wants the exact millisecond, so
     * its messages up to the end of that second are looked up first.
     */
    fun openAround(message: LogMessage) {
        if ('.' in message.ts || blocked()) return open(message)
        scope.launch {
            val second = message.at / 1000
            val reply = api.search(RustleQuery(message.username, "", message.channel), searchAfter = (second + 1) * 1000)
            noteQuota(reply)
            val found = (reply as? RustleReply.Ok)?.value?.firstOrNull { it.at / 1000 == second }
            open(found ?: message)
        }
    }

    fun closeContext() {
        context = null
    }

    /** Asks for the channel list the first time the picker opens. */
    fun loadChannels() {
        if (channelsAsked || blocked()) return
        channelsAsked = true
        scope.launch {
            val reply = api.channels()
            noteQuota(reply)
            if (reply is RustleReply.Ok && reply.value.isNotEmpty()) channels = reply.value else channelsAsked = false
        }
    }

    private fun noteQuota(reply: RustleReply<*>) {
        reply.quota?.let { quota = it }
        if (reply is RustleReply.Limited) {
            // Wait for the refill the server names, or an hour when it names none.
            val wait = reply.quota?.resetSeconds?.takeIf { it > 0 } ?: 3600L
            limitedUntil = now() + wait * 1000
        }
    }

    /** Whether rustlesearch has said no more for now. */
    private fun blocked(): Boolean {
        val until = limitedUntil ?: return false
        if (now() >= until) {
            limitedUntil = null
            return false
        }
        error = null
        return true
    }

    fun limitText(): String {
        val until = limitedUntil ?: return "rustlesearch.dev's daily limit is reached."
        val hours = ((until - now()) / 3_600_000L) + 1
        return "rustlesearch.dev's daily limit is reached. Try again in about $hours h."
    }
}

internal class ContextState(val message: LogMessage) {
    var surrounds by mutableStateOf<Surrounds?>(null)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun Credit() {
    val text = remember {
        buildAnnotatedString {
            append("Search by ")
            withLink(LinkAnnotation.Url(RustleApi.SITE, TextLinkStyles(SpanStyle(color = Palette.Blue)))) { append("rustlesearch.dev") }
        }
    }
    Text(text, color = Palette.TextDim, fontSize = 12.sp)
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier, onSearch: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier,
    )
}

@Composable
private fun ChannelPicker(channel: String, search: SearchState, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true; search.loadChannels() }) { Text(channel, color = Palette.Blue, fontSize = 13.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (c in search.channels) DropdownMenuItem(text = { Text(c) }, onClick = { onPick(c); open = false })
        }
    }
}

@Composable
private fun Status(search: SearchState) {
    val limited = search.limitedUntil != null
    val quota = search.quota
    val text = when {
        limited -> search.limitText()
        search.error != null -> search.error
        quota != null && quota.remaining <= LOW_QUOTA -> "${quota.remaining} of ${quota.limit} requests left today on rustlesearch.dev"
        else -> null
    } ?: return
    Text(
        text,
        color = if (limited || search.error != null) Palette.Orange else Palette.TextDim,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A result as shown: a day heading or a chat line. */
private sealed interface Item {
    data class Day(val label: String) : Item
    data class Line(val message: LogMessage, val line: ChatLine, val continued: Boolean, val time: String) : Item
}

private fun utc(at: Long): String = Instant.fromEpochMilliseconds(at).toString()

/** `2026-11-23T03:23:32…` in the phone's time ([local]) or UTC. */
private fun stamp(at: Long, local: Boolean): String = utc(if (local) at + localOffsetMillis(at) else at)

/** A small toggle, as the site's range buttons. */
@Composable
private fun Chip(label: String, picked: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (picked) Palette.White else Palette.TextDim,
        fontSize = 13.sp,
        maxLines = 1,
        modifier = Modifier.heightIn(min = 36.dp)
            .background(if (picked) Palette.NavyLight else Palette.Navy, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}

/** As chat draws a log line: no flairs are logged, and every emote shows, as the sender's tier isn't known. */
private fun chatLine(nick: String, text: String, at: Long) = ChatLine.user(nick, emptyList(), text, at, subTier = Int.MAX_VALUE)

/**
 * The results split by day: a short heading for each day, kept at the top
 * while its messages scroll, and only the time on each message. Results are
 * often far apart, so chat's runs of one sender (the name left out after the
 * first) aren't used here.
 */
private fun items(results: List<LogMessage>, local: Boolean): List<Item> {
    val out = ArrayList<Item>(results.size + 8)
    var day: String? = null
    for (message in results) {
        val stamp = stamp(message.at, local)
        val date = stamp.take(10)
        if (date != day) {
            out += Item.Day(dayLabel(date))
            day = date
        }
        out += Item.Line(message, chatLine(message.username, message.text, message.at), continued = false, time = stamp.substring(11, 16))
    }
    return out
}

@Composable
private fun DayDivider(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(Modifier.weight(1f), color = Palette.Navy)
        Text(
            label,
            color = Palette.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 10.dp)
                .background(Palette.Navy, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
        HorizontalDivider(Modifier.weight(1f), color = Palette.Navy)
    }
}

private val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** `2026-11-23` as "Mon 23 Nov 2026". */
internal fun dayLabel(date: String): String {
    val (y, m, d) = date.split('-').map { it.toInt() }
    val days = Instant.parse("${date}T00:00:00Z").toEpochMilliseconds() / 86_400_000L
    // 1 January 1970 was a Thursday.
    val weekday = weekdays[((days + 3) % 7 + 7).toInt() % 7]
    return "$weekday $d ${monthNames[m - 1]} $y"
}

@Composable
private fun Results(env: CommunityEnv, search: SearchState, list: LazyListState, local: Boolean, onNick: (String) -> Unit) {
    val shown = remember(search.results, local) { items(search.results, local) }
    val me = env.account?.nick
    val mention = remember(me) { me?.let(::mentionPattern) }
    // Near the end, the next page (unless the cap is reached: then it waits for a tap).
    LaunchedEffect(list, search) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 10
        }.distinctUntilChanged().filter { it }.collect { if (!search.capped) search.more() }
    }
    when {
        search.query == null -> Hint(
            "Search by name, words or both, newest first. Tap a line to see the chat around it.\n\n" +
                "In names and words: a | b for either, a + b for both, \"a phrase\" for exact words, " +
                "-word to leave it out, and ( ) to group.",
        )
        search.results.isEmpty() && search.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Blue)
        }
        search.results.isEmpty() && search.error == null && search.limitedUntil == null -> Hint("No messages found.")
    }
    if (search.results.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        shown.forEachIndexed { i, item ->
            when (item) {
                // The day between the sections, in the middle as messaging apps put it.
                is Item.Day -> item(key = "day$i", contentType = "day") { DayDivider(item.label) }
                is Item.Line -> item(key = i, contentType = "line") {
                    LogRow(env, item.line, item.continued, item.time, false, me, mention, onNick) { search.open(item.message) }
                }
            }
        }
        item(key = "end") {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                when {
                    search.loading -> CircularProgressIndicator(color = Palette.Blue)
                    search.hasMore && search.capped -> TextButton(onClick = search::more) { Text("Older messages", color = Palette.Blue) }
                    !search.hasMore -> Text("No older messages", color = Palette.TextDim, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
}

@Composable
private fun LogRow(
    env: CommunityEnv,
    line: ChatLine,
    continued: Boolean,
    time: String,
    marked: Boolean,
    me: String?,
    mention: Regex?,
    onNick: (String) -> Unit,
    stampWidth: Dp = 36.dp,
    onTap: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (marked) Modifier.background(Palette.Orange.copy(alpha = 0.14f)) else Modifier)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
    ) {
        // On the line's first baseline, beside its text rather than above an emote's middle.
        Text(time, color = Palette.TextDim, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 10.dp).width(stampWidth).alignByBaseline())
        Box(Modifier.weight(1f).alignByBaseline()) {
            ChatRow(line, continued, env.flairs, env.emotes, me, mention, false, env.onWatch, onNick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(
    env: CommunityEnv,
    context: ContextState,
    local: Boolean,
    onSite: () -> Unit,
    onClose: () -> Unit,
    onNick: (String) -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Palette.Black) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Around ${context.message.username}'s message, ${stamp(context.message.at, local).take(16).replace('T', ' ')}" +
                        if (local) "" else " UTC",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSite) { Text("Open on site", color = Palette.Blue, fontSize = 13.sp) }
            }
            val surrounds = context.surrounds
            val error = context.error
            when {
                error != null -> Text(error, color = Palette.Orange, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                surrounds == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Blue)
                }
                surrounds.lines.isEmpty() -> Hint("Nothing logged around it.")
                else -> {
                    val lines = remember(surrounds) {
                        surrounds.lines.map { chatLine(it.nick, it.text, it.at) to stamp(it.at, local).substring(11, 16) }
                    }
                    val me = env.account?.nick
                    val mention = remember(me) { me?.let(::mentionPattern) }
                    // Open with the message a few lines from the top.
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((surrounds.matched ?: 0) - 4).coerceAtLeast(0))
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        itemsIndexed(lines, key = { i, _ -> i }) { i, (line, time) ->
                            val continued = i > 0 && continues(lines[i - 1].first, line)
                            LogRow(env, line, continued, time, i == surrounds.matched, me, mention, onNick, onTap = null)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRange(onPick: (String?, String?) -> Unit, onDismiss: () -> Unit) {
    val today = remember { Clock.System.now().toEpochMilliseconds() }
    val state = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= today
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val from = state.selectedStartDateMillis?.let { utc(it).take(10) }
                    // One day picked: that day.
                    val to = state.selectedEndDateMillis?.let { utc(it).take(10) } ?: from
                    onPick(from, to)
                },
                enabled = state.selectedStartDateMillis != null,
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DateRangePicker(state, modifier = Modifier.weight(1f), title = null)
    }
}
