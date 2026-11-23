package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The d.gg utilities userscript's own options, which the site's settings
 * have no place for, so they stay on this phone: phrases whose messages are
 * hidden, flair icons not drawn, and words to be warned about before sending.
 */
class LocalOptions(private val prefs: ChatPrefs) {
    var hiddenPhrases by mutableStateOf(prefs.getList(HIDDEN_PHRASES))
        private set
    /** Flair names (`flair14`), not labels. */
    var hiddenFlairs by mutableStateOf(prefs.getList(HIDDEN_FLAIRS).toSet())
        private set
    var alertWords by mutableStateOf(prefs.getList(ALERT_WORDS))
        private set
    /** Keep the newest mention of you in view after it scrolls past (on unless turned off). */
    var stickyMentions by mutableStateOf(prefs.get(STICKY_MENTIONS) != "false")
        private set

    /** What to do with messages from people watching another stream than the one playing here. */
    var otherStreams by mutableStateOf(OtherStreams.entries.firstOrNull { it.name == prefs.get(OTHER_STREAMS) } ?: OtherStreams.Show)
        private set

    fun treatOtherStreams(mode: OtherStreams) {
        otherStreams = mode
        prefs.put(OTHER_STREAMS, mode.name)
    }

    fun keepMentions(on: Boolean) {
        stickyMentions = on
        prefs.put(STICKY_MENTIONS, on.toString())
    }

    fun hidePhrases(list: List<String>) {
        hiddenPhrases = list
        prefs.putList(HIDDEN_PHRASES, list)
    }

    fun setFlairHidden(name: String, hidden: Boolean) {
        hiddenFlairs = if (hidden) hiddenFlairs + name else hiddenFlairs - name
        prefs.putList(HIDDEN_FLAIRS, hiddenFlairs.sorted())
    }

    fun alertOn(list: List<String>) {
        alertWords = list
        prefs.putList(ALERT_WORDS, list)
    }

    private companion object {
        const val HIDDEN_PHRASES = "hide.phrases"
        const val HIDDEN_FLAIRS = "hide.flairs"
        const val ALERT_WORDS = "alert.words"
        const val STICKY_MENTIONS = "sticky.mentions"
        const val OTHER_STREAMS = "other.streams"
    }
}

/**
 * The site's eye button ("highlight messages from users watching the same
 * stream") dims the rest; here they can be hidden too.
 */
enum class OtherStreams(val label: String) { Show("Show"), Dim("Dim"), Hide("Hide") }

/**
 * Whether [line] is from someone known to be watching another embed than
 * [playing] (`platform/id`). Nothing playing, your own lines, and people
 * watching nothing on bigscreen (on the phone, say) don't count.
 */
fun fromOtherStream(line: ChatLine, me: String?, playing: String?): Boolean =
    playing != null && line.kind == ChatLine.Kind.User && line.watching != null &&
        line.watching != playing.lowercase() && !line.nick.equals(me, ignoreCase = true)

/**
 * Which chat messages aren't shown, besides those from ignored users (which
 * never reach the log): chat-gui's `ignorementions` (messages naming someone
 * you ignore), `hidensfw` and `hidensfl` (messages saying NSFW or NSFL), and
 * the userscript's hidden phrases (as its nukes match: text anywhere, or a
 * `/pattern/`). Your own messages always show.
 */
class LineFilter(
    me: String?,
    ignored: Collection<String>,
    ignoreMentions: Boolean,
    hideNsfw: Boolean,
    hideNsfl: Boolean,
    phrases: List<String>,
) {
    private val me = me?.lowercase()
    private val mentions = ignored.takeIf { ignoreMentions && it.isNotEmpty() }
        ?.let { Regex("""\b(?:${it.joinToString("|") { n -> Regex.escape(n) }})\b""", RegexOption.IGNORE_CASE) }
    private val nsfw = if (hideNsfw) Regex("""\bNSFW\b""", RegexOption.IGNORE_CASE) else null
    private val nsfl = if (hideNsfl) Regex("""\bNSFL\b""", RegexOption.IGNORE_CASE) else null
    private val traps = phrases.filter { it.isNotBlank() }.map(::Trap)

    val isEmpty get() = mentions == null && nsfw == null && nsfl == null && traps.isEmpty()

    fun hides(line: ChatLine): Boolean {
        if (line.kind != ChatLine.Kind.User) return false
        if (line.nick?.lowercase() == me) return false
        val text = line.text
        return mentions?.containsMatchIn(text) == true ||
            nsfw?.containsMatchIn(text) == true ||
            nsfl?.containsMatchIn(text) == true ||
            traps.any { it.catches(text) }
    }
}
