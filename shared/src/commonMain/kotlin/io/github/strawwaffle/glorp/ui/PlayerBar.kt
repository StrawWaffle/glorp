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
                color = Palette.OnBar.copy(alpha = 0.75f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        if (unreadWhispers != null) Badged(unreadWhispers) { BarIcon(GlorpIcons.Mail, "Whispers", onWhispers) }
        if (embed != null && playerShown) BarIcon(GlorpIcons.Fullscreen, "Full screen", onFullscreen)
        BarIcon(if (playerShown) GlorpIcons.ExpandLess else GlorpIcons.ExpandMore, if (playerShown) "Hide the player" else "Show the player", onTogglePlayer)
    }
    if (listOpen) {
        EmbedList(live, picked, otherStreams, onOtherStreams, onPick = { listOpen = false; onPick(it) }, onDismiss = { listOpen = false })
    }
}

@Composable
private fun BarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = Palette.OnBar, modifier = Modifier.size(22.dp))
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
    onPick: (Embed?) -> Unit,
    onDismiss: () -> Unit,
) {
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
                Entry(listing.displayName ?: listing.embed.key, listing.title ?: listing.embed.key, listing.watchers, listing.embed.platform) {
                    onPick(listing.embed)
                }
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
