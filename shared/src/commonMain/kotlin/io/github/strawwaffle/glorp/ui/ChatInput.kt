package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatInputState
import io.github.strawwaffle.glorp.chat.Suggestion
import io.github.strawwaffle.glorp.chat.complete
import io.github.strawwaffle.glorp.chat.insertWord
import io.github.strawwaffle.glorp.chat.mayUse
import io.github.strawwaffle.glorp.chat.wordAt
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.Emotes
import kotlin.time.Clock

/** The longest message chat takes. */
const val MAX_MESSAGE = 512

/** The counter shows from this many characters on. */
private const val COUNT_FROM = MAX_MESSAGE - 100

/**
 * The message input, with what chat-gui's has, made for a phone: a strip of
 * suggestions above the keyboard (emotes the user may send, names, commands;
 * recent messages while the input is empty), an emote picker in place of the
 * keyboard, the actions for a name tapped in chat, and a counter near the
 * length limit. A hardware keyboard gets chat-gui's keys: Tab completes,
 * up and down step through what was sent, Enter sends.
 *
 * Suggestions are worked out from sorted lists the view model keeps up to
 * date, so typing costs a binary search, not a scan of the emote list.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatInput(
    state: ChatInputState,
    emotes: Emotes,
    account: Account,
    connected: Boolean,
    onSend: (String) -> Boolean,
    /** The placeholder; chat's own when null. */
    hint: String? = null,
) {
    val draft = state.draft
    var focused by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val completer = state.completer
    val ignored = state.ignored.nicks
    val word = remember(draft.text, draft.selection) {
        if (draft.selection.collapsed) wordAt(draft.text, draft.selection.start) else null
    }
    val suggestions = remember(word, completer.version, ignored, account.nick) {
        val me = account.nick.lowercase()
        word?.let { completer.suggest(it, atStart = it.start == 0, skip = { nick -> nick == me || nick in ignored }) }.orEmpty()
    }

    // The picker stands in for the keyboard: it goes when the keyboard comes back.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) { if (imeVisible) picker = false }
    // Back closes the picker or a name's actions before it leaves chat.
    OnBack(enabled = picker) { picker = false }
    // The name sheet's Mention and Whisper hand the input the caret.
    LaunchedEffect(state.focusRequests) {
        if (state.focusRequests > 0) {
            picker = false
            focus.requestFocus()
            keyboard?.show()
        }
    }

    fun take(suggestion: Suggestion) {
        val w = word ?: return
        val (text, caret) = complete(draft.text, w, suggestion.text)
        state.setDraft(text.take(MAX_MESSAGE), caret)
        completer.picked(suggestion.text, Clock.System.now().toEpochMilliseconds())
    }

    fun insert(text: String) {
        val (next, caret) = insertWord(draft.text, draft.selection.end, text)
        if (next.length <= MAX_MESSAGE) state.setDraft(next, caret)
    }

    val send = { onSend(draft.text) }

    Column(Modifier.fillMaxWidth().background(Palette.Navy)) {
        when {
            suggestions.isNotEmpty() -> SuggestionStrip(suggestions, emotes, ::take)
            focused && draft.text.isEmpty() && state.history.recent.isNotEmpty() -> HistoryStrip(state.history.recent) {
                state.setDraft(it)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EmoteButton(open = picker) {
                if (picker) {
                    picker = false
                    focus.requestFocus()
                    keyboard?.show()
                } else {
                    picker = true
                    keyboard?.hide()
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = { value ->
                    state.history.reset()
                    state.draft = if (value.text.length <= MAX_MESSAGE) value
                    else value.copy(text = value.text.take(MAX_MESSAGE))
                },
                textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Palette.Blue),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f)
                    .focusRequester(focus)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            event.key == Key.Tab && suggestions.isNotEmpty() -> { take(suggestions[0]); true }
                            event.key == Key.Enter && !event.isShiftPressed -> { send(); true }
                            event.key == Key.DirectionUp && suggestions.isEmpty() ->
                                state.history.older(draft.text)?.let { state.setDraft(it); true } ?: false
                            event.key == Key.DirectionDown && suggestions.isEmpty() ->
                                state.history.newer()?.let { state.setDraft(it); true } ?: false
                            else -> false
                        }
                    }
                    .background(Palette.Black, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                decorationBox = { field ->
                    Box {
                        if (draft.text.isEmpty()) {
                            Text(
                                if (!connected) "Waiting for chat ..." else hint ?: "Write something ${account.nick} ...",
                                color = Palette.TextDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        field()
                    }
                },
            )
            val length = draft.text.length
            if (length >= COUNT_FROM) {
                Text(
                    "${MAX_MESSAGE - length}",
                    color = if (length >= MAX_MESSAGE - 12) Palette.Orange else Palette.TextDim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            val canSend = draft.text.isNotBlank()
            TextButton(onClick = { send() }, enabled = canSend) {
                Text("Send", color = if (canSend && connected) Palette.Blue else Palette.TextDim)
            }
        }
        if (picker) EmotePicker(emotes, account, state) { insert(it.prefix); completer.picked(it.prefix, Clock.System.now().toEpochMilliseconds()) }
    }
}

@Composable
private fun SuggestionStrip(suggestions: List<Suggestion>, emotes: Emotes, onPick: (Suggestion) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().height(40.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(suggestions, key = { it.kind.name + it.text }) { s ->
            Row(
                Modifier.height(32.dp).background(Palette.NavyLight, RoundedCornerShape(6.dp))
                    .clickable { onPick(s) }.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val emote = if (s.kind == Suggestion.Kind.Emote) emotes[s.text] else null
                if (emote != null) {
                    FittedEmote(emote, maxWidth = 64, maxHeight = 26)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    s.text,
                    color = if (s.kind == Suggestion.Kind.Command) Palette.Glorp else Palette.White,
                    fontSize = 13.sp,
                    fontWeight = if (s.kind == Suggestion.Kind.Name) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HistoryStrip(recent: List<String>, onPick: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().height(40.dp),
        contentPadding = PaddingValues(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Text("Sent:", color = Palette.TextDim, fontSize = 12.sp) }
        items(recent) { text ->
            Text(
                text.replace('\n', ' '),
                color = Palette.White,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
                    .background(Palette.NavyLight, RoundedCornerShape(6.dp))
                    .clickable { onPick(text) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/** A plain drawn face: the button that swaps the keyboard for the emote picker. */
@Composable
private fun EmoteButton(open: Boolean, onClick: () -> Unit) {
    val color = if (open) Palette.Blue else Palette.TextDim
    Box(
        Modifier.size(44.dp).clickable(onClickLabel = if (open) "Keyboard" else "Emotes", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2
            val stroke = r * 0.14f
            drawCircle(color, radius = r - stroke / 2, style = Stroke(stroke))
            drawCircle(color, radius = r * 0.11f, center = Offset(center.x - r * 0.36f, center.y - r * 0.22f))
            drawCircle(color, radius = r * 0.11f, center = Offset(center.x + r * 0.36f, center.y - r * 0.22f))
            drawArc(
                color, startAngle = 20f, sweepAngle = 140f, useCenter = false,
                topLeft = Offset(center.x - r * 0.5f, center.y - r * 0.35f),
                size = androidx.compose.ui.geometry.Size(r, r * 0.9f),
                style = Stroke(stroke),
            )
        }
    }
}

/** An emote scaled down, never up, to fit [maxWidth] by [maxHeight] dp. */
@Composable
private fun FittedEmote(emote: Emote, maxWidth: Int, maxHeight: Int) {
    val scale = minOf(1f, maxWidth.toFloat() / emote.width, maxHeight.toFloat() / emote.height)
    // Sent "at 0": a sprite rests on its last frame instead of playing.
    EmoteImage(emote, sentAt = 0, modifier = Modifier.size((emote.width * scale).dp, (emote.height * scale).dp))
}

private sealed interface PickerItem {
    class Header(val title: String) : PickerItem
    class Item(val emote: Emote, val section: String) : PickerItem
}

/**
 * The emotes the user may send, by tier as chat-gui's menu groups them, with
 * those picked lately first. A lazy grid: only the rows on screen are drawn.
 */
@Composable
private fun EmotePicker(emotes: Emotes, account: Account, state: ChatInputState, onPick: (Emote) -> Unit) {
    val items = remember(emotes, account) {
        val usable = emotes.all.filter { mayUse(it, account.features, account.subTier) }
        val recent = state.completer.recentEmotes(16).mapNotNull { emotes[it] }
        buildList {
            if (recent.isNotEmpty()) {
                add(PickerItem.Header("Recently used"))
                recent.forEach { add(PickerItem.Item(it, "recent")) }
            }
            for ((tier, group) in usable.groupBy { it.minimumSubTier }.toList().sortedBy { it.first }) {
                add(PickerItem.Header(if (tier == 0) "All users" else "Tier $tier subscribers"))
                group.forEach { add(PickerItem.Item(it, "$tier")) }
            }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(64.dp),
        modifier = Modifier.fillMaxWidth().height(260.dp).background(Palette.Black),
        contentPadding = PaddingValues(6.dp),
    ) {
        items(
            items,
            key = { if (it is PickerItem.Item) "${it.section}:${it.emote.prefix}" else "h:" + (it as PickerItem.Header).title },
            span = { if (it is PickerItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
            contentType = { if (it is PickerItem.Header) 0 else 1 },
        ) { item ->
            when (item) {
                is PickerItem.Header -> Text(
                    item.title, color = Palette.TextDim, fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp),
                )
                is PickerItem.Item -> Box(
                    Modifier.height(52.dp).clickable(onClickLabel = item.emote.prefix) { onPick(item.emote) },
                    contentAlignment = Alignment.Center,
                ) { FittedEmote(item.emote, maxWidth = 60, maxHeight = 40) }
            }
        }
    }
}
