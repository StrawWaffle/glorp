package io.github.strawwaffle.glorp.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The last messages and commands sent, as chat-gui keeps them: twenty at
 * most, the same one twice in a row only once, kept between launches.
 * [older] and [newer] step through them the way the up and down keys do,
 * and coming back past the newest gives back what was being written.
 */
class InputHistory(private val prefs: ChatPrefs, private val max: Int = 20) {
    private val entries = ArrayList(prefs.getList(KEY).takeLast(max))
    private var index = -1
    private var stash = ""

    /** Newest first, for showing them to pick from. */
    var recent: List<String> by mutableStateOf(entries.asReversed().toList())
        private set

    fun add(text: String) {
        index = -1
        if (entries.lastOrNull() == text) return
        entries.add(text)
        while (entries.size > max) entries.removeAt(0)
        recent = entries.asReversed().toList()
        prefs.putList(KEY, entries)
    }

    /** The entry before the one shown, or null when there is none. [current] is kept for coming back. */
    fun older(current: String): String? {
        if (index < 0) {
            if (entries.isEmpty()) return null
            stash = current
            index = entries.size
        }
        if (index == 0) return null
        index--
        return entries[index]
    }

    /** The entry after the one shown, or what was being written; null when not stepping. */
    fun newer(): String? {
        if (index < 0) return null
        index++
        if (index >= entries.size) {
            index = -1
            return stash
        }
        return entries[index]
    }

    /** Typing ends the stepping. */
    fun reset() {
        index = -1
    }

    private companion object {
        const val KEY = "history"
    }
}

/**
 * A list of nicks in chat's settings, compared without case: who is
 * ignored (`ignorenicks`), whose messages are highlighted (`highlightnicks`).
 */
class NickSet(private val settings: ChatSettings, private val key: String) {
    /** Lower case. */
    val nicks: Set<String> get() = settings.list(key).map { it.lowercase() }.toSet()

    operator fun contains(nick: String?): Boolean = nick != null && nick.lowercase() in nicks

    /** Adds them; returns the ones that weren't there yet. */
    fun add(names: Collection<String>): List<String> {
        val added = names.map { it.lowercase() }.distinct().filter { it !in nicks }
        if (added.isNotEmpty()) save(nicks + added)
        return added
    }

    /** Takes them out; returns the ones that were there. */
    fun remove(names: Collection<String>): List<String> {
        val removed = names.map { it.lowercase() }.distinct().filter { it in nicks }
        if (removed.isNotEmpty()) save(nicks - removed.toSet())
        return removed
    }

    fun clear() = save(emptySet())

    private fun save(new: Set<String>) = settings.setList(key, new.sorted())
}
