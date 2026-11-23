package io.github.strawwaffle.glorp.diag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * What the app did lately, in memory only, for a problem report the user
 * chooses to send: connections, failures, the player giving up. Never chat
 * messages, names or logins, and addresses lose their query (where stream
 * tokens are). Gone when the app closes, except what a crash saves.
 */
object AppLog {
    private const val KEEP = 300

    private class Entry(val at: Long, val tag: String, val text: String)

    private val entries = MutableStateFlow<List<Entry>>(emptyList())

    fun note(tag: String, text: String) {
        val entry = Entry(Clock.System.now().toEpochMilliseconds(), tag, redact(text))
        println("Glorp $tag: ${entry.text}")
        entries.update { (if (it.size >= KEEP) it.drop(it.size - KEEP + 1) else it) + entry }
    }

    /** An exception as one line: its kind and message, without the stack. */
    fun note(tag: String, what: String, error: Throwable) =
        note(tag, "$what: ${error::class.simpleName}: ${error.message.orEmpty().take(300)}")

    /** The log, oldest first, one line per entry with the time in UTC. */
    fun lines(): List<String> = entries.value.map { "${clock(it.at)} ${it.tag}: ${it.text}" }

    internal fun clear() = entries.update { emptyList() }

    private fun clock(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString().substring(11, 19)
}

private val urlTail = Regex("""(https?://[^\s?#"']+)[?#][^\s"']*""")
private val secrets = Regex("""(?i)\b(sid|rememberme|authtoken|token|key|sig|signature)=[^\s&;"']+""")

/** Takes the query off addresses and the value off anything that looks like a login or token. */
internal fun redact(text: String): String =
    text.replace(urlTail) { it.groupValues[1] + "?…" }.replace(secrets) { it.groupValues[1] + "=…" }
