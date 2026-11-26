package io.github.strawwaffle.glorp.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.foundation.Image
import io.github.strawwaffle.glorp.resources.Res
import io.github.strawwaffle.glorp.resources.glorp_sleeping
import org.jetbrains.compose.resources.painterResource
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
 * The player above chat: what was picked, or what bigscreen would show. The
 * bar under it ([PlayerBar]) picks.
 */
@Composable
fun Bigscreen(
    embed: Embed?,
    playerUrl: String?,
    origin: String,
    live: LiveState,
    resolve: suspend (Embed) -> Resolution,
) {
    val pip = LocalPictureInPicture.current
    val fullscreen = LocalFullscreen.current
    val videoOnly = pip.active || fullscreen.active
    // Video sits on black in every theme; with nothing playing (offline, a
    // note) the area takes the theme's own background.
    val fullBlack = videoOnly
    Column(Modifier.fillMaxWidth().then(if (fullBlack) Modifier.background(Color.Black) else Modifier)) {
        Box(
            Modifier.fillMaxWidth()
                // 16:9 in what's left after the bar below: the full width
                // upright, or as wide as fits beside chat when sideways.
                .then(if (videoOnly) Modifier.fillMaxSize() else Modifier.weight(1f, fill = false).aspectRatio(16f / 9f))
                .onGloballyPositioned { if (!pip.active) pip.setVideoBounds(it.boundsInWindow()) },
            contentAlignment = Alignment.Center,
        ) {
            when {
                embed == null -> Offline(live)
                embed.let(live::isBanned) -> Note("This embed is banned on the site.")
                playerUrl == null -> Note("${PlatformIcons.name(embed.platform)} can't be embedded.")
                else -> Player(embed, playerUrl, origin, resolve)
            }
        }
    }
}

@Composable
private fun Offline(live: LiveState) {
    // Until the site says, nothing is known: not "offline".
    if (!live.known) {
        // A spinner for a few seconds, then still words: no endless animation with no network.
        var waited by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(8_000); waited = true }
        if (!waited) CircularProgressIndicator(color = Palette.Glorp, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        else Note("Waiting for destiny.gg to say what's live…")
        return
    }
    val last = live.streams.firstOrNull { it.title != null }
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Glorp, asleep while there's nothing to watch.
        Image(
            painterResource(Res.drawable.glorp_sleeping),
            contentDescription = "Glorp, asleep",
            modifier = Modifier.size(96.dp).padding(bottom = 8.dp),
        )
        Text("No signal", color = Palette.Glorp, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            if (last != null) "Destiny is offline. Last stream: ${last.title}" else "Destiny is offline.",
            color = VideoText,
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
    // Each result with the embed it's for: picking another embed must not
    // leave the last one playing while (or after) the new one is looked up.
    val looked by produceState<Pair<Embed, Resolution>?>(null, embed, lookups) {
        val found = resolve(embed)
        val before = value?.takeIf { it.first == embed }?.second
        // A failed lookup while this stream was playing natively (no network,
        // say) keeps the player, which tries again, instead of swapping in
        // the web player.
        value = embed to (if (found == Resolution.Web && before is Resolution.Native) before else found)
    }
    when (val found = looked?.takeIf { it.first == embed }?.second) {
        null -> CircularProgressIndicator(color = Palette.Glorp)
        is Resolution.Native -> NativePlayer(found.playable, Modifier.fillMaxSize().background(Color.Black), reload = { lookups++ })
        is Resolution.Offline -> Note("${found.name} is offline.")
        Resolution.Web -> EmbedPlayer(playerUrl, origin, Modifier.fillMaxSize().background(Color.Black))
    }
}

/** Text in the video area when nothing plays, on the theme's background (black in full screen). */
private val VideoText: Color @Composable get() = if (LocalFullscreen.current.active || LocalPictureInPicture.current.active) Color(0xFFB4B8C0) else Palette.TextDim

@Composable
private fun Note(text: String) = Text(text, color = VideoText, fontSize = 13.sp, modifier = Modifier.padding(16.dp))

internal fun label(embed: Embed, live: LiveState): String {
    live.streams.firstOrNull { it.embed == embed }?.let { return "Destiny on ${PlatformIcons.name(embed.platform)}" + (it.title?.let { t -> ": $t" } ?: "") }
    live.embeds.firstOrNull { it.embed.key.equals(embed.key, ignoreCase = true) }?.let { listing ->
        return (listing.displayName ?: embed.key) + (listing.title?.let { ": $it" } ?: "")
    }
    return embed.key
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Entry(
    name: String,
    detail: String?,
    watchers: Int?,
    platform: String? = null,
    /** A note beside the name: whether it notifies, or is muted. */
    mark: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onLongClickLabel = if (onLongClick != null) "Notifications" else null, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (platform != null) PlatformIcon(platform, 20.dp, Modifier.padding(end = 12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = Palette.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                mark?.let { Text(it, color = Palette.Glorp, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp)) }
            }
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
