package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LiveState

/**
 * The player above chat: what was picked, or what bigscreen would show, with
 * the list of what chat is watching one tap away.
 */
@Composable
fun Bigscreen(
    embed: Embed?,
    playerUrl: String?,
    origin: String,
    live: LiveState,
    picked: Boolean,
    onPick: (Embed?) -> Unit,
    onHide: () -> Unit,
) {
    var listOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Palette.Black)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                embed == null -> Offline(live)
                embed.let(live::isBanned) -> Note("This embed is banned on the site.")
                playerUrl == null -> Note("${embed.platform} can't be embedded.")
                else -> EmbedPlayer(playerUrl, origin, Modifier.fillMaxSize())
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Palette.Navy).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                embed?.let { label(it, live) } ?: "Nothing on",
                color = Palette.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (picked) TextButton(onClick = { onPick(null) }) { Text("Destiny", color = Palette.Blue, fontSize = 13.sp) }
            TextButton(onClick = { listOpen = true }) { Text("Embeds", color = Palette.Blue, fontSize = 13.sp) }
            TextButton(onClick = onHide) { Text("Hide", color = Palette.TextDim, fontSize = 13.sp) }
        }
    }
    if (listOpen) EmbedList(live, onPick = { listOpen = false; onPick(it) }, onDismiss = { listOpen = false })
}

@Composable
private fun Offline(live: LiveState) {
    val last = live.streams.firstOrNull { it.title != null }
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No signal", color = Palette.Glorp, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            if (last != null) "Destiny is offline. Last stream: ${last.title}" else "Destiny is offline.",
            color = Palette.TextDim,
            fontSize = 13.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Note(text: String) = Text(text, color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))

private fun label(embed: Embed, live: LiveState): String {
    live.streams.firstOrNull { it.embed == embed }?.let { return "Destiny on ${embed.platform}" + (it.title?.let { t -> ": $t" } ?: "") }
    live.embeds.firstOrNull { it.embed.key.equals(embed.key, ignoreCase = true) }?.let { listing ->
        return (listing.displayName ?: embed.key) + (listing.title?.let { ": $it" } ?: "")
    }
    return embed.key
}

@Composable
private fun EmbedList(live: LiveState, onPick: (Embed) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp)
                .background(Palette.Navy, RoundedCornerShape(10.dp)).padding(vertical = 8.dp),
        ) {
            Text(
                "What chat is watching",
                color = Palette.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            val streams = live.liveStreams
            val embeds = live.embeds.filter { !live.isBanned(it.embed) }
            if (streams.isEmpty() && embeds.isEmpty()) {
                Text("Nothing right now.", color = Palette.TextDim, modifier = Modifier.padding(16.dp))
            }
            LazyColumn {
                items(streams, key = { "d:" + it.embed.key }) { stream ->
                    Entry("Destiny on ${stream.embed.platform}", stream.title, null) { onPick(stream.embed) }
                }
                items(embeds, key = { "e:" + it.embed.key }) { listing ->
                    Entry(
                        listing.displayName ?: listing.embed.key,
                        listing.title ?: listing.embed.key,
                        listing.watchers,
                    ) { onPick(listing.embed) }
                }
            }
        }
    }
}

@Composable
private fun Entry(name: String, detail: String?, watchers: Int?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Palette.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        watchers?.let { Text("$it", color = Palette.Glorp, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp)) }
    }
}

/**
 * The platform's own player for [url], in a page whose origin is the site's
 * ([origin]), the way bigscreen embeds it: YouTube and Twitch check where
 * they're embedded. It plays only while the app is on screen.
 */
@Composable
internal expect fun EmbedPlayer(url: String, origin: String, modifier: Modifier)
