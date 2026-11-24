package io.github.strawwaffle.glorp.ui

import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.movableContentOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.chat.ChatInputState
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.ChatViewModel
import io.github.strawwaffle.glorp.chat.Highlighter
import io.github.strawwaffle.glorp.chat.LineFilter
import io.github.strawwaffle.glorp.chat.EventLine
import io.github.strawwaffle.glorp.site.LinkTitles
import io.github.strawwaffle.glorp.site.mediaOf
import io.github.strawwaffle.glorp.chat.OtherStreams
import io.github.strawwaffle.glorp.chat.fromOtherStream
import io.github.strawwaffle.glorp.chat.Poll
import io.github.strawwaffle.glorp.chat.Segment
import io.github.strawwaffle.glorp.chat.SocketState
import io.github.strawwaffle.glorp.chat.segments
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.community.localTime
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.strawwaffle.glorp.site.LineModel

@Composable
fun ChatScreen(
    /** Read only by chat itself, so a new message redraws chat and nothing around it. */
    lines: () -> List<ChatLine>,
    flairs: Flairs,
    emotes: Emotes,
    state: SocketState,
    account: Account?,
    onSend: (String) -> Boolean,
    /** The input's draft, suggestions and lists, kept by the view model. */
    input: ChatInputState,
    onEmbed: (Embed) -> Unit,
    /** Opens signing in, from the bar shown in the input's place while signed out. */
    onSignIn: () -> Unit = {},
    /** What the sheet for a tapped name can do. */
    userActions: UserActions,
    /** The player, shown above chat; null when hidden. */
    player: (@Composable () -> Unit)?,
    /** The bar between the player and chat, there with the player hidden too. */
    bar: @Composable () -> Unit = {},
    /**
     * Something shown in chat's place (the menu, signing in), under the same
     * player, so a stream keeps playing. Null shows chat.
     */
    instead: (@Composable () -> Unit)? = null,
    /** The poll above chat, and the pinned message. */
    poll: Poll? = null,
    onVote: (Int) -> Unit = {},
    onClosePoll: () -> Unit = {},
    pinned: ChatViewModel.Pinned? = null,
    onShowPin: (Boolean) -> Unit = {},
    /** Scrolled up to read back (true), or following the newest again. */
    onReading: (Boolean) -> Unit = {},
) {
    // The player and what's beside it are each one piece of UI that moves
    // between the layouts (portrait, landscape, full screen, picture in
    // picture) rather than being rebuilt, so a stream doesn't reload when
    // the phone turns.
    val pip = LocalPictureInPicture.current.active
    val fullscreen = LocalFullscreen.current.active
    val currentPlayer by rememberUpdatedState(player)
    val playerSlot = remember { movableContentOf { currentPlayer?.invoke() } }
    val currentBody by rememberUpdatedState<@Composable () -> Unit> {
        if (instead != null) instead()
        else ChatBody(
            lines, flairs, emotes, state, account, onSend, input, onEmbed, userActions,
            poll, onVote, onClosePoll, pinned, onShowPin, onSignIn, onReading,
        )
    }
    val bodySlot = remember { movableContentOf { currentBody() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Black)) {
        val wide = maxWidth > maxHeight && player != null
        val chatWidth = (maxWidth * 0.38f).coerceIn(280.dp, 420.dp)
        when {
            // Only the video: the floating window, or the whole screen.
            pip || (fullscreen && player != null) -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { playerSlot() }
                // Kept, at no size, so what's under the bar (a report being
                // written, a search, an open conversation) is still there after.
                val focus = androidx.compose.ui.platform.LocalFocusManager.current
                LaunchedEffect(Unit) { focus.clearFocus() }
                Box(Modifier.size(0.dp).clipToBounds()) { bodySlot() }
            }
            // Sideways: the video beside chat.
            wide -> Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) { playerSlot() }
                Column(Modifier.width(chatWidth).fillMaxHeight().imePadding()) {
                    bar()
                    Box(Modifier.fillMaxWidth().weight(1f)) { bodySlot() }
                }
            }
            else -> Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                playerSlot()
                bar()
                Box(Modifier.fillMaxWidth().weight(1f)) { bodySlot() }
            }
        }
    }
}

