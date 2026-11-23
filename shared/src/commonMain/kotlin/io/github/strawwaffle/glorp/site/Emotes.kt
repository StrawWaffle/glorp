package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.diag.AppLog
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
 * [width] by [height] is what chat shows. The picture behind it is drawn at
 * [sheetWidth] by [sheetHeight] and shifted left by [offsetX], the way the
 * stylesheet's `background-size` and `background-position` place it: many
 * pictures are strips of frames or variants side by side, of which one shows.
 */
data class Emote(
    val prefix: String,
    val url: String,
    val width: Int,
    val height: Int,
    val minimumSubTier: Int,
    val sprite: Sprite? = null,
    val sheetWidth: Int = width,
    val sheetHeight: Int = height,
    val offsetX: Int = 0,
    /** The stylesheet's hand-made movements: shakes, turns, bounces, fades, glows. */
    val motions: List<Motion> = emptyList(),
    /** `transform-origin`, as fractions of the emote's size. */
    val originX: Float = 0.5f,
    val originY: Float = 0.5f,
    /** Its still `transform` (a mirrored emote facing its neighbour), under any movement. */
    val rest: Pose = Pose.Rest,
    /** `margin-left` and `margin-right` in px: negative ones let neighbours overlap (RapThis, TRIAD). */
    val marginLeft: Float = 0f,
    val marginRight: Float = 0f,
    /** `vertical-align` in px: negative lowers it (BUTTSTINY's -5px). */
    val verticalAlign: Float = 0f,
) {
    /** Whether the picture is drawn whole, scaled to the emote's size, and still. */
    val isWholePicture: Boolean get() = sprite == null && motions.isEmpty() && rest == Pose.Rest && offsetX == 0 && sheetWidth == width && sheetHeight == height

    /** When the last finite movement or sprite run ends, after the message; [Long.MAX_VALUE] if one never does. */
    val settlesAfterMs: Long
        get() = maxOf(sprite?.totalMs ?: 0, motions.maxOfOrNull { it.endMs } ?: 0)
}

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
    /** Where the strip starts while it plays, in px from the picture's left; null for where it rests. */
    val startX: Int? = null,
    /** `animation-delay`; a negative one starts partway through (TRIAD, POPPERS). */
    val delayMs: Long = 0,
    /** How far each step moves the strip, in px, where it isn't the emote's width (PepoPunch's 56 in a 47 px box). */
    val stepX: Int? = null,
) {
    /** When it stops, from when the message was sent. */
    val totalMs: Long get() = if (iterations == Int.MAX_VALUE) Long.MAX_VALUE else delayMs + durationMs * iterations

    /** The frame showing [at] ms after the message was sent; null while it isn't playing (before its delay, or done). */
    fun frameAt(at: Long): Int? {
        val t = at - delayMs
        if (t < 0 || at >= totalMs) return null
        return ((t % durationMs) * frames / durationMs).toInt()
    }
}

/** What the site says about an emote beyond its picture: who made it, and who may use it. */
data class EmoteInfo(val creator: String?, val minimumSubTier: Int, val patreonOnly: Boolean)

