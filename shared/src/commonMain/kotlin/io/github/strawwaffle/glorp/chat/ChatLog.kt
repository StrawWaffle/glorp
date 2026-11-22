package io.github.strawwaffle.glorp.chat

/**
 * The lines kept in memory: a fixed number, oldest dropped first.
 *
 * The website's chat grows its page as it goes and is known to slow down over
 * a long session. Here the cap is the whole memory budget for text, and the
 * set of ids shrinks with the lines, so nothing grows with time spent in chat.
 *
 * Not thread-safe: one coroutine owns it.
 */
class ChatLog(private val capacity: Int = DEFAULT_CAPACITY) {
    private val lines = ArrayDeque<ChatLine>(capacity)
    private val ids = HashSet<String>(capacity * 2)

    /** Adds a line unless it is already here. Returns whether it was added. */
    fun add(line: ChatLine): Boolean {
        if (!ids.add(line.id)) return false
        lines.addLast(line)
        while (lines.size > capacity) ids.remove(lines.removeFirst().id)
        return true
    }

    /** Takes out every line by [nick], as chat does when a moderator mutes or bans. */
    fun removeNick(nick: String): Boolean {
        val removed = lines.removeAll { it.nick.equals(nick, ignoreCase = true) }
        if (removed) {
            ids.clear()
            lines.mapTo(ids) { it.id }
        }
        return removed
    }

    fun snapshot(): List<ChatLine> = lines.toList()

    companion object {
        const val DEFAULT_CAPACITY = 300
    }
}
