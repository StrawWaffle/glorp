package io.github.strawwaffle.glorp.community

import androidx.compose.foundation.background
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
    var username by remember { mutableStateOf(env.argument.orEmpty()) }
    var words by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("Destinygg") }
    var start by remember { mutableStateOf<String?>(null) }
    var end by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val list = rememberLazyListState()

    fun send() {
        keyboard?.hide()
        search.search(RustleQuery(username, words, channel, start, end))
    }
    fun searchUser(nick: String) {
        username = nick
        words = ""
        send()
    }

    // Opened for someone (from a name in chat): search them straight away.
    LaunchedEffect(Unit) { if (!env.argument.isNullOrBlank()) send() }
    // A new search, or the next set past the cap, starts at the top.
    LaunchedEffect(search.generation) { list.scrollToItem(0) }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chat log search", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Credit()
            }
            TextButton(onClick = env.onClose) { Text("Close", color = Palette.TextDim) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(username, { username = it }, "Name", Modifier.weight(1f), ::send)
            Field(words, { words = it }, "Words (a|b for either)", Modifier.weight(1.4f), ::send)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelPicker(channel, search) { channel = it }
            TextButton(onClick = { picking = true }) {
                Text(if (start == null) "Any date" else if (start == end) start!! else "$start to $end", color = Palette.Blue, fontSize = 13.sp)
            }
            if (start != null) TextButton(onClick = { start = null; end = null }) { Text("Any date", color = Palette.TextDim, fontSize = 12.sp) }
            Box(Modifier.weight(1f))
            Button(onClick = ::send, enabled = !search.loading, modifier = Modifier.padding(end = 8.dp)) { Text("Search") }
        }
        Status(search)
        Results(env, search, list, ::searchUser)
    }

    if (picking) DateRange(
        onPick = { from, to -> start = from; end = to; picking = false },
        onDismiss = { picking = false },
    )
    search.context?.let { context ->
        ContextSheet(env, context, onClose = search::closeContext, onNick = { search.closeContext(); searchUser(it) })
    }
}

/** At most this many messages are kept; past it the next ones replace them. */
private const val MAX_KEPT = 5 * RustleApi.PAGE

/** Below this many requests left, the screen says how many. */
private const val LOW_QUOTA = 20

private class SearchState(private val api: RustleApi, private val scope: CoroutineScope) {
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

    private fun now() = Clock.System.now().toEpochMilliseconds()
}

private class ContextState(val message: LogMessage) {
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
    data class Day(val date: String) : Item
    data class Line(val message: LogMessage, val line: ChatLine, val continued: Boolean, val time: String) : Item
}

private fun utc(at: Long): String = Instant.fromEpochMilliseconds(at).toString()

/** As chat draws a log line: no flairs are logged, and every emote shows, as the sender's tier isn't known. */
private fun chatLine(nick: String, text: String, at: Long) = ChatLine.user(nick, emptyList(), text, at, subTier = Int.MAX_VALUE)

private fun items(results: List<LogMessage>): List<Item> {
    val out = ArrayList<Item>(results.size + 8)
    var day: String? = null
    var previous: ChatLine? = null
    for (message in results) {
        val stamp = utc(message.at)
        val date = stamp.take(10)
        if (date != day) {
            out += Item.Day(date)
            day = date
            previous = null
        }
        val line = chatLine(message.username, message.text, message.at)
        out += Item.Line(message, line, previous != null && continues(previous, line), stamp.substring(11, 16))
        previous = line
    }
    return out
}

@Composable
private fun Results(env: CommunityEnv, search: SearchState, list: LazyListState, onNick: (String) -> Unit) {
    val shown = remember(search.results) { items(search.results) }
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
        search.query == null -> Hint("Search by name, words or both, newest first. Times are UTC. Tap a line to see the chat around it.")
        search.results.isEmpty() && search.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Palette.Blue)
        }
        search.results.isEmpty() && search.error == null && search.limitedUntil == null -> Hint("No messages found.")
    }
    if (search.results.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        itemsIndexed(shown, key = { i, _ -> i }, contentType = { _, item -> item::class }) { _, item ->
            when (item) {
                is Item.Day -> Text(
                    item.date,
                    color = Palette.TextDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp),
                )
                is Item.Line -> LogRow(env, item.line, item.continued, item.time, false, me, mention, onNick) { search.open(item.message) }
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
    onTap: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Text(time, color = Palette.TextDim, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp, top = 4.dp).width(36.dp))
        Box(Modifier.weight(1f)) {
            ChatRow(line, continued, env.flairs, env.emotes, me, mention, marked, env.onWatch, onNick)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContextSheet(env: CommunityEnv, context: ContextState, onClose: () -> Unit, onNick: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = Palette.Black) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Text(
                "Around ${context.message.username}'s message, ${utc(context.message.at).take(16).replace('T', ' ')} UTC",
                color = Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                        surrounds.lines.map { chatLine(it.nick, it.text, it.at) to utc(it.at).substring(11, 16) }
                    }
                    val me = env.account?.nick
                    val mention = remember(me) { me?.let(::mentionPattern) }
                    // Open with the message a few lines from the top.
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = ((surrounds.matched ?: 0) - 4).coerceAtLeast(0))
                    LazyColumn(Modifier.fillMaxSize(), state = list) {
                        itemsIndexed(lines, key = { i, _ -> i }) { i, (line, time) ->
                            val continued = i > 0 && continues(lines[i - 1].first, line)
                            LogRow(env, line, continued, time, i == surrounds.matched, me, mention, onNick, null)
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