/** The emote list, keyed by the word that draws each one. */
class Emotes private constructor(
    private val byPrefix: Map<String, Emote>,
    private val builder: Builder? = null,
    private val infos: Map<String, EmoteInfo> = emptyMap(),
) {
    operator fun get(word: String): Emote? = byPrefix[word]

    /** The emote's creator and requirement, as the site's emote card shows them. */
    fun info(word: String): EmoteInfo? = infos[word]

    /**
     * The emotes of one line as they look there: the stylesheet changes some
     * by what's next to them, what else the line holds, or a combo's size.
     * [parts] are the line's emotes and links in order (null for a link);
     * the result has the emote to draw for each emote part, in order.
     */
    internal fun inLine(line: LineModel): List<Emote?> {
        val b = builder ?: return line.emotes.map { it.emoteName?.let(byPrefix::get) }
        val extra = b.context.declarations(line)
        return line.emotes.mapIndexed { i, el ->
            val base = el.emoteName?.let(byPrefix::get) ?: return@mapIndexed null
            val added = extra[i]
            if (added.isEmpty()) base
            else b.variants.getOrPut(base.prefix + "\u0000" + added.joinToString(";")) { b.variant(base, added) }
        }
    }

    /** The same emotes with the rules for how they look beside others. */
    private fun withNeighbours(context: EmoteContext): Emotes =
        Emotes(byPrefix, builder?.let { Builder(it.own, it.images, it.keyframes, it.moving, context) }, infos)

    /** What building variants needs: each emote's own rules and image, the keyframes and the context rules. */
    private class Builder(
        val own: Map<String, Map<String, List<String>>>,
        val images: Map<String, Pair<Int, Int>>,
        val keyframes: Map<String, StripKeyframes>,
        val moving: Map<String, List<KeyFrame>>,
        val context: EmoteContext,
    ) {
        val variants = HashMap<String, Emote>()

        fun variant(base: Emote, added: List<Pair<String, String>>): Emote {
            val decls = HashMap<String, MutableList<String>>()
            own[base.prefix]?.forEach { (k, v) -> decls[k] = v.toMutableList() }
            for ((name, value) in added) decls.getOrPut(name) { ArrayList() } += value
            val (w, h) = images[base.prefix] ?: (base.width to base.height)
            // A rule can swap the picture (a combo's own GIF): relative to the emote's own.
            // Only when the line's rules set one; the emote's own is the list's picture.
            val image = (if (added.none { it.first == "background-image" }) null else decls.value("background-image"))?.let { Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""").find(it)?.groupValues?.get(1) }
            val url = when {
                image == null -> base.url
                image.startsWith("http") -> image
                image.startsWith("data:") -> base.url
                else -> base.url.substringBeforeLast('/') + "/" + image
            }
            return build(base.prefix, url, w, h, base.minimumSubTier, decls, keyframes, moving)
        }
    }
    val size: Int get() = byPrefix.size

    /** Every emote, in the order the site lists them. */
    val all: Collection<Emote> get() = byPrefix.values

    companion object {
        val None = Emotes(emptyMap())

        @Serializable
        private data class Image(val url: String? = null, val width: Int = 0, val height: Int = 0)

        @Serializable
        private data class Entry(
            val prefix: String = "",
            val twitch: Boolean = false,
            val minimumSubTier: Int = 0,
            val creator: String? = null,
            val patreonOnly: Boolean? = null,
            val image: List<Image> = emptyList(),
        )

        /**
         * Reads `emotes.json`, and from `emotes.css` the size chat draws each
         * emote at, its sprite animation and its hand-made movements
         * (transform, opacity and filter keyframes). Effects that depend
         * on neighbouring emotes, on combos or on hovering, and other filters,
         * are not read.
         */
        fun parse(json: String, css: String): Emotes {
            val clean = withoutComments(css)
            val context = runCatching { EmoteContext.parse(clean) }.getOrDefault(EmoteContext.None)
            return assemble(json, baseRules(css), keyframeOffsets(css), motionKeyframes(clean), context)
        }

        private fun withoutComments(css: String) = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

        private fun assemble(
            json: String,
            rules: Map<String, Decls>,
            keyframes: Map<String, StripKeyframes>,
            moving: Map<String, List<KeyFrame>>,
            context: EmoteContext,
        ): Emotes {
            val map = LinkedHashMap<String, Emote>()
            val images = HashMap<String, Pair<Int, Int>>()
            val infos = HashMap<String, EmoteInfo>()
            for (entry in chatJson.decodeFromString<List<Entry>>(json)) {
                val image = entry.image.firstOrNull() ?: continue
                val url = image.url ?: continue
                // Twitch-only emotes are never drawn in destiny.gg chat.
                if (entry.prefix.isEmpty() || entry.twitch) continue
                val decls = rules[entry.prefix].orEmpty()
                images[entry.prefix] = image.width to image.height
                map[entry.prefix] = build(entry.prefix, url, image.width, image.height, entry.minimumSubTier, decls, keyframes, moving)
                infos[entry.prefix] = EmoteInfo(entry.creator?.trim()?.takeIf { it.isNotEmpty() }, entry.minimumSubTier, entry.patreonOnly == true)
            }
            return Emotes(map, Builder(rules, images, keyframes, moving, context), infos)
        }

        /**
         * The site's emotes, read off the main thread with the stylesheet's
         * parts read side by side. [onFirst] gets them as soon as each emote's
         * own rules are read (about a fifth of a second on a phone), so chat
         * can start; the rules for emotes next to others come after, in what
         * this returns.
         */
        suspend fun fetch(client: HttpClient, site: Site, onFirst: (Emotes) -> Unit = {}): Emotes = try {
            coroutineScope {
                val json = async { get(client, "${site.cdn}/emotes/emotes.json") }
                val css = async { get(client, "${site.cdn}/emotes/emotes.css") }
                val body = json.await()
                val cssText = css.await().orEmpty()
                if (body == null) None
                else withContext(Dispatchers.Default) {
                    val clean = withoutComments(cssText)
                    val context = async { runCatching { EmoteContext.parse(clean) }.getOrDefault(EmoteContext.None) }
                    val rules = async { baseRules(cssText) }
                    val keyframes = async { keyframeOffsets(cssText) }
                    val moving = async { motionKeyframes(clean) }
                    val first = assemble(body, rules.await(), keyframes.await(), moving.await(), EmoteContext.None)
                    onFirst(first)
                    first.withNeighbours(context.await())
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.note("emotes", "list unavailable", e)
            None
        }

        private suspend fun get(client: HttpClient, url: String): String? {
            val response = client.get(url) { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
            return if (response.status.isSuccess()) response.bodyAsText() else null
        }
    }
}

// --- The little CSS this needs ----------------------------------------------

/** One emote from its rules' declarations and its picture's own size. */
private fun build(
    prefix: String,
    url: String,
    imageWidth: Int,
    imageHeight: Int,
    minimumSubTier: Int,
    decls: Decls,
    keyframes: Map<String, StripKeyframes>,
    moving: Map<String, List<KeyFrame>>,
): Emote {
    var width = decls.px("width") ?: imageWidth.toDouble()
    var height = decls.px("height") ?: imageHeight.toDouble()
    val size = decls.backgroundSize()
    var sheetWidth = size?.first ?: imageWidth.toDouble()
    var sheetHeight = size?.second ?: if (size != null && imageWidth > 0) size.first * imageHeight / imageWidth else imageHeight.toDouble()
    var offset = decls.backgroundX()
    // No emote may be bigger than a line can take: a stylesheet this parser
    // misreads must not draw a picture over chat.
    val shrink = minOf(1.0, MAX_HEIGHT / height, MAX_WIDTH / width)
    width *= shrink; height *= shrink; sheetWidth *= shrink; sheetHeight *= shrink; offset *= shrink
    val animation = decls.value("animation")?.let { listOf(it) }
    var sprite = animation?.let { spriteOf(it, sheetWidth.toInt(), width.toInt(), keyframes) { token -> decls.resolvePx(token) } }
        ?.let { sp -> sp.copy(startX = sp.startX?.let { (it * shrink).toInt() }, stepX = sp.stepX?.let { (it * shrink).toInt() }?.takeIf { it != width.toInt() }) }
    var motions = animation?.let { motionsOf(topLevelParts(it.last()), moving) }.orEmpty()
    // Longhands after the shorthand change every animation in it (`.emote.SOY + .emote.Clap`).
    decls.value("animation-duration")?.let(::cssMillis)?.takeIf { it > 0 }?.let { ms ->
        sprite = sprite?.copy(durationMs = ms); motions = motions.map { it.copy(durationMs = ms) }
    }
    decls.value("animation-delay")?.let(::cssMillis)?.let { ms -> motions = motions.map { it.copy(delayMs = ms) } }
    decls.value("animation-iteration-count")?.trim()?.let { v ->
        val n = if (v == "infinite") Int.MAX_VALUE else v.toDoubleOrNull()?.toInt()?.coerceAtLeast(1)
        if (n != null) { sprite = sprite?.copy(iterations = n); motions = motions.map { it.copy(iterations = n) } }
    }
    val (originX, originY) = readOrigin(decls.value("transform-origin"), width / shrink, height / shrink)
    // The still transform, and the separate `translate`, `rotate` and `scale` properties.
    var rest = decls.value("transform")?.let(::readTransform) ?: Pose.Rest
    decls.value("translate")?.let { readTransform("translate(${it.trim().replace(Regex("""\s+"""), ",")})") }?.let { rest = rest.copy(tx = rest.tx + it.tx, ty = rest.ty + it.ty) }
    decls.value("rotate")?.let { readTransform("rotate(${it.trim()})") }?.let { rest = rest.copy(rotate = rest.rotate + it.rotate) }
    // Its still `filter`: a tint for a flair, a glow.
    decls.value("filter")?.let(::readFilter)?.takeIf { !it.isNone }?.let { rest = rest.copy(filter = it) }
    decls.value("scale")?.let { readTransform("scale(${it.trim().replace(Regex("""\s+"""), ",")})") }?.let { rest = rest.copy(sx = rest.sx * it.sx, sy = rest.sy * it.sy) }
    val (marginLeft, marginRight) = decls.margins()
    val verticalAlign = decls.px("vertical-align")?.toFloat() ?: 0f
    return Emote(
        prefix, url, width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1), minimumSubTier, sprite,
        sheetWidth = sheetWidth.toInt().coerceAtLeast(1), sheetHeight = sheetHeight.toInt().coerceAtLeast(1), offsetX = offset.toInt(),
        // A motion whose keyframes this can't read moves nothing; kept, an endless
        // one would redraw the emote every frame for nothing (EatFresh's x5 combo).
        motions = motions.map { m -> m.slidingFrom(offset, shrink).withOrigins(width / shrink, height / shrink, originX to originY).withBox(width / shrink, marginLeft.toDouble(), shrink) }
            .filter { m -> m.frames.any { it.transform != null || it.opacity != null || it.filter != null || it.background != null || it.origin != null || it.clip != null || it.width != null || it.marginLeft != null } },
        originX = originX, originY = originY, rest = rest,
        marginLeft = marginLeft, marginRight = marginRight, verticalAlign = verticalAlign,
    )
}

/**
 * This motion with its `background-position` keyframes made relative to
 * where the emote's own rules put the picture ([offset] to the left, already
 * shrunk), so the emote is drawn where it rests and slid from there
 * (GIGACHAD arriving from the left). A stepped one is a sprite strip, which
 * [Sprite] plays: its positions are dropped here.
 */
private fun Motion.slidingFrom(offset: Double, shrink: Double): Motion {
    if (frames.none { it.background != null }) return this
    return copy(frames = frames.map { f ->
        val (x, y) = f.background ?: return@map f
        val background = if (easing is Easing.Steps) null else ((x * shrink + offset).toFloat() to (y * shrink).toFloat())
        f.copy(background = background)
    })
}

/**
 * This motion's `width` and `margin-left` keyframes as changes from the
 * emote's own ([width], [marginLeft] px), shrunk with it: drawn only, as the
 * line's layout stays put (PepoPunch widens by as much as its margin takes back).
 */
private fun Motion.withBox(width: Double, marginLeft: Double, shrink: Double): Motion {
    if (frames.none { it.width != null || it.marginLeft != null }) return this
    return copy(frames = frames.map { f ->
        f.copy(width = f.width?.let { ((it - width) * shrink).toFloat() }, marginLeft = f.marginLeft?.let { ((it - marginLeft) * shrink).toFloat() })
    })
}

/** `margin-left` and `margin-right` in px, from the longhands or `margin` / `margin-inline`. */
private fun Decls.margins(): Pair<Float, Float> {
    fun len(v: String?) = v?.trim()?.let { if (it == "0") 0.0 else pxRegex.find(it)?.groupValues?.get(1)?.toDouble() }
    var left = 0.0
    var right = 0.0
    value("margin")?.trim()?.split(Regex("""\s+"""))?.let { p ->
        when (p.size) {
            1, 2 -> { left = len(p.getOrNull(1) ?: p[0]) ?: 0.0; right = left }
            3 -> { left = len(p[1]) ?: 0.0; right = left }
            4 -> { right = len(p[1]) ?: 0.0; left = len(p[3]) ?: 0.0 }
        }
    }
    value("margin-inline")?.trim()?.split(Regex("""\s+"""))?.let { p -> left = len(p[0]) ?: left; right = len(p.getOrNull(1) ?: p[0]) ?: right }
    len(value("margin-left"))?.let { left = it }
    len(value("margin-right"))?.let { right = it }
    return left.toFloat() to right.toFloat()
}

/** This motion's `transform-origin` keyframes as fractions of the emote's size ([width] by [height] CSS px). */
private fun Motion.withOrigins(width: Double, height: Double, own: Pair<Float, Float>): Motion =
    copy(restOrigin = own, frames = frames.map { f -> f.originText?.let { f.copy(origin = readOrigin(it, width, height)) } ?: f })

private fun cssMillis(text: String): Long? {
    val v = text.trim().substringBefore(',').trim()
    return when {
        v.endsWith("ms") -> v.removeSuffix("ms").toDoubleOrNull()?.toLong()
        v.endsWith("s") -> v.removeSuffix("s").toDoubleOrNull()?.times(1000)?.toLong()
        else -> null
    }
}

private typealias Decls = Map<String, List<String>>

private const val MAX_HEIGHT = 64.0
private const val MAX_WIDTH = 256.0

private val varRegex = Regex("""var\(\s*(--[\w-]+)\s*(?:,\s*([^)]*))?\)""")
private val pxRegex = Regex("""^(-?\d+(?:\.\d+)?)px$""")

/** The last value of [name], with `var(--x)` replaced by the rules' own `--x`. */
private fun Decls.value(name: String): String? {
    var v = this[name]?.lastOrNull() ?: return null
    repeat(3) {
        v = varRegex.replace(v) { m -> this[m.groupValues[1]]?.lastOrNull() ?: m.groupValues[2] }
    }
    return v.replace("!important", "").trim()
}

private fun Decls.px(name: String): Double? = value(name)?.let { pxRegex.find(it)?.groupValues?.get(1)?.toDouble() }

/** `background-size: Wpx Hpx` (one length: the height follows the picture), or null for the picture's own size. */
private fun Decls.backgroundSize(): Pair<Double, Double?>? {
    val parts = value("background-size")?.split(Regex("""\s+""")) ?: return null
    val w = parts.getOrNull(0)?.let { pxRegex.find(it)?.groupValues?.get(1)?.toDouble() } ?: return null
    val h = parts.getOrNull(1)?.let { pxRegex.find(it)?.groupValues?.get(1)?.toDouble() }
    // One length is the width; the height is then `auto`, from the picture's shape.
    return w to h
}

/** How far left `background-position` shifts the picture, in px (0 for keywords like `center`). */
private fun Decls.backgroundX(): Double {
    val first = value("background-position")?.split(Regex("""\s+"""))?.firstOrNull() ?: return 0.0
    return pxRegex.find(first)?.groupValues?.get(1)?.toDouble()?.let { -it }?.coerceAtLeast(0.0) ?: 0.0
}

/**
 * Declarations of the rules whose whole selector is `.emote.NAME` or
 * `.text > .emote.NAME` (an emote in a message), in file order, so the last
 * value of a property is the one that applies.
 */
private fun baseRules(css: String): Map<String, Decls> {
    val clean = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    val out = HashMap<String, HashMap<String, MutableList<String>>>()
    // The `}` before a rule is looked at, not taken, or a rule straight after
    // another would be missed.
    for (match in Regex("""(?:^|(?<=\}))\s*(?:\.text\s*>\s*)?\.emote\.([A-Za-z0-9_-]+)\s*\{([^{}]*)\}""").findAll(clean)) {
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

/**
 * A `@keyframes` that moves a strip: its `background-position` values as
 * written (`-2880px`, or `var(--end)` for each emote to fill in), and the one
 * at `0%`/`from`, if it says where the strip starts.
 */
data class StripKeyframes(val positions: List<String>, val start: String?)

/** For each `@keyframes`, the `background-position` values it moves through. */
private fun keyframeOffsets(css: String): Map<String, StripKeyframes> {
    val out = HashMap<String, StripKeyframes>()
    val clean = css.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
    val block = Regex("""@keyframes\s+([\w-]+)\s*\{((?:[^{}]*\{[^{}]*\})*)\s*\}""")
    val step = Regex("""([^{}]+)\{([^{}]*)\}""")
    val position = Regex("""background-position:\s*([^;}\s]+(?:\([^)]*\))?)""")
    for (match in block.findAll(clean)) {
        val positions = ArrayList<String>()
        var start: String? = null
        for (s in step.findAll(match.groupValues[2])) {
            val value = position.find(s.groupValues[2])?.groupValues?.get(1) ?: continue
            positions += value
            val selectors = s.groupValues[1].split(',').map { it.trim() }
            if ("0%" in selectors || "from" in selectors) start = value
        }
        if (positions.isNotEmpty()) out[match.groupValues[1]] = StripKeyframes(positions, start)
    }
    return out
}

/** A length as the emote's rules give it: `-2880px`, or `var(--end)` filled in from them. */
private fun Decls.resolvePx(token: String): Double? {
    var v = token
    repeat(3) { v = varRegex.replace(v) { m -> this[m.groupValues[1]]?.lastOrNull() ?: m.groupValues[2] } }
    return pxRegex.find(v.trim())?.groupValues?.get(1)?.toDouble()
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

private val timeRegex = Regex("""(-?\d*\.?\d+)(ms|s)\b""")

/**
 * Reads an `animation` shorthand that steps a background through a strip, such
 * as `Askers-anim calc(3.5s / var(--animation-speed, 1)) steps(99) 4`.
 */
private fun spriteOf(
    values: List<String>,
    sheetWidth: Int,
    frameWidth: Int,
    keyframes: Map<String, StripKeyframes>,
    resolve: (String) -> Double?,
): Sprite? {
    for (part in topLevelParts(values.last())) {
        val steps = Regex("""steps\((\d+)""").find(part)?.groupValues?.get(1)?.toInt() ?: continue
        val name = part.trim().split(Regex("""\s+""")).firstOrNull { it in keyframes } ?: continue
        val strip = keyframes.getValue(name)
        // Keyframes written with variables only move a strip once this emote fills them in.
        if (strip.positions.none { resolve(it) != null }) continue
        val startX = strip.start?.let(resolve)?.let { kotlin.math.abs(it).toInt() }
        if (frameWidth <= 0 || sheetWidth <= frameWidth || steps <= 0) continue
        val times = timeRegex.findAll(part).map { t -> t.groupValues[1].toDouble().let { if (t.groupValues[2] == "s") it * 1000 else it }.toLong() }.toList()
        val durationMs = times.firstOrNull() ?: continue
        // What's left once the name, times and steps are taken out is the
        // iteration count and the fill mode.
        val rest = part.replace(Regex("""calc\(.*\)"""), " ").replace(timeRegex, " ")
            .replace(Regex("""steps\([^)]*\)"""), " ").replace(name, " ")
        val iterations = when {
            "infinite" in rest -> Int.MAX_VALUE
            else -> Regex("""(?:^|\s)(\d+)(?:\s|$)""").find(rest)?.groupValues?.get(1)?.toInt() ?: 1
        }
        // Each step moves the strip by its whole travel over the steps.
        val from = strip.start?.let(resolve) ?: 0.0
        val to = strip.positions.lastOrNull()?.let(resolve)
        val stepX = to?.let { kotlin.math.abs(it - from) / steps }?.takeIf { it > 0 }?.let { kotlin.math.round(it).toInt() }
        return Sprite(sheetWidth, steps, durationMs.coerceAtLeast(1), iterations, "forwards" in rest || "both" in rest, startX, times.getOrElse(1) { 0L }, stepX)
    }
    return null
}
