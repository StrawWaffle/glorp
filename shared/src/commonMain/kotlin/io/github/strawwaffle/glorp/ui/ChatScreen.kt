package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.Segment
import io.github.strawwaffle.glorp.chat.SocketState
import io.github.strawwaffle.glorp.chat.segments
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs

@Composable
fun ChatScreen(
    lines: List<ChatLine>,
    flairs: Flairs,
    emotes: Emotes,
    state: SocketState,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSend: (String) -> Boolean,
) {
    Column(Modifier.fillMaxSize().background(Palette.Black).safeDrawingPadding().imePadding()) {
        TopBar(state, account, onSignIn, onSignOut)
        // Newest at the bottom, in a reversed list: only the rows on screen are
        // composed, so only their emotes are drawn or animated.
        val reversed = remember(lines) { lines.asReversed() }
        val me = account?.nick
        val mention = remember(me) { me?.let(::mentionPattern) }
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
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                items(reversed, key = { it.id }, contentType = { it.kind }) { line ->
                    ChatRow(line, flairs, emotes, me, mention)
                }
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
        if (account != null) MessageBox(enabled = state == SocketState.Connected, onSend = onSend)
    }
}

@Composable
private fun TopBar(state: SocketState, account: Account?, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val (text, color) = when (state) {
        SocketState.Connected -> "Receiving transmission" to Palette.Glorp
        SocketState.Connecting -> "Establishing contact…" to Palette.TextDim
        SocketState.Waiting -> "Signal lost, retrying" to Palette.Orange
        SocketState.Stopped -> "Transmission ended" to Palette.Orange
    }
    Row(
        Modifier.fillMaxWidth().background(Palette.Navy).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 12.sp, modifier = Modifier.weight(1f))
        if (account == null) {
            TextButton(onClick = onSignIn) { Text("Sign in", color = Palette.Blue, fontSize = 13.sp) }
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { open = true }) { Text(account.nick, color = Palette.White, fontSize = 13.sp) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = { open = false; onSignOut() })
                }
            }
        }
    }
}

@Composable
private fun MessageBox(enabled: Boolean, onSend: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    val send = { if (onSend(text)) text = "" }
    Row(
        Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { if (it.length <= MAX_MESSAGE) text = it },
            textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.Blue),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            modifier = Modifier.weight(1f)
                .background(Palette.Black, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 9.dp),
            decorationBox = { field ->
                if (text.isEmpty()) Text("Write something", color = Palette.TextDim, fontSize = 15.sp)
                field()
            },
        )
        TextButton(onClick = send, enabled = enabled && text.isNotBlank()) {
            Text("Send", color = if (enabled && text.isNotBlank()) Palette.Blue else Palette.TextDim)
        }
    }
}

private const val MAX_MESSAGE = 512

/** A line laid out once per change of its inputs, with pictures as inline content. */
private class RenderedLine(val text: AnnotatedString, val inline: Map<String, InlineTextContent>, val mentionsMe: Boolean)

@Composable
private fun ChatRow(line: ChatLine, flairs: Flairs, emotes: Emotes, me: String?, mention: Regex?) {
    val density = LocalDensity.current
    val rendered = remember(line, flairs, emotes, density, me) { render(line, flairs, emotes, density, me, mention) }
    val background = when {
        line.kind == ChatLine.Kind.Whisper -> Palette.Blue.copy(alpha = 0.12f)
        rendered.mentionsMe -> Palette.Orange.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    Text(
        rendered.text,
        inlineContent = rendered.inline,
        modifier = Modifier.fillMaxWidth().background(background).padding(horizontal = 10.dp, vertical = 2.dp),
        color = Palette.White,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

private val linkStyle = TextLinkStyles(SpanStyle(color = Palette.Blue, textDecoration = TextDecoration.Underline))

/** Matches [nick] as a word, the way chat highlights mentions. Built once per name. */
internal fun mentionPattern(nick: String) =
    Regex("""(^|[^\w])${Regex.escape(nick)}($|[^\w])""", RegexOption.IGNORE_CASE)

internal fun mentions(text: String, nick: String): Boolean = mentionPattern(nick).containsMatchIn(text)

private fun render(line: ChatLine, flairs: Flairs, emotes: Emotes, density: Density, me: String?, mention: Regex?): RenderedLine {
    val inline = HashMap<String, InlineTextContent>()
    fun placeholder(widthDp: Int, heightDp: Int) = with(density) {
        Placeholder(widthDp.dp.toSp(), heightDp.dp.toSp(), PlaceholderVerticalAlign.Center)
    }
    var mentionsMe = false

    val text = buildAnnotatedString {
        when (line.kind) {
            ChatLine.Kind.Broadcast -> {
                withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold)) { append(line.text) }
                return@buildAnnotatedString
            }
            ChatLine.Kind.Info -> {
                withStyle(SpanStyle(color = Palette.TextDim, fontStyle = FontStyle.Italic)) { append(line.text) }
                return@buildAnnotatedString
            }
            ChatLine.Kind.Whisper -> {
                withStyle(SpanStyle(color = Palette.Blue)) { append("Whisper from ") }
                withStyle(SpanStyle(color = Palette.White, fontWeight = FontWeight.Bold)) { append(line.nick) }
                append(": ")
                append(line.text)
                return@buildAnnotatedString
            }
            ChatLine.Kind.User -> Unit
        }
        mentionsMe = mention != null && !line.nick.equals(me, ignoreCase = true) && mention.containsMatchIn(line.text)
        for (badge in flairs.badges(line.features)) {
            val id = "badge:${badge.name}"
            inline.getOrPut(id) {
                InlineTextContent(placeholder(badge.width, badge.height)) {
                    AsyncImage(badge.url, contentDescription = badge.label, modifier = Modifier.size(badge.width.dp, badge.height.dp))
                }
            }
            appendInlineContent(id, badge.label)
            append(' ')
        }
        val nameColor = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(line.nick) }
        append(if (line.action) " " else ": ")

        val style = when {
            line.action -> SpanStyle(fontStyle = FontStyle.Italic, color = nameColor)
            line.green -> SpanStyle(color = Palette.Greentext)
            else -> SpanStyle()
        }
        withStyle(style) {
            for (segment in segments(line, emotes)) when (segment) {
                is Segment.Text -> append(segment.text)
                is Segment.Link -> withLink(LinkAnnotation.Url(segment.url, linkStyle)) { append(segment.text) }
                is Segment.EmoteRef -> {
                    val emote = segment.emote
                    val id = "emote:${emote.prefix}"
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