@Composable
private fun ChatBody(
    linesOf: () -> List<ChatLine>,
    flairs: Flairs,
    emotes: Emotes,
    state: SocketState,
    account: Account?,
    onSend: (String) -> Boolean,
    input: ChatInputState,
    onEmbed: (Embed) -> Unit,
    userActions: UserActions,
    poll: Poll?,
    onVote: (Int) -> Unit,
    onClosePoll: () -> Unit,
    pinned: ChatViewModel.Pinned?,
    onShowPin: (Boolean) -> Unit,
    onSignIn: () -> Unit,
    onReading: (Boolean) -> Unit,
) {
    val lines = linesOf()
    Column(Modifier.fillMaxSize()) {
        // No bar: the connection shows only when something is wrong.
        // Empty, chat says what it's doing in the middle instead (see below).
        if (lines.isNotEmpty()) ConnectionNotice(state)
        // Newest at the bottom, in a reversed list: only the rows on screen are
        // composed, so only their emotes are drawn or animated.
        // A run of messages from one person shows their name once, as chat
        // does: the lines after the first are "continued".
        val me = account?.nick
        // Messages hidden by settings (NSFW, mentions of ignored people, your
        // hidden phrases) are left out before runs are worked out.
        val settings = input.settings
        val ignoredNicks = input.ignored.nicks
        val hiddenPhrases = input.options.hiddenPhrases
        val filter = remember(me, ignoredNicks, settings.ignoreMentions, settings.hideNsfw, settings.hideNsfl, hiddenPhrases) {
            LineFilter(me, ignoredNicks, settings.ignoreMentions, settings.hideNsfw, settings.hideNsfl, hiddenPhrases)
        }
        val mention = remember(me) { me?.let(::mentionPattern) }
        // Highlights by name and by word; mentions of you are the row's own business.
        val highlightNicks = input.highlighted.nicks
        val words = input.settings.list("customhighlight")
        val highlighter = remember(me, highlightNicks, words) { Highlighter(me, self = false, highlightNicks, words) }
        // Hiding other streams' chat keeps what mentions or highlights you, as the site's dimming does.
        val look = LocalChatLook.current
        val hideOthers = look.otherStreams == OtherStreams.Hide && look.playing != null
        // What rows are prepared and measured with ahead of the screen (see RowPrefetch): as ChatRow and ChatText do it.
        val outerDensity = LocalDensity.current
        val rowDensity = remember(outerDensity, look.textScale) {
            if (look.textScale == 1f) outerDensity else Density(outerDensity.density, outerDensity.fontScale * look.textScale)
        }
        val rowStyle = LocalTextStyle.current.merge(TextStyle(color = Palette.White, fontSize = 14.sp, lineHeight = LINE_HEIGHT))
        val fontResolver = androidx.compose.ui.platform.LocalFontFamilyResolver.current
        val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
        val aheadMeasurer = remember(fontResolver, rowDensity, direction) { androidx.compose.ui.text.TextMeasurer(fontResolver, rowDensity, direction, cacheSize = 0) }
        var listWidth by remember { mutableIntStateOf(0) }
        val textWidth = listWidth - with(outerDensity) { 20.dp.roundToPx() }
        val mentionAhead = remember(me) { me?.let(::mentionPattern) }
        val ahead = remember(flairs, emotes, rowDensity, me, look, rowStyle, textWidth, aheadMeasurer) {
            RowPrefetch.Context(flairs, emotes, rowDensity, me, mentionAhead, look, rowStyle, textWidth, aheadMeasurer)
        }
        SideEffect { RowPrefetch.context = ahead }
        DisposableEffect(Unit) {
            io.github.strawwaffle.glorp.chat.LinePrefetch.hook = RowPrefetch::prefetch
            onDispose { io.github.strawwaffle.glorp.chat.LinePrefetch.hook = null; RowPrefetch.context = null }
        }
        // Each line is judged once, not again on every update: in busy chat
        // that was every pattern against every kept line several times a second.
        val facts = remember(filter, hideOthers, look.playing, mention, highlighter, me) { LineFacts() }
        val reversed = remember(lines, facts) {
            val shownLines = lines.filter { line ->
                !facts.hidden(line) {
                    (!filter.isEmpty && filter.hides(it)) ||
                        (hideOthers && fromOtherStream(it, me, look.playing) && !singledOut(it, mention, highlighter))
                }
            }
            facts.prune(lines)
            shownLines.mapIndexed { i, line -> Shown(line, i > 0 && continues(shownLines[i - 1], line)) }.asReversed()
        }
        val embedHandler = rememberUpdatedState(onEmbed)
        val openEmbed: (Embed) -> Unit = remember { { embedHandler.value(it) } }
        // Tapping a name, or holding a message, opens the sheet about that person.
        val tapNick: (String) -> Unit = remember(input) { { input.openUser(it) } }
        val holdLine: (ChatLine) -> Unit = remember(input) { { line -> line.nick?.let { input.openUser(it, line) } } }
        input.tappedNick?.let { nick ->
            UserSheet(nick, input.tappedLine, flairs, account, input, userActions, onDismiss = input::closeUser)
        }
        input.tappedEmote?.let { prefix ->
            emotes[prefix]?.let { EmoteSheet(it, emotes.info(prefix), account, input, onDismiss = input::closeEmote, hover = emotes.hover(prefix)) }
                ?: LaunchedEffect(prefix) { input.closeEmote() }
        }
        if (pinned != null && !pinned.dismissed) PinnedBar(pinned, flairs, emotes, me, openEmbed, tapNick, onShowPin)
        if (poll != null && !poll.dismissed) PollCard(poll, onVote, onClosePoll)
        val list = rememberLazyListState()
        // A lazy list keeps the row you're looking at in place when rows are
        // added, which hides new lines below the bottom. So: follow while
        // at the bottom, stop when you scroll up to read, and follow again
        // once you scroll back down or tap the button.
        var following by remember { mutableStateOf(true) }
        LaunchedEffect(list) {
            snapshotFlow { list.isScrollInProgress to list.canScrollBackward }
                .collect { (scrolling, awayFromBottom) -> if (scrolling) following = !awayFromBottom }
        }
        // New rows a few a frame rather than a screenful in one (see RowPacer).
        val pacer = remember { RowPacer() }
        val fresh = remember(reversed) { pacer.fresh(reversed, following) }
        var hidden by remember(reversed) { mutableIntStateOf(fresh) }
        val displayed = if (hidden <= 0) reversed else reversed.subList(hidden.coerceAtMost(reversed.size), reversed.size)
        SideEffect { pacer.shown(displayed) }
        LaunchedEffect(reversed) {
            var perFrame = RowPacer.START
            var last = withFrameNanos { it }
            while (hidden > 0) {
                hidden = (hidden - perFrame).coerceAtLeast(0)
                val now = withFrameNanos { it }
                perFrame = pacer.next(perFrame, (now - last) / 1_000_000)
                last = now
            }
        }
        // Kept at the newest while following; the list itself would keep the
        // row you were looking at in place and leave new ones below. (Pinning
        // in the same frame was tried: on a phone its frames ran longer.)
        LaunchedEffect(displayed) { if (following) list.scrollToItem(0) }
        LaunchedEffect(following) { onReading(!following) }
        DisposableEffect(Unit) { onDispose { onReading(false) } }
        // One text measurer for every line of the chat (see ChatText).
        val chatMeasurer = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 0)
        Box(Modifier.fillMaxWidth().weight(1f)) { CompositionLocalProvider(LocalChatMeasurer provides chatMeasurer) {
            if (displayed.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (state != SocketState.Stopped) {
                        CircularProgressIndicator(color = Palette.Glorp, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    }
                    Text(
                        when (state) {
                            SocketState.Connected -> "Waiting for the first messages…"
                            SocketState.Connecting -> "Establishing contact…"
                            SocketState.Waiting -> "Signal lost, retrying"
                            SocketState.Stopped -> "Transmission ended"
                        },
                        color = Palette.TextDim,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize().onSizeChanged { listWidth = it.width }, state = list, reverseLayout = true) {
                items(displayed, key = { it.line.id }, contentType = { it.line.kind }) { shown ->
                    val marked = remember(shown.line, highlighter) {
                        shown.line.kind == ChatLine.Kind.User && highlighter.highlights(shown.line.nick, shown.line.text)
                    }
                    ChatRow(
                        shown.line, shown.continued, flairs, emotes, me, mention, marked, openEmbed, tapNick,
                        onHold = if (shown.line.kind == ChatLine.Kind.User) holdLine else null,
                    )
                }
            }
            // The newest mention of you stays in view once it has scrolled past
            // (the d.gg utilities userscript's sticky mentions), until closed.
            var closedMention by remember { mutableStateOf<String?>(null) }
            val latest = remember(displayed, mention, me, look.highlightSelf) {
                if (mention == null || !look.highlightSelf) null
                else displayed.indexOfFirst { s ->
                    s.line.kind == ChatLine.Kind.User && !s.line.nick.equals(me, ignoreCase = true) &&
                        facts.mentions(s.line) { mention.containsMatchIn(it.text) }
                }.takeIf { it >= 0 }
            }
            val sticky by remember(latest) {
                derivedStateOf {
                    // Above what's on screen: older than the oldest row shown.
                    latest != null && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE) < latest
                }
            }
            val stuck = latest?.let { displayed.getOrNull(it)?.line }
            if (input.options.stickyMentions && sticky && stuck != null && stuck.id != closedMention) {
                val scope = rememberCoroutineScope()
                StickyMention(
                    stuck,
                    Modifier.align(Alignment.TopCenter),
                    onShow = { scope.launch { list.animateScrollToItem(latest); following = false } },
                    onClose = { closedMention = stuck.id },
                )
            }
            ModWarning(modNotes(input.watch), Modifier.align(Alignment.TopStart).padding(6.dp))
            Column(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalAlignment = Alignment.End) {
                if (pinned != null && pinned.dismissed) Tab("Pinned message", Palette.NavyLight) { onShowPin(true) }
            }
            if (!following) {
                val scope = rememberCoroutineScope()
                TextButton(
                    onClick = { following = true; scope.launch { list.scrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                        .background(Palette.NavyLight, RoundedCornerShape(16.dp)),
                ) { Text("Newest messages", color = Palette.White, fontSize = 13.sp) }
            }
        } }
        if (account != null) {
            ChatInput(input, emotes, account, connected = state == SocketState.Connected, onSend)
        } else {
            // Signed out, where the input would be: how to join in.
            Row(
                Modifier.fillMaxWidth().background(Palette.Navy).clickable(onClick = onSignIn)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sign in to chat", color = Palette.Blue, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text("You're reading as a guest", color = Palette.TextDim, fontSize = 13.sp)
            }
        }
    }
}

/** A mention of you that scrolled past: who and what, tap to go to it, × to close. */
@Composable
private fun StickyMention(line: ChatLine, modifier: Modifier, onShow: () -> Unit, onClose: () -> Unit) {
    Row(
        modifier.fillMaxWidth().background(Palette.Navy)
            .drawBehind { drawRect(Palette.Orange, size = Size(4.dp.toPx(), size.height)) }
            .clickable(onClickLabel = "Go to the mention", onClick = onShow)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.nick) }
                append(": ")
                append(line.text)
            },
            color = Palette.White,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        Box(Modifier.size(44.dp).clickable(onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(GlorpIcons.Close, "Close", tint = Palette.TextDim, modifier = Modifier.size(18.dp))
        }
    }
}

