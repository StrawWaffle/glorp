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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import io.github.strawwaffle.glorp.chat.ChatSettings
import io.github.strawwaffle.glorp.chat.SocketState
import io.github.strawwaffle.glorp.chat.highlightWords
import io.github.strawwaffle.glorp.update.Release
import io.github.strawwaffle.glorp.update.UpdateState
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
    /** The catch-up speed; 1 is off. */
    catchUp: Float = 1.5f,
    onToggleCatchUp: () -> Unit = {},
    /** Chat's settings, saved with the account when signed in with cookies. */
    chatSettings: ChatSettings? = null,
    /** Opens a community screen: "rustle", "polls" or "dinkdonk". */
    onCommunity: (String) -> Unit = {},
    unreadWhispers: Int = 0,
    onWhispers: () -> Unit = {},
    /** This build's version, when it updates itself from GitHub; null otherwise. */
    version: String? = null,
    update: UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (Release) -> Unit = {},
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
        // A new version is offered at the top, where it's seen.
        if (update != null && update !is UpdateState.Idle && update !is UpdateState.UpToDate && update !is UpdateState.Checking) {
            item { UpdateItem(update, onInstallUpdate) }
        }
        item { Item("Back to chat", null, onChat) }

        item { Heading("Account") }
        item {
            if (account == null) Item("Sign in", "To chat, whisper and be mentioned", onSignIn)
            else Item("Sign out", "Signed in as ${account.nick}", onSignOut)
        }
        if (account != null) item {
            Item(if (unreadWhispers > 0) "Whispers ($unreadWhispers new)" else "Whispers", null, onWhispers)
        }

        if (chatSettings != null) {
            item { Heading("Chat") }
            item {
                Item(
                    "Timestamps: " + if (chatSettings.showTime) "on" else "off",
                    "The time before each message (/timestampformat changes how it's written)",
                ) { chatSettings.setBool("showtime", !chatSettings.showTime) }
            }
            item {
                Item("Flair icons: " + if (chatSettings.hideFlairIcons) "hidden" else "shown", null) {
                    chatSettings.setBool("hideflairicons", !chatSettings.hideFlairIcons)
                }
            }
            item {
                Item(
                    "Highlight mentions of me: " + if (chatSettings.highlightSelf) "on" else "off",
                    null,
                ) { chatSettings.setBool("highlight", !chatSettings.highlightSelf) }
            }
            item { HighlightWords(chatSettings) }
            item {
                Item(
                    "Tagged users stand out: " + if (chatSettings.taggedVisibility) "on" else "off",
                    "Their messages get a lighter background as well as the colour bar (tag from a name, or /tag)",
                ) { chatSettings.setBool("taggedvisibility", !chatSettings.taggedVisibility) }
            }
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
        item {
            Item(
                "Catch-up speed: " + if (catchUp <= 1f) "off" else "${catchUp.toString().removeSuffix(".0")}×",
                "How fast a live stream that fell behind plays until it's back. Tap to change",
                onToggleCatchUp,
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
        if (version != null) item {
            Item(
                "Glorp $version",
                when (update) {
                    UpdateState.Checking -> "Checking for updates…"
                    UpdateState.UpToDate -> "Up to date"
                    else -> "Check for updates"
                },
                onCheckUpdate,
            )
        }
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
private fun UpdateItem(update: UpdateState, onInstall: (Release) -> Unit) {
    val (title, detail, release) = when (update) {
        is UpdateState.Available -> Triple("Update to Glorp ${update.release.version}", "Downloads from GitHub and asks Android to install it", update.release)
        is UpdateState.Downloading -> Triple("Downloading Glorp ${update.release.version}… ${update.percent}%", null, null)
        is UpdateState.Installing -> Triple("Installing Glorp ${update.release.version}", "Confirm on Android's screen. Glorp closes to update; open it again after.", update.release)
        is UpdateState.NeedsPermission -> Triple(
            "Allow Glorp to install updates",
            "Turn on \"Allow from this source\" in the settings just opened, then tap here again",
            update.release,
        )
        is UpdateState.Failed -> Triple("Update failed", update.why, update.release)
        else -> return
    }
    Column(
        Modifier.fillMaxWidth().background(Palette.Navy)
            .clickable(enabled = release != null) { release?.let(onInstall) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(title, color = Palette.Glorp, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
    }
}

/** `customhighlight`: words that highlight a message, written as the site's settings take them, between commas. */
@Composable
private fun HighlightWords(settings: ChatSettings) {
    val words = settings.list("customhighlight")
    var editing by remember { mutableStateOf<TextFieldValue?>(null) }
    val draft = editing
    if (draft == null) {
        Item(
            "Highlight words",
            if (words.isEmpty()) "None: messages with these words stand out" else words.joinToString(", "),
        ) {
            val text = words.joinToString(", ")
            editing = TextFieldValue(text, TextRange(text.length))
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        settings.setList("customhighlight", highlightWords(draft.text))
        editing = null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Highlight words, between commas", color = Palette.White, fontSize = 15.sp)
        BasicTextField(
            draft,
            onValueChange = { editing = it },
            textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.White),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus)
                .background(Palette.Navy, RoundedCornerShape(6.dp)).padding(10.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { editing = null }) { Text("Cancel", color = Palette.TextDim) }
            TextButton(onClick = ::save) { Text("Save", color = Palette.Blue) }
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
