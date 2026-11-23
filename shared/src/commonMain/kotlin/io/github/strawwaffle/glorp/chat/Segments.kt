package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.Emotes

/** A piece of a message as chat draws it. */
sealed interface Segment {
    data class Text(val text: String) : Segment
    data class EmoteRef(val emote: Emote) : Segment
    data class Link(val text: String, val url: String) : Segment

    /** A bigscreen link or `#platform/id`: opens in the app's player rather than the browser. */
    data class EmbedLink(val text: String, val embed: Embed) : Segment
}

/**
 * chat-gui's `isPrivileged`: a moderator, admin or protected user, who may use
 * every emote and greentext. Privileges come from roles; the flairs of the same
 * names are read too, for anything that arrives without roles.
 */
fun isPrivileged(roles: List<String>, features: List<String>): Boolean =
    roles.any { it.uppercase() in privilegedRoles } || features.any { it.uppercase() in privilegedRoles }

private val privilegedRoles = setOf("ADMIN", "MODERATOR", "PROTECTED")

/** Whether someone with [features] and [subTier] may send [emote], as chat decides. */
fun mayUse(emote: Emote, features: List<String>, subTier: Int, roles: List<String> = emptyList()): Boolean =
    emote.minimumSubTier <= subTier || isPrivileged(roles, features)

/** Moderator powers, as chat-gui's `hasModPowers`. */
fun hasModPowers(features: List<String>): Boolean = features.any { it.equals("admin", true) || it.equals("moderator", true) }

/**
 * Splits a message the way chat does: an emote is a whole word naming one the
 * sender may use, and links are made clickable. Whitespace is kept as it was.
 */
fun segments(line: ChatLine, emotes: Emotes): List<Segment> {
    val out = ArrayList<Segment>()
    val text = StringBuilder()
    val mayUseAll = isPrivileged(line.roles, line.features)
    fun flush() {
        if (text.isNotEmpty()) {
            out += Segment.Text(text.toString())
            text.clear()
        }
    }
    var i = 0
    val s = line.text
    while (i < s.length) {
        if (s[i].isWhitespace()) {
            text.append(s[i++])
            continue
        }
        var end = i
        while (end < s.length && !s[end].isWhitespace()) end++
        val word = s.substring(i, end)
        val emote = emotes[word]
        val embed = if (emote == null && (word.startsWith('#') || "/bigscreen#" in word)) Embed.parse(word) else null
        val link = if (emote == null && embed == null) linkFor(word) else null
        when {
            emote != null && (mayUseAll || emote.minimumSubTier <= line.subTier) -> {
                flush(); out += Segment.EmoteRef(emote)
            }
            embed != null -> {
                flush(); out += Segment.EmbedLink(word, embed)
            }
            link != null -> {
                flush(); out += Segment.Link(word, link)
            }
            else -> text.append(word)
        }
        i = end
    }
    flush()
    return out
}

/**
 * The emote a message consists of, if it is only one emote its sender may
 * use (ignoring a leading `/command`), as chat-gui decides what can combo.
 */
fun soleEmote(line: ChatLine, emotes: Emotes): Emote? {
    // Called for every message, often twice: no pattern unless it starts with a command.
    val text = line.text
    val word = (if (text.startsWith("/")) text.replace(leadingCommand, "") else text).trim()
    val emote = emotes[word] ?: return null
    val mayUseAll = isPrivileged(line.roles, line.features)
    return if (mayUseAll || emote.minimumSubTier <= line.subTier) emote else null
}

private val leadingCommand = Regex("""^/[a-z0-9]+\s?""", RegexOption.IGNORE_CASE)

private val schemeLink = Regex("""^https?://\S+$""", RegexOption.IGNORE_CASE)
private val bareLink = Regex(
    """^(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|gg|tv|io|dev|me|co|be|ly|app|uk|us|ca|de|fr|es|it|nl|ru|jp|xyz|info|news|live)(?:[/?#]\S*)?$""",
    RegexOption.IGNORE_CASE,
)

/** The address a word links to, or null. Trailing punctuation stays text-like. */
internal fun linkFor(word: String): String? {
    val trimmed = word.trimEnd('.', ',', '!', '?', ')', ':', ';')
    return when {
        trimmed.length < 4 -> null
        schemeLink.matches(trimmed) -> trimmed
        bareLink.matches(trimmed) -> "https://$trimmed"
        else -> null
    }
}