/** A small button over the top of chat, for what's waiting: whispers, a closed pin. */
/**
 * Nukes and mutelinks: a warning triangle at the top left of chat while any
 * is on, which opens into a card saying what (and closes again), as the
 * pinned message does.
 */
@Composable
private fun ModWarning(notes: List<String>, modifier: Modifier) {
    if (notes.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val tile = Palette.Tile ?: Palette.Navy
    Column(
        modifier.widthIn(max = 300.dp).background(tile, RoundedCornerShape(10.dp))
            .border(1.dp, Palette.Orange.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .clickable(onClickLabel = if (open) "Close" else "What's on") { open = !open }
            .semantics(mergeDescendants = true) { contentDescription = if (open) notes.joinToString(". ") else "Warning: ${notes.size} on" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(18.dp)) {
                val orange = Palette.Orange
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(size.width / 2, size.height * 0.06f)
                    lineTo(size.width * 0.97f, size.height * 0.92f)
                    lineTo(size.width * 0.03f, size.height * 0.92f)
                    close()
                }
                drawPath(path, orange)
                val ink = Color(0xFF1A1A1A)
                drawLine(ink, androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.36f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.64f), strokeWidth = size.width * 0.11f)
                drawCircle(ink, radius = size.width * 0.065f, center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height * 0.78f))
            }
            if (notes.size > 1 && !open) Text("${notes.size}", color = Palette.Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
            if (open) Text("Careful what you send", color = Palette.Orange, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp))
        }
        if (open) for (note in notes) Text(note, color = Palette.White, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Tab(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = if (color == Palette.Orange) Palette.Black else Palette.White,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 4.dp).background(color, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ConnectionNotice(state: SocketState) {
    val text = when (state) {
        SocketState.Connected -> return
        SocketState.Connecting -> "Establishing contact…"
        SocketState.Waiting -> "Signal lost, retrying"
        SocketState.Stopped -> "Transmission ended"
    }
    Text(
        text,
        color = if (state == SocketState.Connecting) Palette.TextDim else Palette.Orange,
        fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** The line's elements in chat-gui's markup, in order: an emote's name, or null for a link. Text isn't an element. */
private fun elementsOf(parts: List<Segment>): List<String?> = buildList {
    for (part in parts) when (part) {
        is Segment.EmoteRef -> add(part.emote.prefix)
        is Segment.Link, is Segment.EmbedLink -> add(null)
        is Segment.Text -> Unit
    }
}

private class Shown(val line: ChatLine, val continued: Boolean)

/** Whether [line] carries on a run started by [previous]: two chat messages from the same person. */
internal fun continues(previous: ChatLine, line: ChatLine): Boolean =
    previous.kind == ChatLine.Kind.User && line.kind == ChatLine.Kind.User &&
        previous.nick.equals(line.nick, ignoreCase = true)

/**
 * How chat's settings say lines look: the time before each, flair icons or
 * not, whether mentions of you stand out, and tagged users' colours (lower
 * case nick to a [tagColor] name).
 */
/** A line that mentions you or matches your highlights: never dimmed or hidden for its stream. */
private fun singledOut(line: ChatLine, mention: Regex?, highlighter: Highlighter): Boolean =
    mention?.containsMatchIn(line.text) == true || highlighter.highlights(line.nick, line.text)

data class ChatLook(
    val showTime: Boolean = false,
    val timeFormat: String = "HH:mm",
    val hideFlairIcons: Boolean = false,
    val highlightSelf: Boolean = true,
    val tags: Map<String, String> = emptyMap(),
    val taggedVisibility: Boolean = false,
    /** Flair names whose icons aren't drawn. */
    val hiddenFlairs: Set<String> = emptySet(),
    /** Whether a muted or banned user's messages hide behind `<censored>`. */
    val censorRemoved: Boolean = true,
    /** The embed playing here (`platform/id`), and what to do with chat from people watching another. */
    val playing: String? = null,
    val otherStreams: OtherStreams = OtherStreams.Show,
    /** Chat text size over the phone's own (see [textScales]). */
    val textScale: Float = 1f,
    /** Glorp's own name icons (an emoji before someone's name), by lower-case nick. */
    val icons: Map<String, String> = emptyMap(),
    /** The colour theme: rows drawn in one are drawn again in another. */
    val theme: ColorTheme = ColorTheme.Glorp,
    /** Off with chat-gui's "disable animations": emotes don't move and rainbow names stay still. */
    val animations: Boolean = true,
)

/** An event card's colour: its bar, its tint and its mark. */
internal fun eventColor(kind: EventLine.Kind): Color = when (kind) {
    EventLine.Kind.Subscription -> Color(0xFF00C2E0)
    EventLine.Kind.Gift -> Color(0xFFC377E0)
    EventLine.Kind.MassGift -> Color(0xFFFF80CE)
    EventLine.Kind.Donation -> Color(0xFF61BD4F)
    EventLine.Kind.Death -> Color(0xFFEB5A46)
    EventLine.Kind.XPost -> Color(0xFF9EA3AD)
    EventLine.Kind.Vestaboard -> Color(0xFFFFAB4A)
}

private fun eventMark(kind: EventLine.Kind): String = when (kind) {
    EventLine.Kind.Subscription -> "★"
    EventLine.Kind.Gift, EventLine.Kind.MassGift -> "🎁"
    EventLine.Kind.Donation -> "$"
    EventLine.Kind.Death -> "☠"
    EventLine.Kind.XPost -> "𝕏"
    EventLine.Kind.Vestaboard -> "▦"
}

/** chat-gui's tag colours. */
internal fun tagColor(name: String): Color = when (name) {
    "green" -> Color(0xFF61BD4F)
    "yellow" -> Color(0xFFF2D600)
    "orange" -> Color(0xFFFFAB4A)
    "red" -> Color(0xFFEB5A46)
    "purple" -> Color(0xFFC377E0)
    "blue" -> Color(0xFF0079BF)
    "sky" -> Color(0xFF00C2E0)
    "lime" -> Color(0xFF51E898)
    "pink" -> Color(0xFFFF80CE)
    else -> Color(0xFF4D4D4D)
}

/** chat-gui's tag bar is 0.45em wide: about 6 dp beside 14 sp text. */
private val TAG_BAR = 6.dp

/** chat-gui's `.chat-lines { line-height: 1.65em }`: emotes don't make a line taller, they reach over the one above. */
private val LINE_HEIGHT = 1.65.em

/** Whether a tap on a row's name or link should count: not after a hold. */
private class HoldGate {
    var open = true
}

val LocalChatLook = staticCompositionLocalOf { ChatLook() }

/** Links the app opens itself (rustlesearch.dev searches); true when it took the link, false for the browser. */
val LocalOpenLink = staticCompositionLocalOf<(String) -> Boolean> { { false } }

/**
 * Shows a link's preview card (see [LinkCard]) for a link tapped in a message
 * with the given text; false when the link has no preview and should just open.
 */
val LocalLinkCard = staticCompositionLocalOf<(url: String, messageText: String) -> Boolean> { { _, _ -> false } }

/** Opens an emote's card (see [EmoteSheet]) when an emote in a message is tapped. */
val LocalEmoteTap = staticCompositionLocalOf<(String) -> Unit> { {} }

/** An emote in a message, which opens its card when tapped. */
@Composable
private fun TappableEmote(prefix: String, content: @Composable () -> Unit) {
    val tap = LocalEmoteTap.current
    Box(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClickLabel = "About $prefix",
        ) { tap(prefix) },
    ) { content() }
}

/** Titles for YouTube and Rumble links; null where they aren't looked up (turned off, or no app around). */
val LocalLinkTitles = staticCompositionLocalOf<LinkTitles?> { null }

private val anyUrl = Regex("""https?://\S+""")

/** A line laid out once per change of its inputs, with pictures as inline content. */
private class RenderedLine(
    val text: AnnotatedString,
    val inline: Map<String, InlineTextContent>,
    val mentionsMe: Boolean,
    /** Where the name is, when it's drawn in the moving rainbow (Tier 5). */
    val rainbowName: IntRange? = null,
)

/** The site's rainbow (flairs.css): eight hues at 100% saturation, 65% lightness, once across the name. */
private val rainbow = (0..8).map { Color.hsl(it * 45f, 1f, 0.65f) }

/** Once along the name every 3 seconds, as the site's `move` animation. */
private const val RAINBOW_MS = 3_000

@Composable
internal fun ChatRow(
    line: ChatLine,
    continued: Boolean,
    flairs: Flairs,
    emotes: Emotes,
    me: String?,
    mention: Regex?,
    highlighted: Boolean,
    onEmbed: (Embed) -> Unit,
    onNick: (String) -> Unit,
    /** Holding the line: its message's sheet. Null for lines that have none. */
    onHold: ((ChatLine) -> Unit)? = null,
) {
    val look = LocalChatLook.current
    // The chat text size setting scales the text, not the emotes and badges,
    // whose sizes are set in dp from this same density.
    val outer = LocalDensity.current
    val density = remember(outer, look.textScale) {
        if (look.textScale == 1f) outer else Density(outer.density, outer.fontScale * look.textScale)
    }
    val uri = LocalUriHandler.current
    val openLink = LocalOpenLink.current
    val linkCard = LocalLinkCard.current
    // A press held long enough to open the sheet isn't also a tap on the
    // name or link under it, which would fire when the finger lifts.
    val gate = remember { HoldGate() }
    // A muted or banned user's message hides behind <censored> until tapped, as on the site.
    var revealed by remember(line.id) { mutableStateOf(false) }
    val censored = line.moderated && look.censorRemoved && !revealed
    // YouTube and Rumble links on screen are looked up for their titles;
    // the row is drawn again as each arrives.
    val titles = LocalLinkTitles.current
    val media = remember(line.id, titles) {
        if (titles == null) emptyList() else anyUrl.findAll(line.text).map { it.value }.filter { mediaOf(it) != null }.toList()
    }
    if (titles != null && media.isNotEmpty()) LaunchedEffect(line.id) { media.forEach(titles::request) }
    val knownTitles = media.map { titles?.titleOf(it) }
    // The trial: one element per line (see ChatText); a line it can't draw keeps the old way.
    val comboMeasurer = LocalChatMeasurer.current ?: androidx.compose.ui.text.rememberTextMeasurer()
    val prepared = if (!ChatRendering.singleNode) null
    else remember(line, continued, flairs, emotes, density, me, look, censored, knownTitles) {
        // Prepared and measured already, off the main thread, if nothing has changed since.
        RowPrefetch.take(line, continued, flairs, emotes, density, me, look).takeIf { !censored && knownTitles.isEmpty() } ?: prepare(
            line, continued, flairs, emotes, density, me, mention, look, censored, titleOf = { url -> titles?.titleOf(url) },
            comboWidth = { count, ended -> comboWidthDp(comboMeasurer, density, count, ended) },
        )
    }
    val renderedOld = if (prepared != null) null else remember(line, continued, flairs, emotes, density, me, look, censored, knownTitles) {
        render(
            line, continued, flairs, emotes, density, me, mention, look, censored,
            titleOf = { url -> titles?.titleOf(url) },
            onReveal = { revealed = true },
            onEmbed = { if (gate.open) onEmbed(it) },
            onNick = { if (gate.open) onNick(it) },
            onUrl = { if (gate.open && !openLink(it) && !linkCard(it, line.text)) runCatching { uri.openUri(it) } },
        )
    }
    // A tagged user's lines get a bar of their colour down the left, as chat-gui draws them.
    val tag = if (line.kind == ChatLine.Kind.User) line.nick?.lowercase()?.let { look.tags[it] } else null
    val mentionsMe = prepared?.mentionsMe ?: renderedOld!!.mentionsMe
    val singled = (mentionsMe && look.highlightSelf) || highlighted
    val event = line.event?.kind
    val background = when {
        event != null -> eventColor(event).copy(alpha = 0.12f)
        line.spotlightedBy != null -> Palette.Orange.copy(alpha = 0.12f)
        line.kind == ChatLine.Kind.Whisper -> Palette.Blue.copy(alpha = 0.12f)
        singled -> Palette.Mention
        tag != null && look.taggedVisibility -> Palette.Tagged
        else -> Color.Transparent
    }
    // The tag's colour down the left, as chat-gui draws it; otherwise the
    // theme's mention bar, where it has one (Aerith's teal).
    val bar = event?.let(::eventColor) ?: Palette.Orange.takeIf { line.spotlightedBy != null }
        ?: tag?.let(::tagColor) ?: Palette.MentionBar?.takeIf { singled }
    // Aerith's rows: a marked line's background rounded off on the right.
    val rowShape = if (Palette.Tile != null && background != Color.Transparent) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp) else androidx.compose.ui.graphics.RectangleShape
    // From someone watching another stream: faded as the site's eye button does.
    val faded = look.otherStreams == OtherStreams.Dim && !mentionsMe && !highlighted &&
        fromOtherStream(line, me, look.playing)
    // A Tier 5 name: the text is drawn, then the moving rainbow is laid over
    // the name's letters only (SrcAtop, in a layer of its own). The name's
    // outline is worked out once per layout, and the colours move at 30
    // frames a second, which looks the same as 60 or 120 for a slow sweep.
    if (prepared != null) {
        val emoteTap = LocalEmoteTap.current
        ChatText(
            prepared,
            // As Text would: the app's text style (its font, how lines are spaced) under chat's own.
            LocalTextStyle.current.merge(TextStyle(color = Palette.White, fontSize = 14.sp, lineHeight = LINE_HEIGHT)),
            density,
            Modifier.fillMaxWidth().then(if (faded) Modifier.alpha(0.3f) else Modifier).background(background, rowShape)
                .then(if (bar == null) Modifier else Modifier.drawBehind { drawRect(bar, size = Size(TAG_BAR.toPx(), size.height)) })
                .padding(horizontal = 10.dp, vertical = 2.dp),
            animate = look.animations,
            onTap = { tap ->
                when (tap) {
                    is LineTap.Nick -> onNick(tap.nick)
                    is LineTap.Url -> if (!openLink(tap.url) && !linkCard(tap.url, line.text)) runCatching { uri.openUri(tap.url) }
                    is LineTap.EmbedTap -> onEmbed(tap.embed)
                    LineTap.Reveal -> revealed = true
                    is LineTap.EmoteTap -> emoteTap(tap.prefix)
                }
            },
            onHold = onHold?.let { hold -> { hold(line) } },
        )
        return
    }
    val rendered = renderedOld!!
    val rainbowName = rendered.rainbowName
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val outline = remember(layout, rainbowName) {
        val text = layout
        if (text == null || rainbowName == null || rainbowName.last >= text.layoutInput.text.length) null
        else text.getPathForRange(rainbowName.first, rainbowName.last + 1).let { it to it.getBounds() }
    }
    val phase = remember { mutableFloatStateOf(0f) }
    if (rainbowName != null && look.animations) {
        LaunchedEffect(Unit) {
            while (true) {
                val now = withFrameMillis { it }
                phase.floatValue = (now % RAINBOW_MS) / RAINBOW_MS.toFloat()
                delay(33)
            }
        }
    }
    val rainbowLayer = if (outline == null) Modifier else Modifier
        .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        .drawWithContent {
            drawContent()
            val (path, bounds) = outline
            val width = bounds.width.coerceAtLeast(1f)
            val from = bounds.left + phase.floatValue * width
            drawPath(
                path,
                Brush.linearGradient(rainbow, Offset(from, 0f), Offset(from + width, 0f), TileMode.Repeated),
                blendMode = BlendMode.SrcAtop,
            )
        }
    CompositionLocalProvider(LocalDensity provides density) { Text(
        rendered.text,
        inlineContent = rendered.inline,
        // Kept only for a rainbow name: saving it redraws the row, which every
        // row would otherwise do twice.
        onTextLayout = if (rainbowName != null) { { layout = it } } else { {} },
        modifier = Modifier.fillMaxWidth().then(if (faded) Modifier.alpha(0.3f) else Modifier).background(background, rowShape)
            .then(if (bar == null) Modifier else Modifier.drawBehind { drawRect(bar, size = Size(TAG_BAR.toPx(), size.height)) })
            .then(
                if (onHold == null) Modifier
                else Modifier.pointerInput(line) {
                    detectTapGestures(
                        onPress = { gate.open = true },
                        onLongPress = { gate.open = false; onHold(line) },
                    )
                },
            )
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .then(rainbowLayer),
        color = Palette.White,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ) }
}

private val linkStyle get() = TextLinkStyles(SpanStyle(color = Palette.Blue, textDecoration = TextDecoration.Underline))

// A name is tappable but looks like a name, not a link.
private val nickStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None))

