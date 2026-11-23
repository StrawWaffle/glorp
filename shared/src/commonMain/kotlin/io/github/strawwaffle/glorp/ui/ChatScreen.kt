package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.chat.ChatInputState
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.ChatViewModel
import io.github.strawwaffle.glorp.chat.Highlighter
import io.github.strawwaffle.glorp.chat.LineFilter
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
    lines: List<ChatLine>,
    flairs: Flairs,
    emotes: Emotes,
    state: SocketState,
    account: Account?,
    onSend: (String) -> Boolean,
    /** The input's draft, suggestions and lists, kept by the view model. */
    input: ChatInputState,
    onEmbed: (Embed) -> Unit,
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
            poll, onVote, onClosePoll, pinned, onShowPin,
        )
    }
    val bodySlot = remember { movableContentOf { currentBody() } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Black)) {
        val wide = maxWidth > maxHeight && player != null
        val chatWidth = (maxWidth * 0.38f).coerceIn(280.dp, 420.dp)
        when {
            // Only the video: the floating window, or the whole screen.
            pip || (fullscreen && player != null) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { playerSlot() }
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
    lines: List<ChatLine>,
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
) {
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
        val reversed = remember(lines, filter) {
            val shownLines = if (filter.isEmpty) lines else lines.filter { !filter.hides(it) }
            shownLines.mapIndexed { i, line -> Shown(line, i > 0 && continues(shownLines[i - 1], line)) }.asReversed()
        }
        val mention = remember(me) { me?.let(::mentionPattern) }
        val embedHandler = rememberUpdatedState(onEmbed)
        val openEmbed: (Embed) -> Unit = remember { { embedHandler.value(it) } }
        // Tapping a name, or holding a message, opens the sheet about that person.
        val tapNick: (String) -> Unit = remember(input) { { input.openUser(it) } }
        val holdLine: (ChatLine) -> Unit = remember(input) { { line -> line.nick?.let { input.openUser(it, line) } } }
        input.tappedNick?.let { nick ->
            UserSheet(nick, input.tappedLine, flairs, account, input, userActions, onDismiss = input::closeUser)
        }
        // Highlights by name and by word; mentions of you are the row's own business.
        val highlightNicks = input.highlighted.nicks
        val words = input.settings.list("customhighlight")
        val highlighter = remember(me, highlightNicks, words) { Highlighter(me, self = false, highlightNicks, words) }
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
        LaunchedEffect(reversed) { if (following) list.scrollToItem(0) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            if (reversed.isEmpty()) {
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
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                items(reversed, key = { it.line.id }, contentType = { it.line.kind }) { shown ->
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
            val look = LocalChatLook.current
            var closedMention by remember { mutableStateOf<String?>(null) }
            val latest = remember(reversed, mention, me, look.highlightSelf) {
                if (mention == null || !look.highlightSelf) null
                else reversed.indexOfFirst { s ->
                    s.line.kind == ChatLine.Kind.User && !s.line.nick.equals(me, ignoreCase = true) && mention.containsMatchIn(s.line.text)
                }.takeIf { it >= 0 }
            }
            val sticky by remember(latest) {
                derivedStateOf {
                    // Above what's on screen: older than the oldest row shown.
                    latest != null && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE) < latest
                }
            }
            val stuck = latest?.let { reversed.getOrNull(it)?.line }
            if (input.options.stickyMentions && sticky && stuck != null && stuck.id != closedMention) {
                val scope = rememberCoroutineScope()
                StickyMention(
                    stuck,
                    Modifier.align(Alignment.TopCenter),
                    onShow = { scope.launch { list.animateScrollToItem(latest); following = false } },
                    onClose = { closedMention = stuck.id },
                )
            }
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
        }
        if (account != null) {
            ChatInput(input, emotes, account, connected = state == SocketState.Connected, onSend)
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
)

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

private val taggedBackground = Color(0xFF232323)

/** Whether a tap on a row's name or link should count: not after a hold. */
private class HoldGate {
    var open = true
}

val LocalChatLook = staticCompositionLocalOf { ChatLook() }

/** A line laid out once per change of its inputs, with pictures as inline content. */
private class RenderedLine(val text: AnnotatedString, val inline: Map<String, InlineTextContent>, val mentionsMe: Boolean)

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
    val density = LocalDensity.current
    val look = LocalChatLook.current
    val uri = LocalUriHandler.current
    // A press held long enough to open the sheet isn't also a tap on the
    // name or link under it, which would fire when the finger lifts.
    val gate = remember { HoldGate() }
    // A muted or banned user's message hides behind <censored> until tapped, as on the site.
    var revealed by remember(line.id) { mutableStateOf(false) }
    val censored = line.moderated && look.censorRemoved && !revealed
    val rendered = remember(line, continued, flairs, emotes, density, me, look, censored) {
        render(
            line, continued, flairs, emotes, density, me, mention, look, censored,
            onReveal = { revealed = true },
            onEmbed = { if (gate.open) onEmbed(it) },
            onNick = { if (gate.open) onNick(it) },
            onUrl = { if (gate.open) runCatching { uri.openUri(it) } },
        )
    }
    // A tagged user's lines get a bar of their colour down the left, as chat-gui draws them.
    val tag = if (line.kind == ChatLine.Kind.User) line.nick?.lowercase()?.let { look.tags[it] } else null
    val background = when {
        line.kind == ChatLine.Kind.Whisper -> Palette.Blue.copy(alpha = 0.12f)
        (rendered.mentionsMe && look.highlightSelf) || highlighted -> Palette.Orange.copy(alpha = 0.14f)
        tag != null && look.taggedVisibility -> taggedBackground
        else -> Color.Transparent
    }
    val bar = tag?.let(::tagColor)
    Text(
        rendered.text,
        inlineContent = rendered.inline,
        modifier = Modifier.fillMaxWidth().background(background)
            .then(if (bar == null) Modifier else Modifier.drawBehind { drawRect(bar, size = Size(4.dp.toPx(), size.height)) })
            .then(
                if (onHold == null) Modifier
                else Modifier.pointerInput(line) {
                    detectTapGestures(
                        onPress = { gate.open = true },
                        onLongPress = { gate.open = false; onHold(line) },
                    )
                },
            )
            .padding(horizontal = 10.dp, vertical = 2.dp),
        color = Palette.White,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

private val linkStyle = TextLinkStyles(SpanStyle(color = Palette.Blue, textDecoration = TextDecoration.Underline))

// A name is tappable but looks like a name, not a link.
private val nickStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.None))

/** Matches [nick] as a word, the way chat highlights mentions. Built once per name. */
internal fun mentionPattern(nick: String) =
    Regex("""(^|[^\w])${Regex.escape(nick)}($|[^\w])""", RegexOption.IGNORE_CASE)

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

    val text = buildAnnotatedString {
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
                        InlineTextContent(placeholder(emote.width, emote.height)) { EmoteImage(emote, sentAt = line.at) }
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
            ChatLine.Kind.User -> Unit
        }
        mentionsMe = mention != null && !line.nick.equals(me, ignoreCase = true) && mention.containsMatchIn(line.text)
        val nameColor = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
        if (continued) {
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
            withLink(LinkAnnotation.Clickable("nick", nickStyle) { onNick(line.nick!!) }) {
                withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(line.nick) }
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
            line.action -> SpanStyle(fontStyle = FontStyle.Italic, color = nameColor)
            line.green -> SpanStyle(color = Palette.Greentext)
            else -> SpanStyle()
        }
        val parts = segments(line, emotes)
        // How each emote looks beside the others (BLADE next to BLADE…).
        val placed = emotes.inLine(
            LineModel.message(
                line.features, flairs.nameFlair(line.features), continued, line.green && !line.action,
                elementsOf(parts),
            ),
        )
        var emoteIndex = 0
        withStyle(style) {
            for (segment in parts) when (segment) {
                is Segment.Text -> append(segment.text)
                is Segment.Link -> withLink(LinkAnnotation.Clickable(segment.url, linkStyle) { onUrl(segment.url) }) { append(segment.text) }
                is Segment.EmbedLink -> withLink(
                    LinkAnnotation.Clickable(segment.embed.key, linkStyle) { onEmbed(segment.embed) },
                ) { append(segment.text) }
                is Segment.EmoteRef -> {
                    val emote = placed.getOrNull(emoteIndex++) ?: segment.emote
                    val id = "emote:${emote.prefix}:${emote.hashCode()}"
                    inline.getOrPut(id) {
                        InlineTextContent(placeholder(emote.width, emote.height)) {
                            EmoteImage(emote, sentAt = line.at)
                        }
                    }
                    appendInlineContent(id, emote.prefix)
                }
            }
        }
    }
    return RenderedLine(text, inline, mentionsMe)
}
