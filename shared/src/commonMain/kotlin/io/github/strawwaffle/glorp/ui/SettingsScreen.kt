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
import io.github.strawwaffle.glorp.chat.ChatSettings
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
) {
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

        item { SettingsHeading("Highlights and tags") }
        item { SwitchRow("Highlight messages that mention me", null, settings.highlightSelf) { settings.setBool("highlight", it) } }
        item { HighlightWords(settings) }
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

/** `customhighlight`: words that highlight a message, written as the site's settings take them, between commas. */
@Composable
private fun HighlightWords(settings: ChatSettings) {
    val words = settings.list("customhighlight")
    var editing by remember { mutableStateOf<TextFieldValue?>(null) }
    val draft = editing
    if (draft == null) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable {
                val text = words.joinToString(", ")
                editing = TextFieldValue(text, TextRange(text.length))
            }.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Highlight words", color = Palette.White, fontSize = 15.sp)
            Text(
                if (words.isEmpty()) "None. Messages with these words stand out" else words.joinToString(", "),
                color = Palette.TextDim,
                fontSize = 12.sp,
            )
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
