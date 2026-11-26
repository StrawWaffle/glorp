package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.OtherStreams
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LiveState
import io.github.strawwaffle.glorp.site.EmbedListing
import io.github.strawwaffle.glorp.notify.BigscreenOptions
import io.github.strawwaffle.glorp.notify.Channel
import io.github.strawwaffle.glorp.notify.ChannelLookup
import io.github.strawwaffle.glorp.notify.Reach
import io.github.strawwaffle.glorp.notify.followablePlatforms
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect

/**
 * The bar between the player and chat, there with the player hidden too: the
 * menu, what's playing (tap for what chat is watching), whispers, full
 * screen, and hiding or showing the player. Icons with 48 dp targets.
 */
@Composable
fun PlayerBar(
    embed: Embed?,
    live: LiveState,
    /** Whether something other than what bigscreen shows was picked. */
    picked: Boolean,
    playerShown: Boolean,
    menuOpen: Boolean,
    /** Null when signed out: no whispers button. */
    unreadWhispers: Int?,
    onMenu: () -> Unit,
    onPick: (Embed?) -> Unit,
    onTogglePlayer: () -> Unit,
    onFullscreen: () -> Unit,
    onWhispers: () -> Unit,
    /** What chat from people watching another stream does; set from the list too. */
    otherStreams: OtherStreams = OtherStreams.Show,
    onOtherStreams: (OtherStreams) -> Unit = {},
    /** Following and muting from the list (long-press); null where there are no notifications. */
    follows: BigscreenFollows? = null,
) {
    var listOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Palette.Bar),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIcon(if (menuOpen) GlorpIcons.Close else GlorpIcons.Menu, if (menuOpen) "Back to chat" else "Menu", onMenu)
        if (embed != null) PlatformIcon(embed.platform, 18.dp, Modifier.padding(end = 8.dp))
        Column(
            Modifier.weight(1f).height(48.dp).clickable(onClickLabel = "What chat is watching") { listOpen = true },
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                embed?.let { label(it, live) } ?: "Nothing on",
                color = Palette.OnBar,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                barNote(picked, if (embed == null) OtherStreams.Show else otherStreams),
                color = Palette.OnBar,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        if (unreadWhispers != null) Badged(unreadWhispers) { BarIcon(GlorpIcons.Mail, "Whispers", onWhispers) }
        if (embed != null && playerShown) BarIcon(GlorpIcons.Fullscreen, "Full screen", onFullscreen)
        BarIcon(if (playerShown) GlorpIcons.ExpandLess else GlorpIcons.ExpandMore, if (playerShown) "Hide the player" else "Show the player", onTogglePlayer)
    }
    if (listOpen) {
        EmbedList(live, picked, otherStreams, onOtherStreams, follows, onPick = { listOpen = false; onPick(it) }, onDismiss = { listOpen = false })
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    // Aerith's bar: each button on a round tile of its own.
    val tile = Palette.BarTile
    Box(Modifier.size(48.dp).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        if (tile != null) Box(Modifier.size(36.dp).background(tile, RoundedCornerShape(10.dp)))
        Icon(icon, contentDescription = label, tint = if (tile != null) Palette.OnBarTile else Palette.OnBar, modifier = Modifier.size(22.dp))
    }
}

/** A count over the corner of [content], when there is one. */
@Composable
private fun Badged(count: Int, content: @Composable () -> Unit) {
    Box {
        content()
        if (count > 0) {
            Text(
                if (count > 99) "99+" else "$count",
                color = Palette.Black,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-4).dp, y = 6.dp)
                    .widthIn(min = 16.dp).background(Palette.Orange, CircleShape).padding(horizontal = 4.dp),
            )
        }
    }
}

