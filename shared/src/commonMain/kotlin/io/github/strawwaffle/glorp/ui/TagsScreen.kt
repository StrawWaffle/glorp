package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.LocalOptions
import io.github.strawwaffle.glorp.chat.TAG_COLORS
import io.github.strawwaffle.glorp.chat.Tags

/**
 * Everyone you've tagged or given an icon, in one list: tap someone to change
 * their tag colour, note or icon, or add someone by name. Tags and notes are
 * the site's (`/tag`), saved with your account when signed in with cookies;
 * icons are Glorp's own and stay on this phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(tags: Tags, options: LocalOptions, synced: Boolean) {
    val colors = tags.colors
    val notes = tags.notes
    val icons = options.nameIcons
    val people = (colors.keys + icons.keys).sorted()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf("") }
    var removed by remember { mutableStateOf<Snapshot?>(null) }

    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Tags", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(
                    (if (synced) "Tag colours and notes are saved with your destiny.gg account, as on the site. "
                    else "Tag colours and notes are kept on this phone; signed in with the site's cookies, they follow your account. ") +
                        "Icons are Glorp's own and stay on this phone.",
                    color = Palette.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val add = {
                    val nick = adding.trim().removePrefix("@")
                    if (nick.isNotEmpty()) {
                        editing = nick.lowercase()
                        adding = ""
                    }
                }
                Field(adding, "Tag someone by name", Modifier.weight(1f), onDone = add) { adding = it.filter { c -> !c.isWhitespace() } }
                TextButton(onClick = add) { Text("Add", color = Palette.Blue) }
            }
        }
        removed?.let { gone ->
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .background(Palette.Navy, RoundedCornerShape(8.dp)).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Removed ${gone.nick}'s tag and icon", color = Palette.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { gone.restore(tags, options); removed = null }) { Text("Undo", color = Palette.Blue) }
                }
            }
        }
        if (people.isEmpty()) item {
            Text(
                "No one yet. Tag someone here, from their name in chat, or with /tag name colour note.",
                color = Palette.TextDim,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
        items(people, key = { it }) { nick ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { editing = nick },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The bar chat draws beside their lines.
                Box(Modifier.width(6.dp).height(40.dp).background(colors[nick]?.let(::tagColor) ?: Palette.Black))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        listOfNotNull(icons[nick], nick).joinToString(" "),
                        color = Palette.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                    val detail = listOfNotNull(colors[nick], notes[nick]?.takeIf { it.isNotBlank() }).joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, color = Palette.TextDim, fontSize = 12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    editing?.let { nick ->
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Palette.Navy,
        ) {
            TagEditor(nick, tags, options, onDone = { editing = null }, onRemoved = { removed = it; editing = null })
        }
    }
}

/** Someone's tag, note and icon at one moment, to put back. */
private class Snapshot(val nick: String, val color: String?, val note: String, val icon: String?) {
    fun restore(tags: Tags, options: LocalOptions) {
        if (color == null) tags.untag(nick) else tags.tag(nick, color, note)
        options.setIcon(nick, icon)
    }
}

private fun snapshot(nick: String, tags: Tags, options: LocalOptions) =
    Snapshot(nick, tags.colors[nick], tags.notes[nick].orEmpty(), options.nameIcons[nick])

/**
 * Changes save as they're made, the note as it's typed. Undo puts back how
 * they were when the editor opened; Remove all can be undone from the list.
 */
