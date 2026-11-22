package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.SocketState
import io.github.strawwaffle.glorp.site.Flairs

@Composable
fun ChatScreen(lines: List<ChatLine>, flairs: Flairs, state: SocketState) {
    Column(Modifier.fillMaxSize().background(Palette.Black).safeDrawingPadding()) {
        StatusBar(state)
        // Newest at the bottom. A reversed list stays pinned to the newest line
        // while you're at the bottom, and keeps your place (by key) when you've
        // scrolled up to read back. Only the rows on screen are composed.
        val reversed = remember(lines) { lines.asReversed() }
        LazyColumn(Modifier.fillMaxSize(), reverseLayout = true) {
            items(reversed, key = { it.id }, contentType = { if (it.isBroadcast) 1 else 0 }) { line ->
                ChatRow(line, flairs)
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

@Composable
private fun ChatRow(line: ChatLine, flairs: Flairs) {
    val text = remember(line, flairs) { render(line, flairs) }
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        color = Palette.White,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )
}

private fun render(line: ChatLine, flairs: Flairs): AnnotatedString = buildAnnotatedString {
    if (line.isBroadcast) {
        withStyle(SpanStyle(color = Palette.Orange, fontWeight = FontWeight.Bold)) { append(line.text) }
        return@buildAnnotatedString
    }
    val nameColor = flairs.nameColor(line.features)?.let { Color(it) } ?: Palette.White
    withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Bold)) { append(line.nick) }
    append(if (line.action) " " else ": ")
    val style = when {
        line.action -> SpanStyle(fontStyle = FontStyle.Italic, color = nameColor)
        line.green -> SpanStyle(color = Palette.Greentext)
        else -> SpanStyle()
    }
    withStyle(style) { append(line.text) }
}
