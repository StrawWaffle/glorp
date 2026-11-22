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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LiveState
import io.github.strawwaffle.glorp.video.Playable
import io.github.strawwaffle.glorp.video.Resolution
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.produceState

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
    resolve: suspend (Embed) -> Resolution,
) {
    var listOpen by remember { mutableStateOf(false) }
    val pip = LocalPictureInPicture.current
    val fullscreen = LocalFullscreen.current
    val videoOnly = pip.active || fullscreen.active
    Column(Modifier.fillMaxWidth().background(Palette.Black)) {
        Box(
            Modifier.fillMaxWidth()
                // 16:9 in what's left after the bar below: the full width
                // upright, or as wide as fits beside chat when sideways.
                .then(if (videoOnly) Modifier.fillMaxSize() else Modifier.weight(1f, fill = false).aspectRatio(16f / 9f))
                .background(Color.Black)
                .onGloballyPositioned { if (!pip.active) pip.setVideoBounds(it.boundsInWindow()) },
            contentAlignment = Alignment.Center,
        ) {
            when {
                embed == null -> Offline(live)
                embed.let(live::isBanned) -> Note("This embed is banned on the site.")
                playerUrl == null -> Note("${embed.platform} can't be embedded.")
                else -> Player(embed, playerUrl, origin, resolve)
            }
        }
        if (videoOnly) return@Column
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
            if (embed != null) TextButton(onClick = { fullscreen.set(true) }) { Text("Full screen", color = Palette.Blue, fontSize = 13.sp) }
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

/**
 * The app's own player where the embed can be resolved to a stream, the
 * platform's web player where it can't.
 */
@Composable
private fun Player(embed: Embed, playerUrl: String, origin: String, resolve: suspend (Embed) -> Resolution) {
    // Bumped when the player gives up on the stream, to look it up again:
    // a fresh address, or the news that it ended.
    var lookups by remember(embed) { mutableIntStateOf(0) }
    val resolution by produceState<Resolution?>(null, embed, lookups) {
        val found = resolve(embed)
        // A failed lookup while the stream was playing natively (no network,
        // say) keeps the player, which tries again, instead of swapping in
        // the web player.
        value = if (found == Resolution.Web && value is Resolution.Native) value else found
    }
    when (val found = resolution) {
        null -> CircularProgressIndicator(color = Palette.Glorp)
        is Resolution.Native -> NativePlayer(found.playable, Modifier.fillMaxSize(), reload = { lookups++ })
        is Resolution.Offline -> Note("${found.name} is offline.")
        Resolution.Web -> EmbedPlayer(playerUrl, origin, Modifier.fillMaxSize())
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
internal fun Entry(name: String, detail: String?, watchers: Int?, onClick: () -> Unit) {
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

/** Plays [playable] in the platform's own video player, only while the app is on screen. */
@Composable
internal expect fun NativePlayer(playable: Playable, modifier: Modifier, reload: () -> Unit)