/** Matches [nick] as a word, the way chat highlights mentions. Built once per name and kept. */
internal fun mentionPattern(nick: String): Regex =
    lastMention?.takeIf { it.first == nick }?.second
        ?: Regex("""(^|[^\w])${Regex.escape(nick)}($|[^\w])""", RegexOption.IGNORE_CASE).also { lastMention = nick to it }

// Asked for every message while signed in: the one name in use, its pattern built once.
@kotlin.concurrent.Volatile
private var lastMention: Pair<String, Regex>? = null

internal fun mentions(text: String, nick: String): Boolean = mentionPattern(nick).containsMatchIn(text)

private fun render(
    line: ChatLine,
    continued: Boolean,
    flairs: Flairs,
    emotes: Emotes,
    density: Density,
    me: String?,
    mention: Regex?,
    look: ChatLook,
    censored: Boolean,
    titleOf: (String) -> LinkTitles.Title? = { null },
    onReveal: () -> Unit,
    onEmbed: (Embed) -> Unit,
    onNick: (String) -> Unit,
    onUrl: (String) -> Unit,
): RenderedLine {
    val inline = HashMap<String, InlineTextContent>()
    fun placeholder(widthDp: Int, heightDp: Int) = with(density) {
        Placeholder(widthDp.dp.toSp(), heightDp.dp.toSp(), PlaceholderVerticalAlign.Center)
    }
    var mentionsMe = false
    var rainbowName: IntRange? = null

    val text = buildAnnotatedString {
        // A spotlighted message says so above it, as chat-gui's frame does.
        line.spotlightedBy?.let { by ->
            withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp)) {
                append(if (by.isBlank()) "★ Spotlighted" else "★ Spotlighted by $by")
            }
            append('\n')
        }
        // The time first, as chat-gui puts it, for everything but a combo.
        if (look.showTime && line.kind != ChatLine.Kind.Combo && line.at > 0) {
            withStyle(SpanStyle(color = Palette.TextDim, fontSize = 12.sp)) { append(localTime(line.at, look.timeFormat)) }
            append(' ')
        }
        when (line.kind) {
            ChatLine.Kind.Broadcast -> {
                withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold)) { append(line.text) }
                return@buildAnnotatedString
            }
            ChatLine.Kind.Info -> {
                withStyle(SpanStyle(color = Palette.TextDim, fontStyle = FontStyle.Italic)) { append(line.text) }
                return@buildAnnotatedString
            }
            ChatLine.Kind.Combo -> {
                // As chat-gui writes it: the emote, then "N X Hits C-C-C-COMBO",
                // the count growing with the combo (its x5/x10/x20/x30/x50 steps).
                // A combo's size can change how its emote looks (data-combo-group).
                val emote = emotes[line.text]?.let { emotes.inLine(LineModel.combo(it.prefix, line.comboCount)).firstOrNull() ?: it }
                if (emote != null) {
                    val id = "emote:${emote.prefix}:${emote.hashCode()}"
                    inline.getOrPut(id) {
                        InlineTextContent(placeholder(emote.width, emote.height)) {
                            // Each hit plays it again, as chat-gui re-adds the emote.
                            TappableEmote(emote.prefix) { EmoteImage(emote, sentAt = line.lastHitAt.takeIf { it > 0 } ?: line.at) }
                        }
                    }
                    appendInlineContent(id, emote.prefix)
                } else {
                    append(line.text)
                }
                append("  ")
                val n = line.comboCount
                val size = when {
                    n >= 50 -> 24.sp
                    n >= 30 -> 22.sp
                    n >= 20 -> 20.sp
                    n >= 10 -> 18.sp
                    n >= 5 -> 16.sp
                    else -> 14.sp
                }
                withStyle(SpanStyle(color = Palette.White, fontWeight = FontWeight.Bold, fontSize = size)) { append("$n") }
                withStyle(SpanStyle(color = Palette.TextDim, fontSize = 12.sp, fontStyle = FontStyle.Italic)) {
                    append(" X Hits ")
                }
                withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp)) { append("C-C-C-COMBO") }
                return@buildAnnotatedString
            }
            ChatLine.Kind.Whisper -> {
                withStyle(SpanStyle(color = Palette.Blue)) { append("Whisper from ") }
                withLink(LinkAnnotation.Clickable("nick", nickStyle) { onNick(line.nick!!) }) {
                    withStyle(SpanStyle(color = Palette.White, fontWeight = FontWeight.Bold)) { append(line.nick) }
                }
                append(": ")
                append(line.text)
                return@buildAnnotatedString
            }
            ChatLine.Kind.Event -> {
                // chat-gui's event card: its mark, who, what they did; their message below.
                val event = line.event!!
                withStyle(SpanStyle(color = eventColor(event.kind), fontWeight = FontWeight.Bold)) { append(eventMark(event.kind)); append(' ') }
                line.nick?.let { nick ->
                    val color = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
                    withLink(LinkAnnotation.Clickable("nick", nickStyle) { onNick(nick) }) {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(nick) }
                    }
                    append(' ')
                }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(event.header) }
                event.link?.let { url ->
                    append("  ")
                    withLink(LinkAnnotation.Clickable(url, linkStyle) { onUrl(url) }) { append("Open") }
                }
                if (line.text.isBlank()) return@buildAnnotatedString
                append('\n')
            }
            ChatLine.Kind.User -> Unit
        }
        mentionsMe = mention != null && !line.nick.equals(me, ignoreCase = true) && mention.containsMatchIn(line.text)
        val nameColor = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
        if (line.kind == ChatLine.Kind.Event) {
            // The name is in the event's header.
        } else if (continued) {
            // The rest of a run: no badges or name, a dim marker instead.
            withStyle(SpanStyle(color = Palette.TextDim)) { append("> ") }
        } else {
            for (badge in if (look.hideFlairIcons) emptyList() else flairs.badges(line.features).filter { it.name !in look.hiddenFlairs }) {
                val id = "badge:${badge.name}"
                inline.getOrPut(id) {
                    InlineTextContent(placeholder(badge.width, badge.height)) {
                        AsyncImage(badge.url, contentDescription = badge.label, modifier = Modifier.size(badge.width.dp, badge.height.dp))
                    }
                }
                appendInlineContent(id, badge.label)
                append(' ')
            }
            look.icons[line.nick!!.lowercase()]?.let { append(it); append(' ') }
            withLink(LinkAnnotation.Clickable("nick", nickStyle) { onNick(line.nick!!) }) {
                val start = length
                withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(line.nick) }
                if (flairs.rainbowName(line.features)) rainbowName = start until length
            }
            append(if (line.action) " " else ": ")
        }
        if (censored) {
            withLink(LinkAnnotation.Clickable("censored", TextLinkStyles(SpanStyle(color = Palette.Blue))) { onReveal() }) {
                append("<censored>")
            }
            return@buildAnnotatedString
        }

        val style = when {
            // Italic only: chat-gui doesn't colour `/me` messages.
            line.action -> SpanStyle(fontStyle = FontStyle.Italic)
            line.green -> SpanStyle(color = Palette.Greentext)
            else -> SpanStyle()
        }
        val parts = segments(line, emotes)
        // How each emote looks beside the others (BLADE next to BLADE…).
        val placed = emotes.inLine(
            LineModel.message(
                line.features, flairs.nameFlair(line.features), continued, line.green && !line.action,
                elementsOf(parts), nick = line.nick,
            ),
        )
        var emoteIndex = 0
        withStyle(style) {
            for (segment in parts) when (segment) {
                is Segment.Text -> append(segment.text)
                is Segment.Link -> withLink(LinkAnnotation.Clickable(segment.url, linkStyle) { onUrl(segment.url) }) {
                    // A video link with its title: the platform's mark, the title and channel.
                    val title = titleOf(segment.url)
                    if (title == null) append(segment.text)
                    else {
                        val id = "platform:${title.platform}"
                        inline.getOrPut(id) {
                            InlineTextContent(placeholder(19, 16)) { PlatformIcon(title.platform, 15.dp, Modifier.padding(end = 4.dp)) }
                        }
                        appendInlineContent(id, " ")
                        append(title.title)
                        if (title.channel != null) {
                            append(" · ")
                            append(title.channel)
                        }
                    }
                }
                is Segment.EmbedLink -> withLink(
                    LinkAnnotation.Clickable(segment.embed.key, linkStyle) { onEmbed(segment.embed) },
                ) {
                    // The platform's mark first, so the link says where it goes.
                    val platform = segment.embed.platform
                    val id = "platform:$platform"
                    inline.getOrPut(id) {
                        InlineTextContent(placeholder(19, 16)) { PlatformIcon(platform, 15.dp, Modifier.padding(end = 4.dp)) }
                    }
                    appendInlineContent(id, " ")
                    append(segment.text)
                }
                is Segment.EmoteRef -> {
                    val emote = placed.getOrNull(emoteIndex++) ?: segment.emote
                    val id = "emote:${emote.prefix}:${emote.hashCode()}"
                    inline.getOrPut(id) {
                        InlineTextContent(placeholder(emote.width, emote.height)) {
                            TappableEmote(emote.prefix) { EmoteImage(emote, sentAt = line.at) }
                        }
                    }
                    appendInlineContent(id, emote.prefix)
                }
            }
        }
    }
    return RenderedLine(text, inline, mentionsMe, rainbowName)
}

