package io.github.strawwaffle.glorp.community

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.mediaOf
import io.github.strawwaffle.glorp.ui.EmoteImage
import io.github.strawwaffle.glorp.ui.LocalLinkCard
import io.github.strawwaffle.glorp.ui.Palette
import io.github.strawwaffle.glorp.ui.mentionPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** The feed for one set of filters, page by page. */
private class LinksState(private val api: Linkers, private val scope: CoroutineScope, val query: LinksQuery) {
    var posts by mutableStateOf<List<LinkPost>>(emptyList())
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var ended by mutableStateOf(false)
    private var next = 0

    fun more() {
        if (loading || ended) return
        loading = true
        failed = false
        scope.launch {
            val page = api.page(query, next)
            loading = false
            if (page == null) { failed = true; return@launch }
            // Each post once: a page can repeat one from an earlier page (new links shift
            // the offsets) or even list it twice itself, and the list keys posts by id.
            val known = posts.mapTo(HashSet()) { it.id }
            posts = posts + page.posts.filter { known.add(it.id) }
            next = page.next
            if (!page.hasMore || page.posts.isEmpty()) ended = true
        }
    }
}

private val kinds = listOf("image" to "Pictures", "video" to "Videos", "youtube" to "YouTube", "twitter" to "X", "reddit" to "Reddit", "imgur" to "Imgur", "audio" to "Audio")
private val timeframes = listOf("6h" to "6 hours", "12h" to "12 hours", "1d" to "Day", "1w" to "Week", "2w" to "2 weeks", "1m" to "Month")

/**
 * The links and pictures posted in chat, newest first or most reacted to,
 * from Pizza's linkers.ooo: pictures shown, YouTube ready to play here, and
 * the replies and emotes each got. Tapping a name shows only their posts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinksScreen(env: CommunityEnv) {
    val scope = rememberCoroutineScope()
    val api = remember(env.http) { Linkers(env.http) }
    var query by remember { mutableStateOf(LinksQuery(user = env.argument?.takeIf { it.isNotBlank() })) }
    val state = remember(query) { LinksState(api, scope, query) }
    // A new set of filters starts at the top of its own list.
    val list = remember(query) { LazyListState() }
    val uri = LocalUriHandler.current
    val me = env.account?.nick
    val mention = remember(me) { me?.let(::mentionPattern) }
    var reactionsFor by remember { mutableStateOf<LinkPost?>(null) }

    LaunchedEffect(state) { state.more() }
    LaunchedEffect(list, state) {
        snapshotFlow {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 5
        }.distinctUntilChanged().filter { it }.collect { if (!state.failed) state.more() }
    }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Links", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Credit()
            }
            TextButton(onClick = { runCatching { uri.openUri(Linkers.BASE) } }) { Text("Open on site", color = Palette.Blue, fontSize = 13.sp) }
            TextButton(onClick = env.onClose) { Text("Close", color = Palette.TextDim) }
        }
        Chips {
            Chip("Newest", query.sort == "newest") { query = query.copy(sort = "newest") }
            Chip("Most reactions", query.sort == "reactions") { query = query.copy(sort = "reactions") }
            Chip("NSFW too", query.nsfw == "include") { query = query.copy(nsfw = if (query.nsfw == "include") "exclude" else "include") }
            query.user?.let { Chip("Only $it  ✕", true) { query = query.copy(user = null) } }
        }
        if (query.sort == "reactions") Chips {
            for ((key, label) in timeframes) Chip(label, query.timeframe == key) { query = query.copy(timeframe = key) }
        }
        Chips {
            Chip("All", query.kinds.isEmpty()) { query = query.copy(kinds = emptySet()) }
            for ((key, label) in kinds) Chip(label, key in query.kinds) {
                query = query.copy(kinds = if (key in query.kinds) query.kinds - key else query.kinds + key)
            }
        }
        when {
            state.posts.isEmpty() && state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Blue)
            }
            state.posts.isEmpty() && state.failed -> Retry(api, state)
            state.posts.isEmpty() && state.ended -> Hint("Nothing posted that fits these filters.")
        }
        if (state.posts.isEmpty()) return@Column
        val newest = query.sort == "newest"
        LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp), state = list) {
            var day: String? = null
            state.posts.forEachIndexed { i, post ->
                // Newest first, by day as the other logs; most reacted to, with how long ago.
                if (newest) {
                    val date = stamp(post.at, local = true).take(10)
                    if (date != day) {
                        day = date
                        item(key = "day$i", contentType = "day") { DayDivider(dayLabel(date)) }
                    }
                }
                item(key = post.id, contentType = "post") {
                    Post(env, post, if (newest) stamp(post.at, local = true).substring(11, 16) else ago(post.at), me, mention,
                        onNick = { query = query.copy(user = it) },
                        onReactions = { reactionsFor = post })
                }
            }
            item(key = "end") {
                Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator(color = Palette.Blue)
                        state.failed -> Retry(api, state)
                        state.ended -> Text("No older links", color = Palette.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
    reactionsFor?.let { post -> ReactionsSheet(env, api, post, me, mention) { reactionsFor = null } }
}

@Composable
private fun Chips(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** "5m", "3h" or "2d" ago. */
private fun ago(at: Long): String {
    val minutes = ((Clock.System.now().toEpochMilliseconds() - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 48 * 60 -> "${minutes / 60}h"
        else -> "${minutes / (24 * 60)}d"
    }
}

private fun chatLine(nick: String, flairs: List<String>, text: String, at: Long) =
    ChatLine.user(nick, flairs, text, at, subTier = Int.MAX_VALUE)

/** Room the time takes at the start of a row: media and reactions line up with the message after it. */
private val TEXT_START = 46.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Post(
    env: CommunityEnv,
    post: LinkPost,
    time: String,
    me: String?,
    mention: Regex?,
    onNick: (String) -> Unit,
    onReactions: () -> Unit,
) {
    val linkCard = LocalLinkCard.current
    val uri = LocalUriHandler.current
    val line = remember(post) { chatLine(post.nick, post.flairs, post.message, post.at) }
    val open = { if (!linkCard(post.url, post.message)) runCatching { uri.openUri(post.url) } }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        LogRow(env, line, false, time, false, me, mention, onNick, onTap = open)
        Media(env, post, open)
        if (post.reactions.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = TEXT_START, end = 12.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (r in post.reactions) Reaction(env, r, onReactions)
            }
        }
    }
}