/** What chat is watching, in a sheet: Destiny's streams, then the embeds by viewers. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmbedList(
    live: LiveState,
    picked: Boolean,
    otherStreams: OtherStreams,
    onOtherStreams: (OtherStreams) -> Unit,
    follows: BigscreenFollows?,
    onPick: (Embed?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Bumped when a follow or mute changes, to redraw the marks.
    var version by remember { mutableStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Palette.Navy) {
        Text(
            "What chat is watching",
            color = Palette.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Choices(
            "Chat from people watching another stream",
            null,
            OtherStreams.entries,
            otherStreams,
            label = { it.label },
            onPick = onOtherStreams,
        )
        val streams = live.liveStreams
        val embeds = live.embeds.filter { !live.isBanned(it.embed) }
        LazyColumn(Modifier.navigationBarsPadding()) {
            if (picked) item { Entry("Follow bigscreen", "Back to what destiny.gg shows", null) { onPick(null) } }
            items(streams, key = { "d:" + it.embed.key }) { stream ->
                Entry("Destiny on ${PlatformIcons.name(stream.embed.platform)}", stream.title, null, stream.embed.platform) { onPick(stream.embed) }
            }
            items(embeds, key = { "e:" + it.embed.key }) { listing ->
                EmbedEntry(listing, follows, version, onChanged = { version++; follows?.onChanged?.invoke() }) { onPick(listing.embed) }
            }
            if (streams.isEmpty() && embeds.isEmpty()) {
                item { Text("Nothing right now.", color = Palette.TextDim, modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

/** The bar's second line, short enough for a phone held upright. */
private fun barNote(picked: Boolean, otherStreams: OtherStreams): String {
    val others = when (otherStreams) {
        OtherStreams.Show -> null
        OtherStreams.Dim -> "dimmed"
        OtherStreams.Hide -> "hidden"
    }
    return when {
        picked && others != null -> "Picked from chat · others $others"
        others != null -> "Other streams' chat $others"
        picked -> "Picked from chat · tap for more"
        else -> "Tap for what chat is watching"
    }
}

/** What the embeds list needs to follow and mute: the lists, who a video is from, and a word when they change. */
class BigscreenFollows(val options: BigscreenOptions, val lookup: ChannelLookup, val onChanged: () -> Unit)

/**
 * One embed on the list. Long-press: notify about it (on the list, top 3,
 * #1) or mute it. A YouTube video is followed by its channel, looked up
 * when the menu opens if it isn't known yet.
 */
@Composable
private fun EmbedEntry(listing: EmbedListing, follows: BigscreenFollows?, version: Int, onChanged: () -> Unit, onPick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var channel by remember(listing.embed.key) { mutableStateOf(follows?.lookup?.cached(listing.embed, listing)) }
    val embedKey = listing.embed.key.lowercase()
    val key = channel?.key ?: embedKey
    val options = follows?.options
    val followed = remember(version, key) { options?.followOf(key) }
    val muted = remember(version, key) { options != null && (options.isMuted(key) || options.isMuted(embedKey)) }
    val mark = when {
        muted -> "\uD83D\uDD15 muted"
        followed != null -> "\uD83D\uDD14" + when (followed.reach) { Reach.Listed -> ""; Reach.Top3 -> " top 3"; Reach.First -> " #1" }
        else -> null
    }
    Box {
        Entry(
            listing.displayName ?: listing.embed.key, listing.title ?: listing.embed.key, listing.watchers, listing.embed.platform,
            mark = mark,
            onLongClick = if (options != null) ({ menu = true }) else null,
            onClick = onPick,
        )
        if (menu && options != null && follows != null) {
            val followable = listing.embed.platform.lowercase() in followablePlatforms
            if (followable && channel == null) LaunchedEffect(listing.embed.key) { channel = follows.lookup.channelOf(listing.embed, listing) }
            val name = channel?.name ?: listing.displayName ?: listing.embed.id
            val target = channel ?: Channel(embedKey, name)
            fun done() { menu = false; onChanged() }
            DropdownMenu(expanded = true, onDismissRequest = { menu = false }, modifier = Modifier.background(Palette.Navy)) {
                Text(
                    if (followable) "Notify me about $name" else "Notifications for $name",
                    color = Palette.TextDim, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                when {
                    followable && channel == null -> Text("Finding the channel\u2026", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    followable -> {
                        for (reach in Reach.entries) {
                            DropdownMenuItem(
                                text = { Text((if (followed?.reach == reach) "\u2713 " else "") + reach.menu, color = Palette.White) },
                                onClick = { options.follow(target, reach); done() },
                            )
                        }
                        if (followed != null) DropdownMenuItem(text = { Text("Stop following", color = Palette.White) }, onClick = { options.unfollow(key); done() })
                    }
                }
                HorizontalDivider(color = Palette.NavyLight)
                DropdownMenuItem(
                    text = { Text(if (muted) "Unmute" else "Mute it (never notify)", color = Palette.Orange) },
                    onClick = {
                        if (muted) { options.unmute(key); options.unmute(embedKey) } else options.mute(target)
                        done()
                    },
                )
            }
        }
    }
}
