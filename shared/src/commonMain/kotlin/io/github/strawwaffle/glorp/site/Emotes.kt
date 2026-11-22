package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/**
 * One emote. Sizes are CSS pixels, drawn as dp.
 *
 * [width] is what chat shows, which for a sprite strip is one frame, not the
 * picture: the strip is the frames side by side and the stylesheet steps
 * through them.
 */
data class Emote(
    val prefix: String,
    val url: String,
    val width: Int,
    val height: Int,
    val minimumSubTier: Int,
    val sprite: Sprite? = null,
)

/** A sprite strip animation, as the stylesheet runs it. */
data class Sprite(
    /** The whole strip's width in CSS pixels. */
    val sheetWidth: Int,
    val frames: Int,
    val durationMs: Long,
    /** [Int.MAX_VALUE] for `infinite`. */
    val iterations: Int,
    /** `forwards`: stays on the last frame instead of going back to the first. */
    val holdLast: Boolean,
) {
    val totalMs: Long get() = if (iterations == Int.MAX_VALUE) Long.MAX_VALUE else durationMs * iterations
}

/** The emote list, keyed by the word that draws each one. */
class Emotes private constructor(private val byPrefix: Map<String, Emote>) {
    operator fun get(word: String): Emote? = byPrefix[word]
    val size: Int get() = byPrefix.size

    companion object {
        val None = Emotes(emptyMap())

        @Serializable
        private data class Image(val url: String? = null, val width: Int = 0, val height: Int = 0)

        @Serializable
        private data class Entry(
            val prefix: String = "",
            val twitch: Boolean = false,
            val minimumSubTier: Int = 0,
            val image: List<Image> = emptyList(),
        )

        /**
         * Reads `emotes.json`, and from `emotes.css` the size chat draws each
         * emote at and its sprite animation. Other CSS effects (bounces, glows,
         * effects that depend on neighbouring emotes) are not read: those
         * emotes show still.
         */
        fun parse(json: String, css: String): Emotes {
            val rules = baseRules(css)
            val keyframes = keyframeOffsets(css)
            val map = HashMap<String, Emote>()
            for (entry in chatJson.decodeFromString<List<Entry>>(json)) {
                val image = entry.image.firstOrNull() ?: continue
                val url = image.url ?: continue
                // Twitch-only emotes are never drawn in destiny.gg chat.
                if (entry.prefix.isEmpty() || entry.twitch) continue
                val decls = rules[entry.prefix].orEmpty()
                val width = decls.lastPx("width") ?: image.width
                val height = decls.lastPx("height") ?: image.height
                val sprite = decls["animation"]?.let { spriteOf(it, image.width, width, keyframes) }
                map[entry.prefix] = Emote(entry.prefix, url, width, height, entry.minimumSubTier, sprite)
            }
            return Emotes(map)
        }

        suspend fun fetch(client: HttpClient, site: Site): Emotes = try {
            coroutineScope {
                val json = async { get(client, "${site.cdn}/emotes/emotes.json") }
                val css = async { get(client, "${site.cdn}/emotes/emotes.css") }
                val body = json.await()
                if (body == null) None else parse(body, css.await().orEmpty())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Glorp: emote list unavailable: $e")
            None
        }

        private suspend fun get(client: HttpClient, url: String): String? {
            val response = client.get(url) { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
            return if (response.status.isSuccess()) response.bodyAsText() else null
        }
    }
}

// --- The little CSS this needs ----------------------------------------------

private typealias Decls = Map<String, List<String>>

private fun Decls.lastPx(name: String): Int? =
    this[name]?.lastOrNull()?.let { Regex("""^(-?\d+(?:\.\d+)?)px$""").find(it.trim())?.groupValues?.get(1)?.toDouble()?.toInt() }

/**
 * Declarations of the rules whose whole selector is `.emote.NAME`, in file
 * order, so the last value of a property is the one that applies.
 */
private fun baseRules(css: String): Map<String, Decls> {
    val clean = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    val out = HashMap<String, HashMap<String, MutableList<String>>>()
    // The `}` before a rule is looked at, not taken, or a rule straight after
    // another would be missed.
    for (match in Regex("""(?:^|(?<=\}))\s*\.emote\.([A-Za-z0-9_]+)\s*\{([^{}]*)\}""").findAll(clean)) {
        val decls = out.getOrPut(match.groupValues[1]) { HashMap() }
        for (decl in match.groupValues[2].split(';')) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            val name = decl.substring(0, colon).trim().lowercase()
            decls.getOrPut(name) { ArrayList() }.add(decl.substring(colon + 1).trim())
        }
    }
    return out
}

/** For each `@keyframes`, the furthest `background-position` it moves to, in px. */
private fun keyframeOffsets(css: String): Map<String, Int> {
    val out = HashMap<String, Int>()
    val clean = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    val block = Regex("""@keyframes\s+([\w-]+)\s*\{((?:[^{}]*\{[^{}]*\})*)\s*\}""")
    for (match in block.findAll(clean)) {
        val positions = Regex("""background-position:\s*(-?\d+)px""").findAll(match.groupValues[2])
            .map { kotlin.math.abs(it.groupValues[1].toInt()) }.toList()
        if (positions.isNotEmpty()) out[match.groupValues[1]] = positions.max()
    }
    return out
}

/** Splits a list of animations on the commas between them, not those inside `calc(…)`. */
private fun topLevelParts(value: String): List<String> {
    val parts = ArrayList<String>()
    var depth = 0
    var start = 0
    for ((i, c) in value.withIndex()) {
        when (c) {
            '(' -> depth++
            ')' -> depth--
            ',' -> if (depth == 0) {
                parts += value.substring(start, i)
                start = i + 1
            }
        }
    }
    parts += value.substring(start)
    return parts
}

private val timeRegex = Regex("""(\d*\.?\d+)(ms|s)\b""")

/**
 * Reads an `animation` shorthand that steps a background through a strip, such
 * as `Askers-anim calc(3.5s / var(--animation-speed, 1)) steps(99) 4`.
 */
private fun spriteOf(values: List<String>, sheetWidth: Int, frameWidth: Int, keyframes: Map<String, Int>): Sprite? {
    for (part in topLevelParts(values.last())) {
        val steps = Regex("""steps\((\d+)""").find(part)?.groupValues?.get(1)?.toInt() ?: continue
        val name = part.trim().split(Regex("""\s+""")).firstOrNull { it in keyframes } ?: continue
        if (frameWidth <= 0 || sheetWidth <= frameWidth || steps <= 0) continue
        val time = timeRegex.find(part) ?: continue
        val durationMs = time.groupValues[1].toDouble().let { if (time.groupValues[2] == "s") it * 1000 else it }.toLong()
        // What's left once the name, times and steps are taken out is the
        // iteration count and the fill mode.
        val rest = part.replace(Regex("""calc\(.*\)"""), " ").replace(timeRegex, " ")
            .replace(Regex("""steps\([^)]*\)"""), " ").replace(name, " ")
        val iterations = when {
            "infinite" in rest -> Int.MAX_VALUE
            else -> Regex("""(?:^|\s)(\d+)(?:\s|$)""").find(rest)?.groupValues?.get(1)?.toInt() ?: 1
        }
        return Sprite(sheetWidth, steps, durationMs.coerceAtLeast(1), iterations, "forwards" in rest || "both" in rest)
    }
    return null
}