/**
 * The same line as [render], for [ChatText]: what can be tapped marked with
 * string annotations instead of links, and pictures as placeholders with an
 * item each.
 */
private fun prepare(
    line: ChatLine,
    continued: Boolean,
    flairs: Flairs,
    emotes: Emotes,
    density: Density,
    me: String?,
    mention: Regex?,
    look: ChatLook,
    censored: Boolean,
    titleOf: (String) -> LinkTitles.Title? = { null },
    /** A combo's tail width at rest, in dp. */
    comboWidth: (count: Int, ended: Boolean) -> Int = { _, _ -> 120 },
): PreparedLine {
    val placeholders = ArrayList<AnnotatedString.Range<Placeholder>>()
    val items = ArrayList<InlineItem>()
    val embeds = HashMap<String, Embed>()
    var mentionsMe = false
    var rainbowName: IntRange? = null
    fun AnnotatedString.Builder.picture(item: InlineItem, alternate: String) {
        val start = length
        append(alternate)
        val size = with(density) {
            // An emote takes no height from its line (see emoteRect): a hairline on the baseline, as wide as the emote.
            if (item is InlineItem.EmoteItem) Placeholder(item.widthDp.dp.toSp(), 1.dp.toSp(), PlaceholderVerticalAlign.AboveBaseline)
            else Placeholder(item.widthDp.dp.toSp(), item.heightDp.dp.toSp(), PlaceholderVerticalAlign.Center)
        }
        placeholders += AnnotatedString.Range(size, start, length)
        items += item
    }
    fun AnnotatedString.Builder.emote(emote: io.github.strawwaffle.glorp.site.Emote, sentAt: Long) {
        picture(InlineItem.EmoteItem(emote, sentAt), emote.prefix)
    }
    val nickLook = SpanStyle(textDecoration = TextDecoration.None)
    val linkLook = SpanStyle(color = Palette.Blue, textDecoration = TextDecoration.Underline)

    val text = buildAnnotatedString {
        line.spotlightedBy?.let { by ->
            withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp)) {
                append(if (by.isBlank()) "★ Spotlighted" else "★ Spotlighted by $by")
            }
            append('\n')
        }
        if (look.showTime && line.kind != ChatLine.Kind.Combo && line.at > 0) {
            withStyle(SpanStyle(color = Palette.TextDim, fontSize = 12.sp)) { append(localTime(line.at, look.timeFormat)) }
            append(' ')
        }
        when (line.kind) {
            // Formatted as a message is (links, embeds, emotes), as chat-gui does, in the announcement's colour.
            ChatLine.Kind.Broadcast -> Unit
            ChatLine.Kind.Info -> {
                withStyle(SpanStyle(color = Palette.TextDim, fontStyle = FontStyle.Italic)) { append(line.text) }
                return@buildAnnotatedString
            }
            ChatLine.Kind.Combo -> {
                val emote = emotes[line.text]?.let { emotes.inLine(LineModel.combo(it.prefix, line.comboCount)).firstOrNull() ?: it }
                // chat-gui puts the line back into the page on every hit and when the combo ends,
                // which starts the emote's animation over each time.
                if (emote != null) emote(emote, maxOf(line.lastHitAt.takeIf { it > 0 } ?: line.at, line.comboEndedAt)) else append(line.text)
                // The count, X and HITS (or, once ended, C-C-C-COMBO), drawn as chat-gui does (see ComboItem).
                val ended = line.comboEndedAt > 0
                picture(
                    InlineItem.ComboItem(line.comboCount, line.lastHitAt.takeIf { it > 0 } ?: line.at, line.comboEndedAt, comboWidth(line.comboCount, ended)),
                    "${line.comboCount} X ${if (ended) "C-C-C-COMBO" else "Hits"}",
                )
                return@buildAnnotatedString
            }
            ChatLine.Kind.Whisper -> {
                withStyle(SpanStyle(color = Palette.Blue)) { append("Whisper from ") }
                withAnnotation("nick", line.nick!!) {
                    withStyle(SpanStyle(color = Palette.White, fontWeight = FontWeight.Bold)) { append(line.nick) }
                }
                append(": ")
                append(line.text)
                return@buildAnnotatedString
            }
            ChatLine.Kind.Event -> {
                val event = line.event!!
                withStyle(SpanStyle(color = eventColor(event.kind), fontWeight = FontWeight.Bold)) { append(eventMark(event.kind)); append(' ') }
                line.nick?.let { nick ->
                    val color = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
                    withAnnotation("nick", nick) {
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(nick) }
                    }
                    append(' ')
                }
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(event.header) }
                event.link?.let { url ->
                    append("  ")
                    withAnnotation("url", url) { withStyle(linkLook) { append("Open") } }
                }
                if (line.text.isBlank()) return@buildAnnotatedString
                append('\n')
            }
            ChatLine.Kind.User -> Unit
        }
        mentionsMe = mention != null && !line.nick.equals(me, ignoreCase = true) && mention.containsMatchIn(line.text)
        val nameColor = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
        if (line.kind == ChatLine.Kind.Event || line.kind == ChatLine.Kind.Broadcast) {
            // The name is in the event's header; an announcement has none.
        } else if (continued) {
            withStyle(SpanStyle(color = Palette.TextDim)) { append("> ") }
        } else {
            for (badge in if (look.hideFlairIcons) emptyList() else flairs.badges(line.features).filter { it.name !in look.hiddenFlairs }) {
                picture(InlineItem.BadgeItem(badge.url, badge.label, badge.width, badge.height), badge.label)
                append(' ')
            }
            look.icons[line.nick!!.lowercase()]?.let { append(it); append(' ') }
            withAnnotation("nick", line.nick!!) {
                val start = length
                withStyle(nickLook.merge(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold))) { append(line.nick) }
                if (flairs.rainbowName(line.features)) rainbowName = start until length
            }
            append(if (line.action) " " else ": ")
        }
        if (censored) {
            withAnnotation("reveal", "") { withStyle(SpanStyle(color = Palette.Blue)) { append("<censored>") } }
            return@buildAnnotatedString
        }
        val style = when {
            line.kind == ChatLine.Kind.Broadcast -> SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold)
            line.action -> SpanStyle(fontStyle = FontStyle.Italic)
            line.green -> SpanStyle(color = Palette.Greentext)
            else -> SpanStyle()
        }
        val parts = segments(line, emotes)
        val placed = emotes.inLine(
            LineModel.message(
                line.features, flairs.nameFlair(line.features), continued, line.green && !line.action,
                elementsOf(parts), nick = line.nick,
            ),
        )
        var emoteIndex = 0
        withStyle(style) {
            for (segment in parts) when (segment) {
                is Segment.Text -> appendMentions(segment.text, line.mentioned)
                is Segment.Link -> withAnnotation("url", segment.url) {
                    withStyle(linkLook) {
                        val title = titleOf(segment.url)
                        if (title == null) append(segment.text)
                        else {
                            picture(InlineItem.PlatformItem(title.platform), " ")
                            append(title.title)
                            if (title.channel != null) { append(" · "); append(title.channel) }
                        }
                    }
                }
                is Segment.EmbedLink -> withAnnotation("embed", segment.embed.key) {
                    embeds[segment.embed.key] = segment.embed
                    withStyle(linkLook) {
                        picture(InlineItem.PlatformItem(segment.embed.platform), " ")
                        append(segment.text)
                    }
                }
                is Segment.EmoteRef -> emote(placed.getOrNull(emoteIndex++) ?: segment.emote, line.at)
            }
        }
    }
    return PreparedLine(text, placeholders, items, mentionsMe, rainbowName, embeds)
}

