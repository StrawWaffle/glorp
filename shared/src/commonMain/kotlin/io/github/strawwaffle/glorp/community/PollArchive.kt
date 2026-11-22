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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.Segment
import io.github.strawwaffle.glorp.chat.segments
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.ui.EmoteImage
import io.github.strawwaffle.glorp.ui.OnBack
import io.github.strawwaffle.glorp.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.round
import kotlin.time.Clock

/**
 * destiny.gg chat's polls, from Mitchdev's archive at mitchdev.net: newest
 * first, searchable, filtered by who started them. Fetches only while open.
 * Opened with a numeric argument it shows that poll; with a name, that
 * person's polls.
 */
@Composable
fun PollArchiveScreen(env: CommunityEnv) {
    val scope = rememberCoroutineScope()
    val api = remember(env.http) { PollApi(env.http) }
    val feed = remember(api) { PollFeed(api, scope) }
    val listState = rememberLazyListState()
    var detail by remember { mutableStateOf<Poll?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        PollSession.load(api, scope)
        val arg = env.argument?.trim().orEmpty()
        val id = arg.toLongOrNull()
        feed.show(PollQuery(startedBy = arg.takeIf { it.isNotEmpty() && id == null }))
        if (id != null) {
            runCatching { api.poll(id) }
                .onSuccess { detail = it }
                .onFailure { detailError = "Couldn't load poll $id" }
        }
    }

    OnBack(enabled = detail != null) { detail = null }

    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        val shown = detail
        if (shown != null) {
            PollDetail(
                shown,
                env,
                onBack = { detail = null },
                onStarter = { nick ->
                    detail = null
                    feed.show(feed.query.copy(startedBy = nick))
                },
            )
        } else {
            PollList(feed, listState, env, detailError, onOpen = { detail = it })
        }
    }
}

@Composable
private fun PollList(feed: PollFeed, listState: LazyListState, env: CommunityEnv, notice: String?, onOpen: (Poll) -> Unit) {
    // Load the next page when the reader is within a few cards of the end.
    LaunchedEffect(feed, listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { near -> if (near) feed.loadMore() }
    }
    // Back to the top whenever the query changes.
    LaunchedEffect(feed.query) { listState.scrollToItem(0) }

    Header(env)
    Filters(feed)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        if (notice != null) item { Dim(notice, Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        if (feed.trimmed) {
            item(key = "top") {
                Text(
                    "Earlier results were let go to save memory. Start over",
                    color = Palette.Blue,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().clickable { feed.restart() }.padding(12.dp),
                )
            }
        }
        items(feed.polls, key = { it.id }) { poll ->
            PollCard(poll, env, Modifier.clickable { onOpen(poll) })
        }
        item(key = "end") {
            val error = feed.error
            when {
                error != null -> Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Dim(error)
                    Text("Try again", color = Palette.Blue, fontSize = 14.sp, modifier = Modifier.clickable { feed.loadMore() }.padding(8.dp))
                }
                feed.loading -> Dim("Loading polls…", Modifier.fillMaxWidth().padding(16.dp))
                feed.done && feed.polls.isEmpty() -> Dim("No polls found.", Modifier.fillMaxWidth().padding(16.dp))
                feed.done -> Dim("That's every poll.", Modifier.fillMaxWidth().padding(16.dp))
                else -> Spacer(Modifier.height(48.dp))
            }
        }
    }
}

private val linkStyle = TextLinkStyles(SpanStyle(color = Palette.Blue, textDecoration = TextDecoration.Underline))

