package io.github.strawwaffle.glorp.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.ui.Palette
import io.github.strawwaffle.glorp.ui.mentionPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** What the mentions screen has loaded for one name, page by page from polecat.me. */
private class MentionsState(
    private val polecat: Polecat,
    private val scope: CoroutineScope,
    val nick: String,
    /** A message a link pointed at: pages load until it's found, up to [SEEK_PAGES]. */
    val seek: Long? = null,
) {
    var lines by mutableStateOf<List<PolecatLine>>(emptyList())
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var ended by mutableStateOf(false)
    private var job: Job? = null

    fun more() {
        if (loading || ended || nick.isBlank()) return
        loading = true
        failed = false
        job = scope.launch {
            val page = polecat.mentions(nick, Polecat.PAGE, lines.size)
            loading = false
            when {
                page == null -> failed = true
                page.isEmpty() -> ended = true
                else -> {
                    val known = lines.mapTo(HashSet()) { it.id }
                    lines = lines + page.filter { it.id !in known }
                    if (page.size < Polecat.PAGE) ended = true
                    if (seek != null && lines.none { it.id == seek } && !ended && lines.size < SEEK_PAGES * Polecat.PAGE) more()
                }
            }
        }
    }

    companion object {
        const val SEEK_PAGES = 10
    }
}

/**
 * Everything said to someone (you, unless another name is typed), newest
 * first, from polecat.me's chat logger: it keeps every message that names
 * someone, back to December 2025. Tapping a line opens the chat around it.
 */
@Composable
fun MentionsScreen(env: CommunityEnv) {
    val scope = rememberCoroutineScope()
    val polecat = remember(env.http) { Polecat(env.http) }
    // Opened from a polecat.me link: its name, and the message it points at.
    val link = remember(env.argument) { env.argument?.let(::polecatLinkOf) }
    var typed by remember { mutableStateOf(link?.nick ?: env.argument ?: env.account?.nick.orEmpty()) }
    var shown by remember { mutableStateOf(typed.trim()) }
    val state = remember(shown) { MentionsState(polecat, scope, shown, link?.message?.takeIf { shown.equals(link.nick, ignoreCase = true) }) }
    val list = rememberLazyListState()
    val uri = LocalUriHandler.current
    val me = env.account?.nick
    val mention = remember(shown) { shown.takeIf { it.isNotBlank() }?.let(::mentionPattern) }

    LaunchedEffect(state) { state.more() }
    // Near the end, the next page.
    LaunchedEffect(list, state) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 10
        }.distinctUntilChanged().filter { it }.collect { state.more() }
    }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Mentions", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("From polecat.me's chat logs", color = Palette.TextDim, fontSize = 12.sp)
            }
            TextButton(onClick = { runCatching { uri.openUri("${Polecat.BASE}/mentions") } }) {
                Text("Open on site", color = Palette.Blue, fontSize = 13.sp)
            }
            TextButton(onClick = env.onClose) { Text("Close", color = Palette.TextDim) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                typed, { typed = it },
                label = { Text("Name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { shown = typed.trim() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { shown = typed.trim() }) { Text("Show", color = Palette.Blue) }
        }
        // The linked message, once loaded: scrolled to and marked.
        val sought = state.seek
        val items = remember(state.lines) {
            buildList {
                var day: String? = null
                for (m in state.lines) {
                    val stamp = stamp(m.at, local = true)
                    val date = stamp.take(10)
                    if (date != day) { add(date); day = date }
                    add(m)
                }
            }
        }
        when {
            shown.isBlank() -> Hint("Type a name to see the messages that mention it.")
            state.lines.isEmpty() && state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Blue)
            }
            state.lines.isEmpty() && state.failed -> Retry(state)
            state.lines.isEmpty() && state.ended -> Hint("No mentions of $shown.")
        }
        if (state.lines.isEmpty()) return@Column
        val soughtIndex = if (sought == null) -1 else items.indexOfFirst { it is PolecatLine && it.id == sought }
        LaunchedEffect(soughtIndex) { if (soughtIndex >= 0) list.scrollToItem(maxOf(0, soughtIndex - 2)) }
        if (sought != null && soughtIndex < 0 && !state.loading && (state.ended || state.lines.size >= MentionsState.SEEK_PAGES * Polecat.PAGE)) {
            Hint("The linked message wasn't found in the newest ${state.lines.size}; it may be older.")
        }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            items.forEachIndexed { i, item ->
                when (item) {
                    is String -> item(key = "day$i", contentType = "day") { DayDivider(dayLabel(item)) }
                    is PolecatLine -> item(key = item.id, contentType = "line") {
                        val line = remember(item) { ChatLine.user(item.nick, item.flairs, item.text, item.at, subTier = Int.MAX_VALUE) }
                        LogRow(env, line, false, stamp(item.at, local = true).substring(11, 16), item.id == sought, me, mention, onNick = { env.onOpen("mentions", it) }) {
                            env.onOpen("rustle", LogMessage("Destinygg", item.text, Instant.fromEpochMilliseconds(item.at).toString(), item.nick, item.at).siteLink())
                        }
                    }
                }
            }
            item(key = "end") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator(color = Palette.Blue)
                        state.failed -> Retry(state)
                        state.ended -> Text("No older mentions", color = Palette.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Retry(state: MentionsState) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("polecat.me isn't answering right now, or asked to wait a minute.", color = Palette.TextDim, fontSize = 13.sp)
        TextButton(onClick = state::more) { Text("Try again", color = Palette.Blue) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
}
