package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.strawwaffle.glorp.chat.OtherStreams
import io.github.strawwaffle.glorp.notify.NotifyControls
import io.github.strawwaffle.glorp.notify.NotifyOptions
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
    /** Notifications, where the platform has them. */
    notify: NotifyControls? = null,
    notifyOptions: NotifyOptions? = null,
    onNotifyChanged: () -> Unit = {},
    /** Mentions and whispers are read with the site's cookies; a login key can't. */
    signedInWithCookies: Boolean = false,
    /** Glorp's mentions service is there: mentions work with any sign-in. */
    mentionsService: Boolean = false,
    warnings: Boolean,
    onWarnings: (Boolean) -> Unit,
    /** Whether test versions are offered; null in builds that don't update themselves. */
    colorTheme: ColorTheme = ColorTheme.Glorp,
    onColorTheme: (ColorTheme) -> Unit = {},
    /** Opens the list of tagged people. */
    onTags: () -> Unit = {},
    /** Where Destiny's stream plays when he's live on several platforms. */
    preferredPlatform: String = "kick",
    onPreferredPlatform: (String) -> Unit = {},
    appFont: AppFont = AppFont.Phone,
    onAppFont: (AppFont) -> Unit = {},
    textScale: Float = 1f,
    onTextScale: (Float) -> Unit = {},
    testVersions: Boolean? = null,
    onTestVersions: (Boolean) -> Unit = {},
) {
    var choosingFlairs by remember { mutableStateOf(false) }
    // A list of categories, each its own page, as Android's settings are.
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    OnBack(enabled = section != null) { section = null }
    val sections = SettingsSection.entries.filter {
        when (it) {
            SettingsSection.Notifications -> notify != null && notifyOptions != null
            SettingsSection.Updates -> testVersions != null
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
                if (section != null) TextButton(onClick = { section = null }) { Text("‹ Settings", color = Palette.Blue) }
                Text(
                    section?.title ?: "Settings",
                    color = Palette.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    modifier = Modifier.padding(start = if (section == null) 12.dp else 4.dp),
                )
            }
        }
        if (section == null) {
            items(sections, key = { it.name }) { s ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { section = s }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.title, color = Palette.White, fontSize = 16.sp)
                        Text(s.summary, color = Palette.TextDim, fontSize = 13.sp)
                    }
                    Text("›", color = Palette.TextDim, fontSize = 22.sp)
                }
            }
        }
        if (section == SettingsSection.Look) {
        item {
            Choices(
                "Theme",
                "The Aerith themes are stolen from Aerith's themes for the site",
                ColorTheme.entries,
                colorTheme,
                label = { it.label },
                onPick = onColorTheme,
            )
        }
        item {
            Choices(
                "Chat text size",
                "On top of your phone's text size, which Glorp follows everywhere",
                textScales,
                textScale,
                label = ::textScaleLabel,
                onPick = onTextScale,
            )
        }
        item { Choices("Font", null, AppFont.entries, appFont, label = { it.label }, onPick = onAppFont) }

        }
        if (section == SettingsSection.Chat) {
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
            SwitchRow("Show timestamps", "In the format /timestampformat sets, now ${settings.timestampFormat}", settings.showTime) {
                settings.setBool("showtime", it)
            }
        }
        item { SwitchRow("Show flair icons", null, !settings.hideFlairIcons) { settings.setBool("hideflairicons", !it) } }
        item {
            SwitchRow(
                "Titles for YouTube and Rumble links",
                "The video's title and channel in place of the link. Each one is asked of YouTube or Rumble, which sees your IP address, as when you open it",
                options.linkTitles,
                options::showLinkTitles,
            )
        }
        item {
            SwitchRow(
                "Preview pictures from any site",
                "Tapping a picture's link shows it first, from any site rather than only imgur, catbox and the like. " +
                    "The picture's site sees your IP address, as when you open it",
                settings.previewAllImages,
            ) { settings.setBool("previewallimages", it) }
        }
        item {
            Choices(
                "Messages kept",
                "How far back you can scroll in chat, now ${settings.maxLines}. The site calls it maxlines. More uses a little more memory",
                listOf(100, 250, 500, 1000),
                settings.maxLines,
                label = { "$it" },
                onPick = { settings.set("maxlines", kotlinx.serialization.json.JsonPrimitive(it)) },
            )
        }
        item {
            SwitchRow(
                "Warn about nukes, mutelinks and banned phrases",
                "Before a message would get you muted or banned. The lists come from vyneer.me, as they did for the d.gg utilities userscript, and Glorp checks them every 30 seconds while chat is open",
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

        }
        if (section == SettingsSection.Hiding) {
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
            Choices(
                "Chat from people watching another stream",
                "While something plays here. Dim is the site's eye button; mentions of you and your highlights always show. " +
                    "Also in the list of what chat is watching",
                OtherStreams.entries,
                options.otherStreams,
                label = { it.label },
                onPick = options::treatOtherStreams,
            )
        }
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
                        .toggleable(
                            value = badge.name in options.hiddenFlairs,
                            role = androidx.compose.ui.semantics.Role.Checkbox,
                            onValueChange = { options.setFlairHidden(badge.name, it) },
                        )
                        .padding(start = 32.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(badge.url, null, Modifier.size(badge.width.dp, badge.height.dp))
                    Text(badge.label, color = Palette.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 12.dp))
                    Checkbox(checked = badge.name in options.hiddenFlairs, onCheckedChange = null)
                }
            }
        }

        }
        if (section == SettingsSection.Highlights) {
        item { SwitchRow("Highlight messages that mention me", null, settings.highlightSelf) { settings.setBool("highlight", it) } }
        item {
            SwitchRow(
                "Keep my latest mention in view",
                "After it scrolls past, it stays at the top of chat until you close it. Kept on this phone",
                options.stickyMentions,
                options::keepMentions,
            )
        }
        item {
            WordsSetting(
                "Highlight words",
                "None. Messages with these words stand out",
                settings.list("customhighlight"),
            ) { settings.setList("customhighlight", it) }
        }
        item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onTags).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tagged people", color = Palette.White, fontSize = 15.sp)
                    Text("Tag colours, notes and Glorp's name icons, all in one list", color = Palette.TextDim, fontSize = 12.sp)
                }
                Text("›", color = Palette.TextDim, fontSize = 22.sp)
            }
        }
        item {
            SwitchRow(
                "Make tagged users stand out",
                "A lighter background as well as the colour bar. Tag someone from their name, or with /tag",
                settings.taggedVisibility,
            ) { settings.setBool("taggedvisibility", it) }
        }

        }
        if (section == SettingsSection.Notifications && notify != null && notifyOptions != null) {
            item { NotificationSettings(notify, notifyOptions, settings, signedInWithCookies, mentionsService, onNotifyChanged) }
        }

        if (section == SettingsSection.Player) {
        item {
            Choices(
                "Destiny's stream on",
                "When he's live on several platforms. You can still pick another from the bar",
                listOf("kick", "youtube", "twitch", "rumble"),
                preferredPlatform,
                label = PlatformIcons::name,
                onPick = onPreferredPlatform,
            )
        }
        item {
            SwitchRow(
                "Keep playing with the screen off",
                "Streams and recordings played in Glorp's own player keep their sound when you lock the phone",
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
        }
        if (section == SettingsSection.Updates && testVersions != null) {
            item {
                SwitchRow(
                    "Get test versions",
                    "Try what's next before everyone else. Test versions show as Glorp Probe on your phone and may break; " +
                        "Menu → Report a problem tells us what did. Turned off, you're back on releases with the next one",
                    testVersions,
                    onTestVersions,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

/** The settings' categories, each a page of its own. */
private enum class SettingsSection(val title: String, val summary: String) {
    Look("Look", "Theme, chat text size and font"),
    Chat("Chat", "Timestamps, link titles and previews, how much chat is kept, warnings"),
    Hiding("Hiding", "Hidden words, messages and flair icons, and chat from other streams"),
    Highlights("Highlights and tags", "Mentions, highlight words and tagged people"),
    Notifications("Notifications", "Destiny going live, mentions and whispers"),
    Player("Player", "Destiny's platform, sound with the screen off, catching up"),
    Updates("Updates", "Test versions"),
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
internal fun SwitchRow(title: String, detail: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        // A switch to TalkBack, saying whether it's on.
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun <T> Choices(title: String, detail: String?, values: List<T>, current: T, label: (T) -> String, onPick: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, color = Palette.White, fontSize = 15.sp)
        detail?.let { Text(it, color = Palette.TextDim, fontSize = 12.sp) }
        // Wraps onto a second line on a narrow phone or with large text.
        FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (value in values) {
                val picked = value == current
                Text(
                    label(value),
                    color = if (picked) Palette.White else Palette.TextDim,
                    fontSize = 14.sp,
                    fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.heightIn(min = 40.dp)
                        .background(if (picked) Palette.NavyLight else Palette.Navy, RoundedCornerShape(8.dp))
                        .selectable(selected = picked, role = androidx.compose.ui.semantics.Role.RadioButton) { onPick(value) }
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

/**
 * Which notifications, how often Glorp looks, and Android's own settings for
 * each kind's sound. Glorp checks the site in the background; it doesn't
 * keep chat connected for this.
 */
@Composable
private fun NotificationSettings(
    notify: NotifyControls,
    options: NotifyOptions,
    settings: ChatSettings,
    cookies: Boolean,
    mentionsService: Boolean,
    onChanged: () -> Unit,
) {
    var live by remember { mutableStateOf(options.live) }
    var interval by remember { mutableStateOf(options.intervalMinutes) }
    var permitted by remember { mutableStateOf(notify.permitted) }
    // Turning one on asks Android's permission first, where it's needed.
    fun turn(on: Boolean, apply: () -> Unit) {
        if (on && !notify.permitted) {
            notify.ask { granted ->
                permitted = granted
                if (granted) { apply(); onChanged() }
            }
        } else {
            apply()
            onChanged()
        }
    }
    val signedOutNote = if (cookies) null else "Needs the computer sign-in, because the site only shows whispers to its own login"
    Column {
        Text(
            "In picture in picture, full screen, or playing with the screen off, whispers and mentions notify as they arrive. " +
                "With Glorp open, a whisper shows as a banner instead. When Glorp is closed, it checks the site as often as you choose below.",
            color = Palette.TextDim,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        SwitchRow("Destiny goes live", null, live) { on -> turn(on) { options.setLive(on); live = on } }
        SwitchRow(
            "Someone mentions me",
            (if (mentionsService) null else signedOutNote)
                ?: "Tapping it opens the chat around the message, even once it has left chat. Follows your account, as on the site",
            options.mentions,
        ) { on -> turn(on) { settings.setBool("notificationhighlight", on) } }
        SwitchRow(
            "Whispers",
            signedOutNote ?: "Tapping it opens the conversation. Follows your account, as on the site",
            options.whispers,
        ) { on -> turn(on) { settings.setBool("notificationwhisper", on) } }
        Choices(
            "Look every",
            "Sooner means more checks. Each check is one small request per kind turned on",
            NotifyOptions.INTERVALS,
            interval,
            label = { if (it < 60) "$it min" else "${it / 60} h" },
        ) { options.setInterval(it); interval = it; onChanged() }
        Column(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { notify.openSystemSettings() }.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Sound and vibration", color = Palette.White, fontSize = 15.sp)
            Text("Android's settings for each kind set its sound and vibration, or silence it or turn it off", color = Palette.TextDim, fontSize = 12.sp)
        }
        if (options.any && !permitted) {
            Text(
                "Android is blocking Glorp's notifications. Tap to allow them.",
                color = Palette.Orange,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth().clickable { notify.ask { permitted = it; onChanged() } }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