/**
 * What the settings say about each line (hidden? mentions you?), worked out
 * once per line. A line replaced by a new copy (censored, a combo grown) is
 * judged again.
 */
private class LineFacts {
    private val hidden = HashMap<String, Pair<ChatLine, Boolean>>()
    private val mentions = HashMap<String, Pair<ChatLine, Boolean>>()

    fun hidden(line: ChatLine, test: (ChatLine) -> Boolean) = memo(hidden, line, test)

    fun mentions(line: ChatLine, test: (ChatLine) -> Boolean) = memo(mentions, line, test)

    private fun memo(map: HashMap<String, Pair<ChatLine, Boolean>>, line: ChatLine, test: (ChatLine) -> Boolean): Boolean {
        map[line.id]?.let { (seen, answer) -> if (seen === line) return answer }
        return test(line).also { map[line.id] = line to it }
    }

    /** Forgets lines chat no longer keeps. */
    fun prune(lines: List<ChatLine>) {
        if (hidden.size + mentions.size <= lines.size * 3 + 200) return
        val kept = lines.mapTo(HashSet()) { it.id }
        hidden.keys.retainAll(kept)
        mentions.keys.retainAll(kept)
    }
}

/**
 * Lets a screenful of new rows in a few a frame instead of all at once: in
 * busy chat an update brings a whole screen of new lines, and composing them
 * in one frame took 60–100 ms on a mid-range phone. Only for bursts: a
 * smaller update comes in one go, as it always did. Rows come oldest first,
 * so chat still flows from the bottom; nothing is left out, only held back a
 * frame or two. How many a frame follows how long frames are taking.
 */
