package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.strawwaffle.glorp.resources.Res
import io.github.strawwaffle.glorp.resources.menu_dinkdonk
import io.github.strawwaffle.glorp.resources.menu_linkers
import io.github.strawwaffle.glorp.site.Emotes
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
 * The menu: places to go. Reached from the bar's menu button or with Back
 * from chat; it takes chat's place under the player, so whatever is playing
 * keeps playing. Settings have their own screen.
 */
@Composable
fun MainMenu(
    host: String,
    state: SocketState,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onSettings: () -> Unit,
    /** Opens a community screen: "rustle", "polls", "dinkdonk", "mentions" or "links". */
    onCommunity: (String) -> Unit = {},
    /** For the emote polecat.me uses as its icon; a plain @ until they've loaded. */
    emotes: Emotes? = null,
    unreadWhispers: Int = 0,
    onWhispers: () -> Unit = {},
    /** This build's version, when it updates itself from GitHub; null otherwise. */
    version: String? = null,
    update: UpdateState? = null,
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: (Release) -> Unit = {},
    /** Null where problems can't be reported from the app. */
    onReport: (() -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 16.dp)) {
                    Text("Glorp", color = Palette.Glorp, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    val status = when (state) {
                        SocketState.Connected -> "Receiving transmission from $host"
                        SocketState.Connecting -> "Establishing contact with $host…"
                        SocketState.Waiting -> "Signal lost, retrying"
                        SocketState.Stopped -> "Transmission ended"
                    }
                    Text(status, color = Palette.TextDim, fontSize = 13.sp)
                }
                // The version and the update check, where they're seen first.
                if (version != null) {
                    Column(
                        Modifier.heightIn(min = 48.dp).clickable(onClickLabel = "Check for updates", onClick = onCheckUpdate)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            when (update) {
                                UpdateState.Checking -> "Checking…"
                                UpdateState.UpToDate -> "Up to date"
                                else -> "Check for updates"
                            },
                            color = Palette.Blue,
                            fontSize = 13.sp,
                        )
                        Text("v$version", color = Palette.TextDim, fontSize = 12.sp)
                    }
                }
            }
        }
        // A new version is offered at the top, where it's seen.
        if (update != null && update !is UpdateState.Idle && update !is UpdateState.UpToDate && update !is UpdateState.Checking) {
            item { UpdateItem(update, onInstallUpdate) }
        }
        item { Heading("Account") }
        item {
            if (account == null) Item("Sign in", "To chat, whisper and be mentioned", onSignIn) { Vector(GlorpIcons.Login) }
            else Item("Sign out", "Signed in as ${account.nick}", onSignOut) { Vector(GlorpIcons.Logout) }
        }
        if (account != null) item {
            Item(if (unreadWhispers > 0) "Whispers ($unreadWhispers new)" else "Whispers", null, onWhispers) { Vector(GlorpIcons.Mail) }
        }

        item { Heading("Community") }
        item { Item("Mentions", if (account != null) "Messages naming you, from polecat.me" else "Messages naming someone, from polecat.me", { onCommunity("mentions") }) { EmoteIcon(emotes, "FerretLOL", GlorpIcons.At) } }
        item { Item("Chat log search", "rustlesearch.dev", { onCommunity("rustle") }) { Vector(GlorpIcons.Search) } }
        item { Item("Links", "Posted in chat, from linkers.ooo", { onCommunity("links") }) { Picture(Res.drawable.menu_linkers) } }
        item { Item("Poll archive", "mitchdev.net", { onCommunity("polls") }) { Vector(GlorpIcons.Poll) } }
        item { Item("Movie nights", "dinkdonk.mov", { onCommunity("dinkdonk") }) { Picture(Res.drawable.menu_dinkdonk) } }

        item { Heading("App") }
        item { Item("Settings", "Timestamps, highlights and tags, the player", onSettings) { Vector(GlorpIcons.Settings) } }
        if (onReport != null) item { Item("Report a problem", "Opens a GitHub issue with the details filled in, for you to check and send", onReport) { Vector(GlorpIcons.Bug) } }
        item { Heading("About") }
        item {
            Text(
                "Glorp is an unofficial community app, not made by or affiliated with destiny.gg or Destiny. " +
                    "Free for non-commercial use (PolyForm Noncommercial 1.0.0).",
                color = Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item { Heading("Thanks") }
        items(credits) { Credit(it) }
        item { Spacer(Modifier.height(24.dp)) }
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

@Composable
private fun Heading(text: String) = Text(
    text.uppercase(),
    color = Palette.TextDim,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
)

/** Every entry has a 24 dp icon, so their text lines up: the site's own where it has one, else an outline. */
@Composable
private fun Item(title: String, detail: String?, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
        Column {
            Text(title, color = Palette.White, fontSize = 15.sp)
            detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun Vector(vector: ImageVector) = Icon(vector, contentDescription = null, tint = Palette.TextDim, modifier = Modifier.size(24.dp))

/** A site's own icon, shipped with the app (nothing is fetched to show the menu). */
@Composable
private fun Picture(resource: DrawableResource) =
    Image(painterResource(resource), contentDescription = null, modifier = Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)))

/** A site whose icon is a dgg emote: drawn still, from the emotes chat already has. */
@Composable
private fun EmoteIcon(emotes: Emotes?, name: String, fallback: ImageVector) {
    val emote = emotes?.get(name) ?: return Vector(fallback)
    val scale = minOf(24f / emote.width.coerceAtLeast(1), 24f / emote.height.coerceAtLeast(1))
    EmoteImage(emote, sentAt = 0, modifier = Modifier.size((emote.width * scale).dp, (emote.height * scale).dp))
}

/** Who Glorp builds on, what for, and where they are. */
private class Thanks(val who: String, val what: String, val link: String?)

private val credits = listOf(
    Thanks("polecat.me", "Mentions", "https://polecat.me/mentions"),
    Thanks("rustlesearch.dev", "Chat log search", "https://rustlesearch.dev/"),
    Thanks("linkers.ooo, by Pizza", "Links", "https://linkers.ooo/"),
    Thanks("mitchdev.net, by Mitch", "Poll archive", "https://mitchdev.net/dgg/"),
    Thanks("dinkdonk.mov, by Mitch", "Movie nights", "https://dinkdonk.mov/"),
    Thanks("vyneer.me, by vyneer", "Nuke, mutelinks and banned phrase warnings", "https://vyneer.me/"),
    Thanks("Grayjay, by FUTO", "YouTube playback, through its plugin and code adapted from it (Source First License 1.1)", "https://grayjay.app/"),
    Thanks("Roboto, by Google", "The site's font (Apache 2.0)", null),
)

@Composable
private fun Credit(thanks: Thanks) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth()
            .then(if (thanks.link != null) Modifier.clickable { runCatching { uri.openUri(thanks.link) } } else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(thanks.who, color = if (thanks.link != null) Palette.Blue else Palette.White, fontSize = 13.sp)
        Text(thanks.what, color = Palette.TextDim, fontSize = 12.sp)
    }
}

/** The system back button or gesture, where the platform has one. */
@Composable
internal expect fun OnBack(enabled: Boolean, onBack: () -> Unit)
