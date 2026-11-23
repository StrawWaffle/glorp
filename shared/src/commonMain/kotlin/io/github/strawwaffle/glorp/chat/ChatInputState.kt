package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.github.strawwaffle.glorp.ui.MAX_MESSAGE
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

    /** Someone tapped in chat, whose sheet is open; null when none. */
    var tappedNick by mutableStateOf<String?>(null)
        private set

    /** The message long-pressed to open the sheet, if it was opened that way. */
    var tappedLine by mutableStateOf<ChatLine?>(null)
        private set

    /** Bumped to ask the input to take focus and show the keyboard. */
    var focusRequests by mutableIntStateOf(0)
        private set

    fun openUser(nick: String, line: ChatLine? = null) {
        tappedNick = nick
        tappedLine = line
    }

    fun closeUser() {
        tappedNick = null
        tappedLine = null
    }

    /** Puts [nick] in the draft at the caret, and the caret in the input. */
    fun mention(nick: String) {
        val (next, caret) = insertWord(draft.text, draft.selection.end, nick)
        if (next.length <= MAX_MESSAGE) setDraft(next, caret)
        focusRequests++
    }

    /** Starts a whisper to [nick] in the input. */
    fun whisper(nick: String) {
        setDraft("/w $nick ")
        focusRequests++
    }

    /** Nukes, mutelinks and banned phrases the draft is checked against. */
    val watch = ModWatch()

    /** A draft whose warning was shown once: sending it again goes through. */
    var armed by mutableStateOf<String?>(null)

    /** Who `/r` answers: the last whisperer, or the last one whispered. */
    var replyTo: String? = null

    fun setDraft(text: String, caret: Int = text.length) {
        draft = TextFieldValue(text, TextRange(caret.coerceIn(0, text.length)))
    }
}