private class RowPacer {
    private var newestShown: String? = null

    /**
     * How many of [target]'s newest rows weren't on screen yet. None while
     * reading back (they're out of view), and a backlog that has grown past
     * [MOST_BEHIND] is let in down to one screen, so chat never falls behind.
     */
    fun fresh(target: List<Shown>, following: Boolean): Int {
        val shown = newestShown ?: return 0
        val i = target.indexOfFirst { it.line.id == shown }
        if (i > 0) ChatRendering.arrived(i)
        if (!following) return 0
        return when {
            // Up to half a screen comes at once, as before: pacing that would
            // only move chat more often, which costs more than it saves.
            i <= ONCE -> 0
            i > MOST_BEHIND -> SCREEN
            else -> i
        }
    }

    fun shown(displayed: List<Shown>) {
        newestShown = displayed.firstOrNull()?.line?.id
    }

    /** Fewer rows a frame when frames run long, more when there's room. */
    fun next(perFrame: Int, frameMs: Long): Int = when {
        frameMs > 20 -> (perFrame / 2).coerceAtLeast(1)
        frameMs < 12 -> (perFrame + 1).coerceAtMost(MOST)
        else -> perFrame
    }

    companion object {
        const val START = 4
        private const val MOST = 8
        private const val ONCE = 12
        private const val SCREEN = 30
        private const val MOST_BEHIND = 60
    }
}