@Composable
private fun TagEditor(nick: String, tags: Tags, options: LocalOptions, onDone: () -> Unit, onRemoved: (Snapshot) -> Unit) {
    val opened = remember(nick) { snapshot(nick, tags, options) }
    val color = tags.colors[nick]
    val icon = options.nameIcons[nick]
    // Typed before a colour is picked, the note waits for one: a tag is a colour first.
    var note by remember(nick) { mutableStateOf(opened.note) }
    val changed = color != opened.color || icon != opened.icon || note.trim() != opened.note
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            listOfNotNull(icon, nick).joinToString(" "),
            color = color?.let(::tagColor) ?: Palette.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        TagRow(color) { picked -> if (picked == null) tags.untag(nick) else tags.tag(nick, picked, note.trim()) }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Note", color = Palette.White, fontSize = 15.sp)
            Text(
                if (color == null) "A note belongs to a tag. Pick a colour and it's saved" else "Saved as you type. Shown on their name's sheet, as on the site",
                color = Palette.TextDim,
                fontSize = 12.sp,
            )
            Field(note, "Why they're tagged", Modifier.fillMaxWidth().padding(top = 8.dp), onDone = onDone) {
                note = it
                color?.let { c -> tags.tag(nick, c, it.trim()) }
            }
        }
        IconRow(icon) { options.setIcon(nick, it) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            if (changed) {
                TextButton(onClick = { opened.restore(tags, options); note = opened.note }) { Text("Undo", color = Palette.Blue) }
            }
            if (color != null || icon != null) {
                TextButton(onClick = {
                    val before = snapshot(nick, tags, options)
                    tags.untag(nick)
                    options.setIcon(nick, null)
                    onRemoved(before)
                }) { Text("Remove all", color = Palette.Orange) }
            }
            TextButton(onClick = onDone) { Text("Done", color = Palette.Blue) }
        }
    }
}

/** chat-gui's tag colours; the one they have is ringed, and tapping it again takes the tag off. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TagRow(tag: String?, onTag: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(if (tag == null) "Tag" else "Tagged $tag. Tap it again to untag", color = Palette.White, fontSize = 15.sp)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (color in TAG_COLORS) {
                Box(
                    Modifier.size(44.dp)
                        .clickable(onClickLabel = if (color == tag) "Untag" else "Tag $color") { onTag(if (color == tag) null else color) }
                        .padding(6.dp)
                        .then(if (color == tag) Modifier.border(2.dp, Palette.White, CircleShape) else Modifier)
                        .padding(4.dp)
                        .background(tagColor(color), CircleShape),
                )
            }
        }
    }
}

/** A few to pick from; any emoji can be typed in the field. */
private val QUICK_ICONS = listOf("⭐", "🔥", "💩", "👀", "🤡", "🐐", "❤️", "🧠", "🐸", "👑")

/** Glorp's own tag: an emoji before someone's name in chat. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconRow(icon: String?, onIcon: (String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            if (icon == null) "Icon before their name" else "Icon before their name, $icon. Tap it again to remove it",
            color = Palette.White,
            fontSize = 15.sp,
        )
        Text("Glorp's own tag, kept on this phone", color = Palette.TextDim, fontSize = 12.sp)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (choice in QUICK_ICONS) {
                Box(
                    Modifier.size(44.dp)
                        .clickable(onClickLabel = if (choice == icon) "Remove the icon" else "Use $choice") { onIcon(if (choice == icon) null else choice) }
                        .then(if (choice == icon) Modifier.border(2.dp, Palette.White, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { Text(choice, fontSize = 20.sp) }
            }
        }
        var custom by remember { mutableStateOf("") }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(custom, "Or type one", Modifier.weight(1f), onDone = { if (custom.isNotBlank()) { onIcon(custom); custom = "" } }) {
                custom = it.take(LocalOptions.MAX_ICON)
            }
            TextButton(onClick = { if (custom.isNotBlank()) { onIcon(custom); custom = "" } }) { Text("Use", color = Palette.Blue) }
        }
    }
}

@Composable
private fun Field(
    value: String,
    hint: String,
    modifier: Modifier,
    enabled: Boolean = true,
    onDone: () -> Unit,
    onChange: (String) -> Unit,
) {
    BasicTextField(
        value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
        cursorBrush = SolidColor(Palette.White),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.background(Palette.Black, RoundedCornerShape(6.dp)).padding(10.dp),
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(hint, color = Palette.TextDim, fontSize = 15.sp)
                field()
            }
        },
    )
}
