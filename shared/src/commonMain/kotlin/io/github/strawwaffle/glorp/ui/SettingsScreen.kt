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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.chat.ChatSettings
import io.github.strawwaffle.glorp.chat.LocalOptions
import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.chat.highlightWords

/**
 * Settings, on their own screen and grouped, each a switch or a value, the
 * most used first (see docs/DESIGN.md).
 */
@Composable
fun SettingsScreen(
    settings: ChatSettings,
    /** Whether the chat settings are saved with the account (cookie sign-in). */
    synced: Boolean,
    backgroundAudio: Boolean,
    onBackgroundAudio: (Boolean) -> Unit,
    /** The catch-up speed; 1 is off. */
    catchUp: Float,
    catchUpSpeeds: List<Float>,
    onCatchUp: (Float) -> Unit,
    /** The options kept on this phone: hidden phrases and flairs, alert words. */
    options: LocalOptions,
    flairs: Flairs,
    warnings: Boolean,
    onWarnings: (Boolean) -> Unit,
) {
    var choosingFlairs by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Text("Settings", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.padding(16.dp))
        }
        item { SettingsHeading("Chat") }
        item {
            Text(
                if (synced) "Chat settings are saved with your destiny.gg account, as on the site."
                else "Chat settings are kept on this phone. Signed in with the site's cookies, they follow your account.",
                color = Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        item {
            SwitchRow("Show timestamps", "Written as /timestampformat says (now ${settings.timestampFormat})", settings.showTime) {
                settings.setBool("showtime", it)
            }
        }
        item { SwitchRow("Show flair icons", null, !settings.hideFlairIcons) { settings.setBool("hideflairicons", !it) } }
        item {
            SwitchRow(
                "Warn about nukes, mutelinks and banned phrases",
                "Before a message would get you muted or banned. The lists come from vyneer.me (d.gg utilities), checked every 30 seconds while chat is open",
                warnings,
                onWarnings,
            )
        }
        item {
            WordsSetting(
                "My alert words",
                "None. Words to be warned about before sending, kept on this phone",
                options.alertWords,
                options::alertOn,
            )
        }

        item { SettingsHeading("Hiding") }
        item {
            SwitchRow("Hide messages that mention people I ignore", null, settings.ignoreMentions) {
                settings.setBool("ignorementions", it)
            }
        }
        item {
            SwitchRow(
                "Censor messages from muted or banned users",
                "Their earlier messages show as <censored> until tapped, as on the site",
                settings.censorRemoved,
            ) { settings.set("showremoved", kotlinx.serialization.json.JsonPrimitive(if (it) 1 else 2)) }
        }
        item { SwitchRow("Hide messages marked NSFW", null, settings.hideNsfw) { settings.setBool("hidensfw", it) } }
        item { SwitchRow("Hide messages marked NSFL", null, settings.hideNsfl) { settings.setBool("hidensfl", it) } }
        item {
            WordsSetting(
                "Hide messages with these words",
                "None. Kept on this phone; a word can be a /pattern/",
                options.hiddenPhrases,
                options::hidePhrases,
            )
        }
        item {
            val hidden = options.hiddenFlairs.size
            Column(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { choosingFlairs = !choosingFlairs }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Flair icons to hide", color = Palette.White, fontSize = 15.sp)
                Text(
                    (if (hidden == 0) "None hidden" else "$hidden hidden") + if (choosingFlairs) " · tap to close" else " · tap to choose",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                )
            }
        }
        if (choosingFlairs) {
            items(flairs.allBadges, key = { "flair:" + it.name }) { badge ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable { options.setFlairHidden(badge.name, badge.name !in options.hiddenFlairs) }
                        .padding(start = 32.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(badge.url, null, Modifier.size(badge.width.dp, badge.height.dp))
                    Text(badge.label, color = Palette.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Checkbox(checked = badge.name in options.hiddenFlairs, onCheckedChange = null)
                }
            }
        }

        item { SettingsHeading("Highlights and tags") }
        item { SwitchRow("Highlight messages that mention me", null, settings.highlightSelf) { settings.setBool("highlight", it) } }
        item {
            WordsSetting(
                "Highlight words",
                "None. Messages with these words stand out",
                settings.list("customhighlight"),
            ) { settings.setList("customhighlight", it) }
        }
        item {
            SwitchRow(
                "Make tagged users stand out",
                "A lighter background as well as the colour bar. Tag someone from their name, or with /tag",
                settings.taggedVisibility,
            ) { settings.setBool("taggedvisibility", it) }
        }

        item { SettingsHeading("Player") }
        item {
            SwitchRow(
                "Keep playing with the screen off",
                "Kick, Twitch and YouTube live keep their sound when you lock the phone",
                backgroundAudio,
                onBackgroundAudio,
            )
        }
        item {
            Choices(
                "Catch-up speed",
                "How fast a live stream that fell behind plays until it's back",
                catchUpSpeeds,
                catchUp,
                label = { if (it <= 1f) "Off" else "${it.toString().removeSuffix(".0")}×" },
                onPick = onCatchUp,
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
internal fun SettingsHeading(text: String) {
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp), color = Palette.Navy)
        Text(
            text,
            color = Palette.Blue,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
    }
}

/** A setting that's on or off: the whole row toggles it. */
@Composable
private fun SwitchRow(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = Palette.White, fontSize = 15.sp)
            detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        }
        Switch(
            checked = on,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Blue, uncheckedTrackColor = Palette.Navy),
        )
    }
}

/** One of a few values, all in view. */
@Composable
private fun <T> Choices(title: String, detail: String?, values: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, color = Palette.White, fontSize = 15.sp)
        detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in values) {
                val picked = value == current
                Text(
                    label(value),
                    color = if (picked) Palette.White else Palette.TextDim,
                    fontSize = 14.sp,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.heightIn(min = 40.dp)
                        .background(if (picked) Palette.NavyLight else Palette.Navy, RoundedCornerShape(8.dp))
                        .clickable { onPick(value) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** A list of words, written as the site's settings take them: between commas. */
@Composable
private fun WordsSetting(title: String, empty: String, words: List<String>, onSave: (List<String>) -> Unit) {
    var editing by remember { mutableStateOf<TextFieldValue?>(null) }
    val draft = editing
    if (draft == null) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                val text = words.joinToString(", ")
                editing = TextFieldValue(text, TextRange(text.length))
            }.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(title, color = Palette.White, fontSize = 15.sp)
            Text(
                if (words.isEmpty()) empty else words.joinToString(", "),
                color = Palette.TextDim,
                fontSize = 12.sp,
            )
        }
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        onSave(highlightWords(draft.text))
        editing = null
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("$title, between commas", color = Palette.White, fontSize = 15.sp)
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