/**
 * chat-gui's MentionedUserFormatter: an `@name` (3 to 20 letters, digits or
 * underscores, after a space, the start or `>`, before a space, the end or
 * `.?!,'`) can be tapped whether or not that person is here, the `@` staying
 * outside; a bare name only if they were in chat when the message came
 * ([mentioned]). Tapping one opens their card, as the sender's name does.
 */
internal fun AnnotatedString.Builder.appendMentions(text: String, mentioned: List<String>) {
    val names = mentioned.mapTo(HashSet()) { it.lowercase() }
    var at = 0
    for (m in mentionWord.findAll(text)) {
        val at2 = m.groups[2]!!
        val name = m.groups[3]!!
        val tappable = at2.value == "@" || name.value.lowercase() in names
        if (!tappable) continue
        append(text.substring(at, name.range.first))
        withAnnotation("nick", name.value) { append(name.value) }
        at = name.range.last + 1
    }
    append(text.substring(at))
}

private val mentionWord = Regex("""(^|\s|>)(@?)(\w{3,20})(?=$|\s|[.?!,'])""")

/**
 * Rows prepared and measured ahead, off the main thread: the chat engine
 * calls [prefetch] (through [io.github.strawwaffle.glorp.chat.LinePrefetch])
 * with each batch before the screen gets it, and a row composed for one of
 * those lines takes it if the screen's settings are still the ones it was
 * made with. Anything else (a link being looked up, a message hidden behind
 * <censored>, a new width) is prepared on the main thread as before.
 */
internal object RowPrefetch {
    /** What rows are prepared with: set by the chat screen. */
    class Context(
        val flairs: Flairs, val emotes: Emotes, val density: Density, val me: String?, val mention: Regex?,
        val look: ChatLook, val style: TextStyle, val widthPx: Int, val measurer: androidx.compose.ui.text.TextMeasurer,
    )

    private class Ready(val line: ChatLine, val continued: Boolean, val context: Context, val prepared: PreparedLine)

    @kotlin.concurrent.Volatile
    var context: Context? = null

    @kotlin.concurrent.Volatile
    private var ready: Map<String, Ready> = emptyMap()

    /** How many of the newest lines of each batch are prepared ahead: about a screenful. */
    private const val NEWEST = 30

    fun take(line: ChatLine, continued: Boolean, flairs: Flairs, emotes: Emotes, density: Density, me: String?, look: ChatLook): PreparedLine? {
        val r = ready[line.id] ?: return null
        val c = r.context
        if (r.line !== line || r.continued != continued || c.flairs !== flairs || c.emotes !== emotes || c.me != me || c.look != look ||
            c.density.density != density.density || c.density.fontScale != density.fontScale
        ) return null
        return r.prepared
    }

    /** On a background thread: rows for the newest of [lines] not yet ready. */
    fun prefetch(lines: List<ChatLine>) {
        val c = context ?: return
        if (c.widthPx <= 0) return
        val current = ready
        val out = HashMap<String, Ready>(current.size + NEWEST)
        val from = (lines.size - NEWEST).coerceAtLeast(0)
        for (i in from until lines.size) {
            val line = lines[i]
            val continued = i > 0 && continues(lines[i - 1], line)
            val have = current[line.id]
            if (have != null && have.line === line && have.continued == continued && have.context === c) { out[line.id] = have; continue }
            // Lines whose look depends on something still on its way (a video's title) or a tap (<censored>) are left to the screen.
            if (line.moderated || anyUrl.containsMatchIn(line.text)) continue
            val prepared = prepare(
                line, continued, c.flairs, c.emotes, c.density, c.me, c.mention, c.look, censored = false,
                comboWidth = { count, ended -> comboWidthDp(c.measurer, c.density, count, ended) },
            )
            prepared.measured = c.measurer.measure(
                prepared.text, c.style, placeholders = prepared.placeholders,
                constraints = androidx.compose.ui.unit.Constraints(maxWidth = c.widthPx), density = c.density,
            )
            out[line.id] = Ready(line, continued, c, prepared)
        }
        ready = out
    }
}

