package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * The word being written, as chat-gui's autocomplete reads it: from the last
 * space before the caret to the next one after it. A leading `@` or `>` asks
 * for names only and stays in the message.
 */
data class Word(
    /** Where the replaced part starts (after any `@` or `>`). */
    val start: Int,
    /** Where it ends: the next whitespace after the caret, or the end. */
    val end: Int,
    /** What is matched: the part from [start] to the caret. */
    val prefix: String,
    val namesOnly: Boolean,
)

fun wordAt(text: String, caret: Int): Word? {
    if (caret < 0 || caret > text.length) return null
    var start = caret
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    var end = caret
    while (end < text.length && !text[end].isWhitespace()) end++
    var namesOnly = false
    if (start < caret && (text[start] == '@' || text[start] == '>')) {
        start++
        namesOnly = true
    }
    val prefix = text.substring(start, caret)
    if (prefix.isEmpty()) return null
    return Word(start, end, prefix, namesOnly)
}

/** Puts [choice] in place of [word], followed by one space; returns the text and the caret after the space. */
fun complete(text: String, word: Word, choice: String): Pair<String, Int> {
    val before = text.substring(0, word.start)
    var after = text.substring(word.end)
    if (after.isEmpty() || !after[0].isWhitespace()) after = " $after"
    return (before + choice + after) to (before.length + choice.length + 1)
}

/**
 * Puts [insert] (an emote picked from the list, a tapped name) at the caret
 * as a word of its own, with spaces around it as needed.
 */
fun insertWord(text: String, caret: Int, insert: String): Pair<String, Int> {
    val at = caret.coerceIn(0, text.length)
    val before = text.substring(0, at)
    val after = text.substring(at)
    val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
    val trail = if (after.isNotEmpty() && after[0].isWhitespace()) "" else " "
    return (before + lead + insert + trail + after) to (before.length + lead.length + insert.length + 1)
}

/** One suggestion in the strip above the keyboard. */
data class Suggestion(val text: String, val kind: Kind) {
    enum class Kind { Emote, Name, Command }
}

/**
 * Suggestions for the word being written: emotes the user may send, names,
 * and commands at the start of the input. chat-gui's order: emotes first,
 * then names; within each, what was picked or heard most recently first,
 * then alphabetically.
 *
 * Everything that changes as chat runs is updated as it arrives, so a
 * keystroke costs a binary search in two sorted arrays and a pass over the
 * few hundred recent speakers.
 *
 * Not thread-safe: the chat coroutine and the screen share the main thread.
 */
class Completer(private val recentCap: Int = 500) {
    private var emotes = Sorted(emptyList())
    private var emoteSet: Set<String> = emptySet()
    private var roster = Sorted(emptyList())
    private var commands: List<String> = emptyList()
    // Lower-case nick to the name as written and when it was last heard.
    private val heard = LinkedHashMap<String, Heard>()
    // When each suggestion was last picked.
    private val picked = HashMap<String, Long>()

    /** Changes when the lists do, so a screen showing suggestions looks again. */
    var version by mutableIntStateOf(0)
        private set

    private class Heard(val nick: String, val at: Long)

    /** Names sorted without case, with their lower-case forms for searching. */
    private class Sorted(names: Collection<String>) {
        val names: Array<String>
        val lower: Array<String>

        init {
            val sorted = names.distinct().sortedBy { it.lowercase() }
            this.names = sorted.toTypedArray()
            lower = Array(sorted.size) { sorted[it].lowercase() }
        }

        /** Indices of the names starting with [prefix] (lower case). */
        fun range(prefix: String): IntRange {
            var lo = 0
            var hi = lower.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (lower[mid] < prefix) lo = mid + 1 else hi = mid
            }
            var end = lo
            while (end < lower.size && lower[end].startsWith(prefix)) end++
            return lo until end
        }
    }

    fun setEmotes(prefixes: Collection<String>) {
        emotes = Sorted(prefixes)
        emoteSet = prefixes.toHashSet()
        version++
    }

    fun setCommands(names: List<String>) {
        commands = names
        version++
    }

    /** Who is in chat, from the roster sent on connect. */
    fun setRoster(nicks: Collection<String>) {
        roster = Sorted(nicks)
        version++
    }

    /** Someone spoke. The most recent [recentCap] speakers are kept. */
    fun heard(nick: String, at: Long) {
        val key = nick.lowercase()
        heard.remove(key)
        heard[key] = Heard(nick, at)
        if (heard.size > recentCap) {
            val it = heard.keys.iterator()
            it.next()
            it.remove()
        }
    }

    fun forget(nick: String) {
        heard.remove(nick.lowercase())
    }

    /** A suggestion was used: it comes first next time, as in chat-gui. */
    fun picked(text: String, at: Long) {
        picked[text] = at
        if (picked.size > recentCap) picked.remove(picked.minByOrNull { it.value }!!.key)
    }

    /** The emotes picked most recently, newest first. */
    fun recentEmotes(limit: Int): List<String> =
        picked.entries.filter { it.key in emoteSet }.sortedByDescending { it.value }.take(limit).map { it.key }

    fun suggest(word: Word, atStart: Boolean, skip: (String) -> Boolean = { false }, limit: Int = 20): List<Suggestion> {
        val prefix = word.prefix.lowercase()
        if (prefix.startsWith("/")) {
            if (!atStart || word.namesOnly) return emptyList()
            return commands.filter { it.startsWith(prefix) }.take(limit).map { Suggestion(it, Suggestion.Kind.Command) }
        }
        val out = ArrayList<Suggestion>()
        if (!word.namesOnly) {
            emotes.range(prefix).map { emotes.names[it] }
                .sortedByDescending { picked[it] ?: 0L } // stable: alphabetical within the same weight
                .take(limit)
                .mapTo(out) { Suggestion(it, Suggestion.Kind.Emote) }
        }
        if (out.size >= limit) return out
        val names = ArrayList<Pair<String, Long>>()
        val seen = HashSet<String>()
        for ((key, h) in heard) {
            if (key.startsWith(prefix) && !skip(key)) {
                names += h.nick to maxOf(h.at, picked[h.nick] ?: 0L)
                seen += key
            }
        }
        names.sortWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first.lowercase() })
        if (names.size > limit) names.subList(limit, names.size).clear()
        for (i in roster.range(prefix)) {
            val key = roster.lower[i]
            if (key !in seen && !skip(key)) {
                names += roster.names[i] to (picked[roster.names[i]] ?: 0L)
                if (names.size >= limit) break
            }
        }
        // A roster name picked before still comes ahead of ones never used.
        names.sortWith(compareByDescending<Pair<String, Long>> { it.second > 0 }.thenByDescending { it.second })
        for ((nick, _) in names) {
            if (out.size >= limit) break
            out += Suggestion(nick, Suggestion.Kind.Name)
        }
        return out
    }
}

/**
 * The nicks in a `NAMES` payload, read by scanning for `"nick":"…"` rather
 * than decoding the whole roster (a few hundred kilobytes at busy times, of
 * which only the names are kept).
 */
fun rosterNicks(payload: String): List<String> {
    val out = ArrayList<String>()
    val marker = "\"nick\":\""
    var i = payload.indexOf(marker)
    while (i >= 0) {
        val start = i + marker.length
        val end = payload.indexOf('"', start)
        if (end < 0) break
        if (end > start) out += payload.substring(start, end)
        i = payload.indexOf(marker, end)
    }
    return out
}
