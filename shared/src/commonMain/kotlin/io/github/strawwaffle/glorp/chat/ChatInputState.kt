package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * What the input needs that outlives the input itself: the draft (kept
 * through rotation, picture in picture and signing in again), the history,
 * the ignore and highlight lists and the suggestion lists. The view model
 * holds it.
 */
class ChatInputState(
    prefs: ChatPrefs,
    val completer: Completer = Completer(),
    val settings: ChatSettings = ChatSettings(prefs),
) {
    var draft by mutableStateOf(TextFieldValue())

    val history = InputHistory(prefs)
    val ignored = NickSet(settings, "ignorenicks")
    val highlighted = NickSet(settings, "highlightnicks")
    val tags = Tags(settings)

    /** A name tapped in chat, whose actions show above the input; null when none. */
    var tappedNick by mutableStateOf<String?>(null)

    /** Who `/r` answers: the last whisperer, or the last one whispered. */
    var replyTo: String? = null

    fun setDraft(text: String, caret: Int = text.length) {
        draft = TextFieldValue(text, TextRange(caret.coerceIn(0, text.length)))
    }
}
