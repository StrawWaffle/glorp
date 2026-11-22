package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
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
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs

@Composable
fun ChatScreen(lines: List<ChatLine>, flairs: Flairs, emotes: Emotes, state: SocketState) {
    Column(Modifier.fillMaxSize().background(Palette.Black).safeDrawingPadding()) {
        StatusBar(state)
        // Newest at the bottom. A reversed list stays pinned to the newest line
        // while you're at the bottom, and keeps your place (by key) when you've
        // scrolled up to read back. Only the rows on screen are composed, so
        // only their emotes are drawn or animated.
        val reversed = remember(lines) { lines.asReversed() }
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
            items(reversed, key = { it.id }, contentType = { if (it.isBroadcast) 1 else 0 }) { line ->
                ChatRow(line, flairs, emotes)
            }
        }
    }
}

@Composable
private fun StatusBar(state: SocketState) {
    val (text, color) = when (state) {
        SocketState.Connected -> "Receiving transmission" to Palette.Glorp
        SocketState.Connecting -> "Establishing contact…" to Palette.TextDim
        SocketState.Waiting -> "Signal lost, retrying" to Palette.Orange
    }
    Row(Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

/** A line laid out once per change of its inputs, with pictures as inline content. */
private class RenderedLine(val text: AnnotatedString, val inline: Map<String, InlineTextContent>)

@Composable
private fun ChatRow(line: ChatLine, flairs: Flairs, emotes: Emotes) {
    val density = LocalDensity.current
    val rendered = remember(line, flairs, emotes, density) { render(line, flairs, emotes, density) }
    Text(
        rendered.text,
        inlineContent = rendered.inline,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        color = Palette.White,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

private val linkStyle = TextLinkStyles(SpanStyle(color = Palette.Blue, textDecoration = TextDecoration.Underline))

private fun render(line: ChatLine, flairs: Flairs, emotes: Emotes, density: Density): RenderedLine {
    val inline = HashMap<String, InlineTextContent>()
    fun placeholder(widthDp: Int, heightDp: Int) = with(density) {
        Placeholder(widthDp.dp.toSp(), heightDp.dp.toSp(), PlaceholderVerticalAlign.Center)
    }

    val text = buildAnnotatedString {
        if (line.isBroadcast) {
            withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold)) { append(line.text) }
            return@buildAnnotatedString
        }
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
    return RenderedLine(text, inline)
}