/** A picture as posted, or a YouTube video's thumbnail to play here; other kinds are the link in the message. */
@Composable
private fun Media(env: CommunityEnv, post: LinkPost, open: () -> Unit) {
    var shown by remember(post.id) { mutableStateOf(!post.nsfw) }
    var failed by remember(post.id) { mutableStateOf(false) }
    val youtube = remember(post.url) { mediaOf(post.url)?.takeIf { it.platform == "youtube" }?.key?.removePrefix("youtube:") }
    val picture = when {
        post.kind == "image" -> post.url
        youtube != null -> "https://i.ytimg.com/vi/$youtube/hqdefault.jpg"
        else -> null
    } ?: return
    if (failed) return
    val box = Modifier.padding(start = TEXT_START, end = 12.dp, top = 4.dp).clip(RoundedCornerShape(8.dp))
    if (!shown) {
        Text(
            "Marked NSFW. Tap to show.",
            color = Palette.Orange,
            fontSize = 13.sp,
            modifier = box.fillMaxWidth().background(Palette.Navy).clickable { shown = true }.padding(12.dp),
        )
        return
    }
    Box(box.clickable { if (youtube != null) env.onWatch(Embed("youtube", youtube)) else open() }) {
        AsyncImage(
            picture,
            contentDescription = if (youtube != null) "YouTube video" else "Picture posted by ${post.nick}",
            contentScale = if (youtube != null) ContentScale.Crop else ContentScale.Fit,
            onError = { failed = true },
            modifier = if (youtube != null) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.fillMaxWidth().heightIn(max = 300.dp),
        )
        if (youtube != null) {
            Text(
                "▶",
                color = Palette.White,
                fontSize = 22.sp,
                modifier = Modifier.align(Alignment.Center).background(Palette.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

/** An emote (or @ for replies) and how many used it on the post. */
@Composable
private fun Reaction(env: CommunityEnv, reaction: LinkReaction, onClick: () -> Unit) {
    Row(
        Modifier.background(Palette.Navy, RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val emote = env.emotes[reaction.name]
        if (emote != null) {
            val scale = 20f / emote.height.coerceAtLeast(1)
            EmoteImage(emote, sentAt = 0, modifier = Modifier.size((emote.width * scale).dp, (emote.height * scale).dp))
        } else {
            Text(reaction.name, color = Palette.White, fontSize = 13.sp)
        }
        Text(reaction.count.toString(), color = Palette.TextDim, fontSize = 12.sp)
    }
}

/** The messages that reacted to [post], oldest first, as they were in chat. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReactionsSheet(env: CommunityEnv, api: Linkers, post: LinkPost, me: String?, mention: Regex?, onClose: () -> Unit) {
    var messages by remember(post.id) { mutableStateOf<List<ReactionMessage>?>(null) }
    var failed by remember(post.id) { mutableStateOf(false) }
    LaunchedEffect(post.id) {
        val got = api.reactions(post)
        if (got == null) failed = true else messages = got.sortedBy { it.at }
    }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Black) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            Text(
                "Reactions to ${post.nick}'s link",
                color = Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val got = messages
            when {
                failed -> Hint("linkers.ooo isn't answering right now, or asked to wait a minute.")
                got == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Blue)
                }
                got.isEmpty() -> Hint("No messages logged for these reactions.")
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(got, key = { i, _ -> i }) { _, m ->
                        val line = remember(m) { chatLine(m.nick, m.flairs, m.text, m.at) }
                        LogRow(env, line, false, stamp(m.at, local = true).substring(11, 16), false, me, mention, onNick = {}, onTap = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun Credit() {
    val text = remember {
        buildAnnotatedString {
            append("From Pizza's ")
            withLink(LinkAnnotation.Url(Linkers.BASE, TextLinkStyles(SpanStyle(color = Palette.Blue)))) { append("linkers.ooo") }
        }
    }
    Text(text, color = Palette.TextDim, fontSize = 12.sp)
}

@Composable
private fun Retry(api: Linkers, state: LinksState) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            if (api.resting) "linkers.ooo asked to wait a minute." else "linkers.ooo isn't answering right now.",
            color = Palette.TextDim,
            fontSize = 13.sp,
        )
        TextButton(onClick = state::more) { Text("Try again", color = Palette.Blue) }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
}
