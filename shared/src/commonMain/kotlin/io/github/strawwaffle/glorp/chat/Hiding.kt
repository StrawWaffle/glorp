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

    /**
     * Glorp's own tag: an emoji (or a few characters) shown before someone's
     * name, by lower-case nick. Kept on this phone; the site has nothing like it.
     */
    var nameIcons by mutableStateOf(readIcons(prefs.getList(NAME_ICONS)))
        private set

    /** Sets [nick]'s icon, or takes it away when [icon] is blank. */
    fun setIcon(nick: String, icon: String?) {
        val key = nick.lowercase()
        val clean = icon?.trim()?.takeIf { it.isNotEmpty() }?.let(::cutIcon)
        nameIcons = if (clean == null) nameIcons - key else nameIcons + (key to clean)
        prefs.putList(NAME_ICONS, nameIcons.map { (n, i) -> "$n $i" }.sorted())
    }

    /** Titles and channels for YouTube and Rumble links (on unless turned off). */
    var linkTitles by mutableStateOf(prefs.get(LINK_TITLES) != "false")
        private set

    fun showLinkTitles(on: Boolean) {
        linkTitles = on
        prefs.put(LINK_TITLES, on.toString())
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

    companion object {
        private const val HIDDEN_PHRASES = "hide.phrases"
        private const val HIDDEN_FLAIRS = "hide.flairs"
        private const val ALERT_WORDS = "alert.words"
        private const val STICKY_MENTIONS = "sticky.mentions"
        private const val OTHER_STREAMS = "other.streams"
        private const val NAME_ICONS = "name.icons"
        private const val LINK_TITLES = "link.titles"

        /** Room for one emoji made of several code points (a family, a flag with a modifier). */
        const val MAX_ICON = 12

        /** At most [MAX_ICON] characters, without cutting an emoji's two halves apart. */
        fun cutIcon(icon: String): String {
            val cut = icon.take(MAX_ICON)
            return if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut.dropLast(1) else cut
        }

        fun readIcons(list: List<String>): Map<String, String> =
            list.mapNotNull { entry -> entry.split(' ', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] } }.toMap()
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
