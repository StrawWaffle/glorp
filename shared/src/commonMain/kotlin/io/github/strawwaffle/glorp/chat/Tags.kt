package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** chat-gui's tag colours, in its order. */
val TAG_COLORS = listOf("green", "yellow", "orange", "red", "purple", "blue", "sky", "lime", "pink", "black")

/**
 * Tagged users, kept as chat-gui keeps them: `taggednicks` holds
 * `[nick, colour]` pairs and `taggednotes` `[nick, note]` pairs, nicks in
 * lower case.
 */
class Tags(private val settings: ChatSettings) {
    val colors: Map<String, String> get() = pairs("taggednicks")
    val notes: Map<String, String> get() = pairs("taggednotes")

    fun tag(nick: String, color: String, note: String) {
        val key = nick.lowercase()
        setPairs("taggednicks", colors + (key to color))
        setPairs("taggednotes", notes + (key to note))
    }

    fun untag(nick: String) {
        val key = nick.lowercase()
        setPairs("taggednicks", colors - key)
        setPairs("taggednotes", notes - key)
    }

    fun clear() {
        setPairs("taggednicks", emptyMap())
        setPairs("taggednotes", emptyMap())
    }

    private fun pairs(key: String): Map<String, String> =
        (settings[key] as? JsonArray).orEmpty().mapNotNull { pair ->
            val items = (pair as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull } ?: return@mapNotNull null
            val nick = items.getOrNull(0) ?: return@mapNotNull null
            nick to (items.getOrNull(1) ?: "")
        }.toMap()

    private fun setPairs(key: String, pairs: Map<String, String>) =
        settings.set(key, JsonArray(pairs.map { (k, v) -> JsonArray(listOf(JsonPrimitive(k), JsonPrimitive(v))) }))
}

/**
 * The colour and note `/tag nick [colour] [note]` gives, as chat-gui reads
 * the words after the nick: a known colour first, the rest the note (the old
 * note when there is none, cut to 100 characters). No colour picks one at
 * random.
 */
internal fun readTag(args: List<String>, oldNote: String, random: () -> String): Pair<String, String> {
    if (args.isEmpty()) return random() to oldNote
    val first = args[0].lowercase()
    val (color, words) = if (first in TAG_COLORS) first to args.drop(1) else random() to args
    val note = if (words.isEmpty()) oldNote else words.joinToString(" ")
    return color to note.take(100)
}

/** The list `/tag` and `/untag` show, in chat-gui's words. */
internal fun tagList(colors: Map<String, String>, notes: Map<String, String>): String =
    if (colors.isEmpty()) "No tagged nicks."
    else "Tagged nicks\n\n" + colors.entries.joinToString("\n") { (nick, color) -> "    $nick ($color) ${notes[nick].orEmpty()}".trimEnd() }

/**
 * Which messages chat highlights, as chat-gui decides: ones naming you
 * (when `highlight` is on), ones from the nicks in `highlightnicks`, and ones
 * whose sender or text has a word from `customhighlight`. Your own never are.
 */
class Highlighter(me: String?, self: Boolean, nicks: Collection<String>, words: List<String>) {
    private val selfPattern = me?.takeIf { self && it.isNotEmpty() }?.let { wordsPattern(listOf(Regex.escape(it))) }
    private val me = me?.lowercase()
    private val nicks = nicks.map { it.lowercase() }.toSet()
    // chat-gui puts the words into its pattern as they are, so a word can be a
    // pattern too; one that isn't valid is matched as plain text.
    private val custom = words.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { list ->
        runCatching { wordsPattern(list) }.getOrElse { wordsPattern(list.map(Regex::escape)) }
    }

    val isEmpty get() = selfPattern == null && nicks.isEmpty() && custom == null

    fun highlights(nick: String?, text: String): Boolean {
        val from = nick?.lowercase()
        if (from == null || from == me) return false
        if (from in nicks) return true
        if (selfPattern?.containsMatchIn(text.replace(link, "")) == true) return true
        return custom?.containsMatchIn("$nick $text") == true
    }

    private companion object {
        val link = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

        fun wordsPattern(parts: List<String>) = Regex("""\b(?:${parts.joinToString("|")})\b""", RegexOption.IGNORE_CASE)
    }
}

/** How the settings page reads the words: split at commas, trimmed, each once. */
fun highlightWords(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
