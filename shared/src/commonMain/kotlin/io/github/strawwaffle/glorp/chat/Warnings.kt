package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A word or phrase as the d.gg utilities userscript matches them: text
 * anywhere in the message (ignoring case), or a pattern written `/like this/`.
 */
class Trap(raw: String) {
    private val text = raw.trim().lowercase()
    private val pattern: Regex? = if (text.length > 2 && text.startsWith('/') && text.endsWith('/')) {
        runCatching { Regex(text.substring(1, text.length - 1), RegexOption.IGNORE_CASE) }.getOrNull()
    } else null

    fun catches(message: String): Boolean =
        if (pattern != null) pattern.containsMatchIn(message) else text.isNotEmpty() && message.lowercase().contains(text)
}

/** A nuke a moderator has running: saying [word] gets you a [type] ("mute", "ban") for [duration]. */
data class Nuke(val word: String, val type: String, val duration: String) {
    internal val trap by lazy { Trap(word) }
}

/** A banned phrase: saying it is a [type] for [duration]. */
data class BannedPhrase(val phrase: String, val type: String, val duration: String) {
    internal val trap by lazy { Trap(phrase) }
}

/** Mutelinks: [status] "on" or "all" mutes links for [duration]; "off" doesn't. [by] turned it on. */
data class Mutelinks(val status: String, val by: String?, val duration: String) {
    val active get() = status == "on" || status == "all"
}

/** Why a draft would get its sender in trouble. */
data class Warning(val kind: Kind, val what: String, val type: String, val duration: String) {
    enum class Kind { Phrase, Nuke, Links, Alert }

    /** One line, for over the input. */
    val text: String
        get() {
            val penalty = if (type.startsWith("ban", ignoreCase = true)) "a $duration ban" else "a $duration mute"
            return when (kind) {
                Kind.Phrase -> "\"$what\" is a banned phrase: $penalty."
                Kind.Nuke -> "\"$what\" is nuked: $penalty."
                Kind.Links -> "Mutelinks is on. Posting a link gets you $penalty."
                Kind.Alert -> "\"$what\" is one of your alert words."
            }
        }
}

/**
 * Nukes, mutelinks and banned phrases as they stand (filled from vyneer.me,
 * see `VyneerTools`), and the check of a draft against them.
 */
class ModWatch {
    var nukes by mutableStateOf(emptyList<Nuke>())
    var phrases by mutableStateOf(emptyList<BannedPhrase>())
    var mutelinks by mutableStateOf<Mutelinks?>(null)

    /** What the draft would set off, the userscript's order: phrases, links, nukes, your own words. Null when nothing. */
    fun check(draft: String, alertWords: List<String> = emptyList()): Warning? =
        checkDraft(draft, phrases, mutelinks, nukes, alertWords)
}

private val whisperCommands = listOf("/w ", "/whisper ", "/msg ", "/message ", "/notify ", "/tell ", "/t ", "/r ", "/reply ")

// The userscript's link test: an address, or a word ending in a common top-level domain.
private val link = Regex("""(?:https?://|www\.)\S|\b[\w-]+\.(?:com|org|ru|net|uk|au|in|de|ir|ca|gov|be|tv|it|gg|io|ly|co|me)\b""", RegexOption.IGNORE_CASE)

internal fun checkDraft(
    draft: String,
    phrases: List<BannedPhrase>,
    mutelinks: Mutelinks?,
    nukes: List<Nuke>,
    alertWords: List<String> = emptyList(),
): Warning? {
    val text = draft.trim()
    if (text.isEmpty()) return null
    val lower = text.lowercase()
    // Whispers and the app's own commands don't go to chat; `/me` does.
    if (whisperCommands.any { lower.startsWith(it) }) return null
    if (lower.startsWith("/") && !lower.startsWith("/me ")) return null
    phrases.firstOrNull { it.trap.catches(text) }?.let { return Warning(Warning.Kind.Phrase, it.phrase, it.type, it.duration) }
    if (mutelinks?.active == true && link.containsMatchIn(text)) {
        return Warning(Warning.Kind.Links, "links", "mute", mutelinks.duration)
    }
    nukes.firstOrNull { it.trap.catches(text) }?.let { return Warning(Warning.Kind.Nuke, it.word, it.type, it.duration) }
    alertWords.firstOrNull { it.isNotBlank() && Trap(it).catches(text) }?.let { return Warning(Warning.Kind.Alert, it, "", "") }
    return null
}