/** The credit, and YEE vs PEPE when it has come in. */
@Composable
private fun Header(env: CommunityEnv) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
        Text("Chat polls", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(
            buildAnnotatedString {
                append("Poll archive by ")
                withLink(LinkAnnotation.Url(PollApi.SITE, linkStyle)) { append("mitchdev.net") }
            },
            color = Palette.TextDim,
            fontSize = 12.sp,
        )
        PollSession.yeePepe?.let { yp ->
            val density = LocalDensity.current
            val rendered = remember(yp, env.emotes, density) {
                val line = RichText(env.emotes, density, sentAt = 0, onEmbed = env.onWatch)
                line.add("YEE ", SpanStyle(fontWeight = FontWeight.Bold))
                line.add(grouped(yp.yee), SpanStyle(color = Palette.Glorp))
                line.add("  vs  ", SpanStyle(color = Palette.TextDim))
                line.add("PEPE ", SpanStyle(fontWeight = FontWeight.Bold))
                line.add(grouped(yp.pepe), SpanStyle(color = Palette.Orange))
                line.add("  in every YEE vs PEPE poll", SpanStyle(color = Palette.TextDim, fontSize = 12.sp))
                line.build()
            }
            Text(rendered.text, inlineContent = rendered.inline, color = Palette.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Filters(feed: PollFeed) {
    var text by remember(feed) { mutableStateOf(feed.query.search) }
    val focus = LocalFocusManager.current
    // Typing searches once it pauses; Search on the keyboard searches at once.
    LaunchedEffect(text) {
        if (text.trim() == feed.query.search) return@LaunchedEffect
        delay(700)
        feed.show(feed.query.copy(search = text.trim()))
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Box(
            Modifier.fillMaxWidth().background(Palette.Navy, RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        ) {
            if (text.isEmpty()) Text("Search questions and options", color = Palette.TextDim, fontSize = 14.sp)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = Palette.White, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    feed.show(feed.query.copy(search = text.trim()))
                }),
                modifier = Modifier.fillMaxWidth().padding(end = if (text.isEmpty()) 0.dp else 28.dp),
            )
            if (text.isNotEmpty()) {
                Text(
                    "✕",
                    color = Palette.TextDim,
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).clickable {
                        text = ""
                        feed.show(feed.query.copy(search = ""))
                    },
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            StarterPicker(feed)
            Spacer(Modifier.weight(1f))
            Text(
                if (feed.query.oldestFirst) "Oldest first" else "Newest first",
                color = Palette.Blue,
                fontSize = 13.sp,
                modifier = Modifier.clickable { feed.show(feed.query.copy(oldestFirst = !feed.query.oldestFirst)) }.padding(6.dp),
            )
        }
        feed.total?.let { Dim(if (it == 1) "1 poll" else "${grouped(it)} polls", Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun StarterPicker(feed: PollFeed) {
    var open by remember { mutableStateOf(false) }
    Box {
        Text(
            "Started by: " + (feed.query.startedBy ?: "anyone"),
            color = Palette.Blue,
            fontSize = 13.sp,
            modifier = Modifier.clickable { open = true }.padding(vertical = 6.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            DropdownMenuItem(text = { Text("Anyone") }, onClick = {
                open = false
                feed.show(feed.query.copy(startedBy = null))
            })
            val makers = PollSession.makers
            if (makers == null) {
                DropdownMenuItem(text = { Text("Loading names…", color = Palette.TextDim) }, onClick = {}, enabled = false)
            }
            makers?.forEach { maker ->
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth()) {
                            Text(maker.name, modifier = Modifier.weight(1f))
                            Text(grouped(maker.count), color = Palette.TextDim, modifier = Modifier.padding(start = 16.dp))
                        }
                    },
                    onClick = {
                        open = false
                        feed.show(feed.query.copy(startedBy = maker.name))
                    },
                )
            }
        }
    }
}

@Composable
private fun PollCard(poll: Poll, env: CommunityEnv, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .background(Palette.Navy, RoundedCornerShape(6.dp)).padding(12.dp),
    ) {
        ChatText(poll.question, env, poll.startedAt ?: 0, fontSize = 15.sp, bold = true)
        Spacer(Modifier.height(8.dp))
        Results(poll, env)
        Spacer(Modifier.height(6.dp))
        Dim(summary(poll, Clock.System.now().toEpochMilliseconds()))
    }
}

/** "173 votes · Bing · 6 h ago · weighted by sub tier" */
private fun summary(poll: Poll, now: Long): String = buildList {
    add(if (poll.sum == 1) "1 vote" else "${grouped(poll.sum)} votes")
    if (poll.startedBy.isNotEmpty()) add(poll.startedBy)
    poll.startedAt?.let { add(whenText(it, now)) }
    if (poll.isWeighted) add("weighted by sub tier")
}.joinToString(" · ")

/** One bar per option, filled by its share of the votes; the winner in green. */
@Composable
private fun Results(poll: Poll, env: CommunityEnv) {
    val sum = poll.sum
    val winners = poll.winners
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        poll.options.forEachIndexed { i, option ->
            val votes = poll.votesFor(i)
            val share = if (sum > 0) votes.toFloat() / sum else 0f
            val won = i in winners
            Box(Modifier.fillMaxWidth().background(Palette.NavyLight.copy(alpha = 0.45f), RoundedCornerShape(3.dp))) {
                Box(Modifier.matchParentSize()) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f))
                            .background(if (won) Palette.Glorp.copy(alpha = 0.35f) else Palette.Blue.copy(alpha = 0.28f), RoundedCornerShape(3.dp)),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { ChatText(option, env, poll.startedAt ?: 0, fontSize = 14.sp, bold = won) }
                    Text(
                        "${grouped(votes)}  ${percent(share)}",
                        color = if (won) Palette.White else Palette.TextDim,
                        fontSize = 12.sp,
                        fontWeight = if (won) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

private fun percent(share: Float): String = "${round(share * 100).toInt()}%"

@Composable
private fun PollDetail(poll: Poll, env: CommunityEnv, onBack: () -> Unit, onStarter: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("‹ Polls", color = Palette.Blue, fontSize = 14.sp, modifier = Modifier.clickable(onClick = onBack).padding(12.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            ChatText(poll.question, env, poll.startedAt ?: 0, fontSize = 18.sp, bold = true)
            Spacer(Modifier.height(12.dp))
            Results(poll, env)
            Spacer(Modifier.height(14.dp))
            Fact("Votes", grouped(poll.sum))
            if (poll.isWeighted) Fact("Weighted", "Votes count by subscription tier")
            poll.startedAt?.let { Fact("Started", utcDate(it, withTime = true)) }
            if (poll.duration > 0) Fact("Ran for", duration(poll.duration))
            if (poll.startedBy.isNotEmpty()) {
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("Started by", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
                    Text(poll.startedBy, color = Palette.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    "More polls by ${poll.startedBy}",
                    color = Palette.Blue,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { onStarter(poll.startedBy) }.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                buildAnnotatedString {
                    append("Poll archive by ")
                    withLink(LinkAnnotation.Url(PollApi.SITE, linkStyle)) { append("mitchdev.net") }
                },
                color = Palette.TextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.width(96.dp))
        Text(value, color = Palette.White, fontSize = 13.sp)
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s < 60) "$s s" else "${s / 60} min" + (if (s % 60 != 0L) " ${s % 60} s" else "")
}

@Composable
private fun Dim(text: String, modifier: Modifier = Modifier) = Text(text, color = Palette.TextDim, fontSize = 12.sp, modifier = modifier)

/** Raw chat text, drawn as chat draws a message: emote words as emotes, links tappable. */
@Composable
private fun ChatText(text: String, env: CommunityEnv, sentAt: Long, fontSize: TextUnit, bold: Boolean) {
    val density = LocalDensity.current
    val rendered = remember(text, env.emotes, density, sentAt) {
        RichText(env.emotes, density, sentAt, env.onWatch).apply { addChat(text) }.build()
    }
    Text(
        rendered.text,
        inlineContent = rendered.inline,
        color = Palette.White,
        fontSize = fontSize,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        lineHeight = (fontSize.value + 6).sp,
    )
}

private class Rendered(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

/** Builds a line of text with emotes as inline pictures. */
private class RichText(
    private val emotes: Emotes,
    private val density: Density,
    /** When the text was sent: an animated emote plays only if that was moments ago. */
    private val sentAt: Long,
    private val onEmbed: (Embed) -> Unit,
) {
    private val builder = AnnotatedString.Builder()
    private val inline = HashMap<String, InlineTextContent>()

    fun add(text: String, style: SpanStyle) {
        // A word that names an emote (YEE, PEPE) is drawn as it.
        val emote = emotes[text.trim()]
        if (emote != null) {
            appendEmote(emote)
            builder.append(' ')
            return
        }
        builder.withStyle(style) { append(text) }
    }

    fun addChat(text: String) {
        // The archive doesn't say what the starter subscribes to, and chat
        // draws a poll's words as emotes whoever asked, so every emote counts.
        val line = ChatLine(id = "", kind = ChatLine.Kind.User, nick = null, features = listOf("bot"), text = text, at = sentAt, action = false, green = false)
        for (segment in segments(line, emotes)) when (segment) {
            is Segment.Text -> builder.append(segment.text)
            is Segment.Link -> builder.withLink(LinkAnnotation.Url(segment.url, linkStyle)) { append(segment.text) }
            is Segment.EmbedLink -> builder.withLink(LinkAnnotation.Clickable(segment.embed.key, linkStyle) { onEmbed(segment.embed) }) {
                append(segment.text)
            }
            is Segment.EmoteRef -> appendEmote(segment.emote)
        }
    }

    private fun appendEmote(emote: Emote) {
        val id = "emote:${emote.prefix}"
        inline.getOrPut(id) {
            val placeholder = with(density) { Placeholder(emote.width.dp.toSp(), emote.height.dp.toSp(), PlaceholderVerticalAlign.Center) }
            InlineTextContent(placeholder) { EmoteImage(emote, sentAt = sentAt) }
        }
        builder.appendInlineContent(id, emote.prefix)
    }

    fun build() = Rendered(builder.toAnnotatedString(), inline)
}
