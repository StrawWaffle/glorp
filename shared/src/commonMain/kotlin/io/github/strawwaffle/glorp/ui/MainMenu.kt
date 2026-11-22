package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.SocketState
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LiveState

/**
 * The menu, reached with Back from chat. It takes chat's place under the
 * player, so whatever is playing keeps playing.
 */
@Composable
fun MainMenu(
    host: String,
    state: SocketState,
    account: Account?,
    live: LiveState,
    playerShown: Boolean,
    picked: Embed?,
    onChat: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onWatch: (Embed?) -> Unit,
    onTogglePlayer: () -> Unit,
    backgroundAudio: Boolean = false,
    onToggleBackgroundAudio: () -> Unit = {},
    /** Opens a community screen: "rustle", "polls" or "dinkdonk". */
    onCommunity: (String) -> Unit = {},
) {
    val streams = live.liveStreams
    val embeds = live.embeds.filter { !live.isBanned(it.embed) }
    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Glorp", color = Palette.Glorp, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                val status = when (state) {
                    SocketState.Connected -> "Receiving transmission from $host"
                    SocketState.Connecting -> "Establishing contact with $host…"
                    SocketState.Waiting -> "Signal lost, retrying"
                    SocketState.Stopped -> "Transmission ended"
                }
                Text(status, color = Palette.TextDim, fontSize = 13.sp)
            }
        }
        item { Item("Back to chat", null, onChat) }

        item { Heading("Account") }
        item {
            if (account == null) Item("Sign in", "To chat, whisper and be mentioned", onSignIn)
            else Item("Sign out", "Signed in as ${account.nick}", onSignOut)
        }

        item { Heading("Watch") }
        item { Item(if (playerShown) "Hide the player" else "Show the player", null, onTogglePlayer) }
        item {
            Item(
                if (backgroundAudio) "Sound with the screen off: on" else "Sound with the screen off: off",
                "Kick, Twitch and YouTube live keep playing their sound when you lock the phone",
                onToggleBackgroundAudio,
            )
        }
        if (picked != null) item { Item("Back to Destiny", "Follow what bigscreen shows", { onWatch(null) }) }
        items(streams, key = { "d:" + it.embed.key }) { stream ->
            Entry("Destiny on ${stream.embed.platform}", stream.title, null) { onWatch(stream.embed) }
        }
        if (embeds.isNotEmpty()) item { Heading("What chat is watching") }
        items(embeds, key = { "e:" + it.embed.key }) { listing ->
            Entry(listing.displayName ?: listing.embed.key, listing.title ?: listing.embed.key, listing.watchers) {
                onWatch(listing.embed)
            }
        }
        if (streams.isEmpty() && embeds.isEmpty()) {
            item { Text("Nothing live right now.", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        }

        item { Heading("Community") }
        item { Item("Chat log search", "rustlesearch.dev", { onCommunity("rustle") }) }
        item { Item("Poll archive", "mitchdev.net", { onCommunity("polls") }) }
        item { Item("Movie nights", "dinkdonk.mov", { onCommunity("dinkdonk") }) }

        item { Heading("About") }
        item {
            Text(
                "Glorp is an unofficial community app. It is not made by or affiliated with destiny.gg or Destiny. " +
                    "Free for non-commercial use (PolyForm Noncommercial 1.0.0). YouTube playback uses FUTO's Grayjay plugin, " +
                    "run by code adapted from Grayjay under FUTO's Source First License 1.1. " +
                    "Community screens use rustlesearch.dev, mitchdev.net (by Mitchdev) and dinkdonk.mov (by Mitch), with thanks.",
                color = Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text.uppercase(),
    color = Palette.TextDim,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
)

@Composable
private fun Item(title: String, detail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, color = Palette.White, fontSize = 15.sp)
            detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        }
    }
}

/** The system back button or gesture, where the platform has one. */
@Composable
internal expect fun OnBack(enabled: Boolean, onBack: () -> Unit)
