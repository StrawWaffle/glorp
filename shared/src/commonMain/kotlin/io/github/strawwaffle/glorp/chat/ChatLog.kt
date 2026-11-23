package io.github.strawwaffle.glorp.chat

/**
 * The lines kept in memory: a fixed number, oldest dropped first.
 *
 * The website's chat grows its page as it goes and is known to slow down over
 * a long session. Here the cap is the whole memory budget for text, and the
 * set of ids shrinks with the lines, so nothing grows with time spent in chat.
 *
 * Runs of the same single emote become one combo line, as in chat-gui: a
 * message that is only an emote the sender may use, after a message or combo
 * of that same emote, joins it instead of taking a line of its own.
 *
 * Not thread-safe: one coroutine owns it.
 */
class ChatLog(capacity: Int = DEFAULT_CAPACITY) {
    /** How many lines are kept (chat-gui's `maxlines`); lowering it drops the oldest now. */
    var capacity: Int = capacity.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
        set(value) {
            field = value.coerceIn(MIN_CAPACITY, MAX_CAPACITY)
            while (lines.size > field) forget(lines.removeFirst())
        }

    private val lines = ArrayDeque<ChatLine>(this.capacity)
    private val ids = HashSet<String>(this.capacity * 2)
    // A combo line stands for several messages; their ids go when it goes.
    private val comboMembers = HashMap<String, MutableList<String>>()

    /**
     * Adds a line unless it is already here. [soleEmote] says which emote a
     * chat message consists of, if it's only one the sender may use.
     * Returns whether anything changed.
     */
    fun add(line: ChatLine, soleEmote: (ChatLine) -> String? = { null }): Boolean {
        if (!ids.add(line.id)) return false
        val emote = if (line.kind == ChatLine.Kind.User) soleEmote(line) else null
        val last = lines.lastOrNull()
        if (emote != null && last != null) {
            if (last.kind == ChatLine.Kind.Combo && last.text == emote) {
                lines[lines.lastIndex] = last.copy(comboCount = last.comboCount + 1)
                comboMembers.getOrPut(last.id) { ArrayList() }.add(line.id)
                return true
            }
            if (last.kind == ChatLine.Kind.User && soleEmote(last) == emote) {
                val combo = ChatLine.combo(emote, last, 2)
                lines[lines.lastIndex] = combo
                ids.add(combo.id)
                comboMembers[combo.id] = mutableListOf(last.id, line.id)
                return true
            }
        }
        lines.addLast(line)
        while (lines.size > capacity) forget(lines.removeFirst())
        return true
    }

    /** Marks every chat message by [nick] so far as moderated: a mute or ban, which chat-gui censors. */
    fun moderate(nick: String): Boolean {
        var changed = false
        for (i in lines.indices) {
            val line = lines[i]
            if (line.kind == ChatLine.Kind.User && !line.moderated && line.nick.equals(nick, ignoreCase = true)) {
                lines[i] = line.copy(moderated = true)
                changed = true
            }
        }
        return changed
    }

    /** Takes out every line by [nick] (someone just ignored). */
    fun removeNick(nick: String): Boolean {
        val gone = lines.filter { it.nick.equals(nick, ignoreCase = true) }
        if (gone.isEmpty()) return false
        lines.removeAll { it.nick.equals(nick, ignoreCase = true) }
        gone.forEach(::forget)
        return true
    }

    fun snapshot(): List<ChatLine> = lines.toList()

    private fun forget(line: ChatLine) {
        ids.remove(line.id)
        comboMembers.remove(line.id)?.forEach(ids::remove)
    }

    companion object {
        /** chat-gui's default `maxlines`. */
        const val DEFAULT_CAPACITY = 250
        const val MIN_CAPACITY = 50
        const val MAX_CAPACITY = 1000
    }
}
