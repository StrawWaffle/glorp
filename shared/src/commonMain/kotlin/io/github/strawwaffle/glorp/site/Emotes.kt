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
    /** How far down its own `background-position` puts the picture (BoomerSippy's `0 5px`), in px. */
    val offsetY: Int = 0,
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
    /** Whether the picture tiles across (`background-repeat`, by default): a strip starting off its edge (CuckCrab's 704px) shows its other end. */
    val repeatX: Boolean = true,
    /** `mask-image: radial-gradient(black a%, transparent b%)` as a and b: ARRIVE's combo fading at its edges. */
    val radialMask: Pair<Float, Float>? = null,
    /** What its `::before` and `::after` draw over it: GSN's dolphins, BEARSLEEP's 💤. */
    val overlays: List<Overlay> = emptyList(),
) {
    /** Whether the picture is drawn whole, scaled to the emote's size, and still. */
    val isWholePicture: Boolean get() = sprite == null && motions.isEmpty() && overlays.isEmpty() && rest == Pose.Rest && offsetX == 0 && offsetY == 0 && sheetWidth == width && sheetHeight == height

    /** Whether a keyframe can raise it over its neighbours (`z-index`): only then is its pose worked out to find out. */
    val raisable: Boolean by lazy { motions.any { m -> m.frames.any { it.z != null } } }

    /** What drawing keeps for this emote between frames (its still filter, ready to draw); not part of what it is. */
    @kotlin.concurrent.Volatile
    internal var drawCache: Any? = null

    /** Its still filter's colour stages, worked out once (a flair's tint). */
    internal val restStages: List<FloatArray> by lazy { rest.filter.colorStages() }

    /** When the last finite movement or sprite run ends, after the message; [Long.MAX_VALUE] if one never does. */
    val settlesAfterMs: Long
        get() = maxOf(sprite?.totalMs ?: 0, motions.maxOfOrNull { it.endMs } ?: 0, overlays.maxOfOrNull { o -> o.motions.maxOfOrNull { it.endMs } ?: 0 } ?: 0)
}

/**
 * A `::before` or `::after` with text in it (an emoji, a few characters),
 * placed absolutely in the emote's box and clipped to it as the site's
 * `overflow: hidden` does. Sizes are px; [originX], [originY] are fractions
 * of the text's own box.
 */
data class Overlay(
    val text: String,
    val fontSize: Float,
    /** 0xAARRGGBB; null for the text's own colours (an emoji). */
    val argb: Long?,
    val left: Float?,
    val right: Float?,
    val top: Float,
    /** Its still transform, opacity and filter, under any movement. */
    val rest: Pose,
    val originX: Float,
    val originY: Float,
    val motions: List<Motion>,
    val italic: Boolean = false,
    /** `letter-spacing` in px. */
    val letterSpacing: Float = 0f,
    /** `text-shadow`: offset and blur in px, colour 0xAARRGGBB. */
    val shadow: Filter.Glow? = null,
    /** `line-height` in px; null for about 1.2 times the font size. */
    val lineHeight: Float? = null,
    /** Its box's width, in px or as a fraction of the emote's: text wraps to it, a picture fills it. */
    val widthPx: Float? = null,
    val widthFraction: Float? = null,
    val heightPx: Float? = null,
    /** `mix-blend-mode`: screen (SNAP's flash), color-burn, luminosity (BERN's second picture). */
    val blend: String? = null,
    /** A picture instead of text: its address (a data: one too), or "inherit" for the emote's own. */
    val image: String? = null,
    /** A `mask-image: linear-gradient(angle, black a%, transparent b%)`: angle in degrees, a and b as fractions. */
    val mask: List<Float>? = null,
    /** For a picture: `background-size` width in px (the height keeps its shape), `background-position` in px, and whether it tiles across. */
    val backgroundWidth: Float? = null,
    val backgroundX: Float = 0f,
    val backgroundY: Float = 0f,
    val repeatX: Boolean = false,
    /** Its height as a fraction of the emote's (`height: 100%`). */
    val heightFraction: Float? = null,
    /** In the flow of the emote's box (`display: block`, not absolutely placed): an ::after comes below a ::before. */
    val flow: Boolean = false,
    /** `font-family` asks for a monospace face (HACKERMAN's code). */
    val monospace: Boolean = false,
    /** `box-shadow`s, still: a box drawn with edges and glows rather than text or a picture (HACKERMAN's scan line). */
    val boxShadows: List<BoxShadow>? = null,
)

/** One `box-shadow`: offset and blur in px, colour 0xAARRGGBB, `inset` or around the box. */
data class BoxShadow(val dx: Float, val dy: Float, val blur: Float, val argb: Long, val inset: Boolean)

/** A `box-shadow` list, or null for `none` or what can't be read. */
internal fun readBoxShadows(value: String): List<BoxShadow>? {
    if (value.trim() == "none") return null
    val out = ArrayList<BoxShadow>()
    var depth = 0
    var start = 0
    val parts = ArrayList<String>()
    for ((i, c) in value.withIndex()) {
        when (c) { '(' -> depth++; ')' -> depth--; ',' -> if (depth == 0) { parts += value.substring(start, i); start = i + 1 } }
    }
    parts += value.substring(start)
    for (part in parts) {
        val inset = "inset" in part
        val glow = readFilter("drop-shadow(${part.replace("inset", "").trim()})")?.glows?.firstOrNull() ?: return null
        out += BoxShadow(glow.dx, glow.dy, glow.blur, glow.argb, inset)
    }
    return out
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
    /** `animation-direction`: `reverse` runs it backwards, `alternate` every other run backwards (BONK: both). */
    val reverse: Boolean = false,
    val alternate: Boolean = false,
) {
    /** When it stops, from when the message was sent. */
    val totalMs: Long get() = if (iterations == Int.MAX_VALUE) Long.MAX_VALUE else delayMs + durationMs * iterations

    /** The frame showing [at] ms after the message was sent; null while it isn't playing (before its delay, or done). */
    fun frameAt(at: Long): Int? {
        val t = at - delayMs
        if (t < 0 || at >= totalMs) return null
        val k = ((t % durationMs) * frames / durationMs).toInt()
        val backwards = reverse xor (alternate && (t / durationMs) % 2 == 1L)
        return if (backwards) frames - 1 - k else k
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
     * The emote as the site shows it while pointed at (its `:hover` rules, and
     * those of its ::before and ::after), or null if it has none: the emote
     * card plays it while the emote is held.
     */
    fun hover(word: String): Emote? {
        if (builder == null) return null
        val base = byPrefix[word] ?: return null
        val held = inLine(LineModel.message(emptyList(), null, continued = false, green = false, parts = listOf(word), hovered = true)).firstOrNull()
        return held?.takeIf { it !== base && it != inLine(LineModel.message(emptyList(), null, continued = false, green = false, parts = listOf(word))).firstOrNull() }
    }

    /**
     * The emotes of one line as they look there: the stylesheet changes some
     * by what's next to them, what else the line holds, or a combo's size.
     * [parts] are the line's emotes and links in order (null for a link);
     * the result has the emote to draw for each emote part, in order.
     */
    internal fun inLine(line: LineModel): List<Emote?> {
        val b = builder ?: return line.emotes.map { it.emoteName?.let(byPrefix::get) }
        val extra = b.context.declarations(line)
        val before = b.context.declarations(line, "before")
        val after = b.context.declarations(line, "after")
        return line.emotes.mapIndexed { i, el ->
            val base = el.emoteName?.let(byPrefix::get) ?: return@mapIndexed null
            val added = extra[i]
            if (added.isEmpty() && before[i].isEmpty() && after[i].isEmpty()) base
            else b.variant(base.prefix + "\u0000" + added.joinToString(";") + "\u0000" + before[i].joinToString(";") + "\u0000" + after[i].joinToString(";")) {
                b.variant(base, added, before[i], after[i])
            }
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
        /** Copied on write: lines are also prepared off the main thread (see LinePrefetch). */
        @kotlin.concurrent.Volatile
        var variants: Map<String, Emote> = emptyMap()

        inline fun variant(key: String, make: () -> Emote): Emote =
            variants[key] ?: make().also { variants = variants + (key to it) }

        fun variant(base: Emote, added: List<Pair<String, String>>, before: List<Pair<String, String>> = emptyList(), after: List<Pair<String, String>> = emptyList()): Emote {
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
            // Its ::before and ::after: their own rules, then what the line adds.
            fun part(name: String, more: List<Pair<String, String>>): Decls? {
                val mine = own["${base.prefix}::$name"]
                if (mine == null && more.isEmpty()) return null
                val d = HashMap<String, MutableList<String>>()
                mine?.forEach { (k, v) -> d[k] = v.toMutableList() }
                for ((k, v) in more) d.getOrPut(k) { ArrayList() } += v
                return d
            }
            return build(base.prefix, url, w, h, base.minimumSubTier, decls, keyframes, moving, listOfNotNull(part("before", before), part("after", after)))
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
                map[entry.prefix] = build(entry.prefix, url, image.width, image.height, entry.minimumSubTier, decls, keyframes, moving, overlayRules(rules, entry.prefix))
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
            // Asked again each launch, but conditionally (no-cache): with the
            // client's cache the site answers "not modified" until the list changes.
            val response = client.get(url) {
                header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
                header(HttpHeaders.CacheControl, "no-cache")
            }
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
    overlayDecls: List<Decls> = emptyList(),
): Emote {
    var width = decls.px("width") ?: imageWidth.toDouble()
    var height = decls.px("height") ?: imageHeight.toDouble()
    val size = decls.backgroundSize()
    var sheetWidth = size?.first ?: imageWidth.toDouble()
    var sheetHeight = size?.second ?: if (size != null && imageWidth > 0) size.first * imageHeight / imageWidth else imageHeight.toDouble()
    var offset = decls.backgroundX(width, sheetWidth)
    var offsetY = decls.backgroundY(height, sheetHeight)
    // No emote may be bigger than a line can take: a stylesheet this parser
    // misreads must not draw a picture over chat.
    val shrink = minOf(1.0, MAX_HEIGHT / height, MAX_WIDTH / width)
    width *= shrink; height *= shrink; sheetWidth *= shrink; sheetHeight *= shrink; offset *= shrink; offsetY *= shrink
    val animation = decls.value("animation")?.let { listOf(it) }
    var sprite = animation?.let { spriteOf(it, sheetWidth.toInt(), width.toInt(), keyframes, -decls.backgroundX(width, sheetWidth)) { token -> decls.resolvePx(token) } }
        ?.let { sp -> sp.copy(startX = sp.startX?.let { (it * shrink).toInt() }, stepX = sp.stepX?.let { (it * shrink).toInt() }?.takeIf { it != width.toInt() }) }
    var motions = animation?.let { motionsOf(topLevelParts(it.last()), moving) }.orEmpty()
    // Longhands after the shorthand change every animation in it (`.emote.SOY + .emote.Clap`).
    decls.value("animation-duration")?.let(::cssMillis)?.takeIf { it > 0 }?.let { ms ->
        sprite = sprite?.copy(durationMs = ms); motions = motions.map { it.copy(durationMs = ms) }
    }
    // A strip whose shorthand left its time to a longhand that never came doesn't play.
    if (sprite?.durationMs == 0L) sprite = null
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
        sheetWidth = sheetWidth.toInt().coerceAtLeast(1), sheetHeight = sheetHeight.toInt().coerceAtLeast(1), offsetX = offset.toInt(), offsetY = offsetY.toInt(),
        // A motion whose keyframes this can't read moves nothing; kept, an endless
        // one would redraw the emote every frame for nothing (EatFresh's x5 combo).
        motions = motions.map { m -> m.slidingFrom(offset, offsetY, shrink).withOrigins(width / shrink, height / shrink, originX to originY).withBox(width / shrink, marginLeft.toDouble(), shrink) }
            .filter { m -> m.frames.any { it.transform != null || it.opacity != null || it.filter != null || it.background != null || it.origin != null || it.clip != null || it.width != null || it.marginLeft != null || it.left != null || it.z != null } },
        originX = originX, originY = originY, rest = rest,
        marginLeft = marginLeft, marginRight = marginRight, verticalAlign = verticalAlign,
        radialMask = (decls.value("mask-image") ?: decls.value("-webkit-mask-image"))?.let(::readRadialMask),
        repeatX = decls.value("background-repeat")?.trim()?.let { it == "repeat" || it == "repeat-x" || it.startsWith("repeat ") } ?: (decls.value("background")?.contains("no-repeat") != true),
        // A ::before or ::after inherits the emote's custom properties (NoTears' `--t-NoTears`).
        overlays = overlayDecls.mapNotNull { part ->
            val withVars = HashMap(part).apply { decls.filterKeys { it.startsWith("--") }.forEach { (k, v) -> putIfAbsent(k, v) } }
            overlayOf(withVars, moving, url)
        }.let { parts ->
            // Two blocks in the box's flow stack: the ::after starts below the ::before (ARRIVE's combo copies).
            val first = parts.firstOrNull()
            if (parts.size == 2 && first != null && first.flow && parts[1].flow) {
                parts.take(1) + parts[1].copy(top = parts[1].top + first.top + (first.heightPx ?: (first.fontSize * 1.2f)))
            } else parts
        },
    )
}

/**
 * This motion with its `background-position` keyframes made relative to
 * where the emote's own rules put the picture ([offset] to the left, already
 * shrunk), so the emote is drawn where it rests and slid from there
 * (GIGACHAD arriving from the left). A stepped one is a sprite strip, which
 * [Sprite] plays: its positions are dropped here.
 */
private fun Motion.slidingFrom(offset: Double, offsetY: Double, shrink: Double): Motion {
    if (frames.none { it.background != null }) return this
    return copy(frames = frames.map { f ->
        val (x, y) = f.background ?: return@map f
        val background = if (easing is Easing.Steps) null else ((x * shrink + offset).toFloat() to (y * shrink - offsetY).toFloat())
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
/**
 * Where `background-position` puts the picture in the emote's box, as how far
 * left ([backgroundX], positive) or down ([backgroundY]) it is shifted, in px.
 * Keywords and percentages place it by the space left over, as CSS does:
 * `center` a picture wider than its box (ACKSHUALLY) shows its middle.
 */
private fun Decls.backgroundX(box: Double, sheet: Double): Double {
    val (x, _) = backgroundWords() ?: return 0.0
    return -(position(x, box - sheet) ?: 0.0)
}

private fun Decls.backgroundY(box: Double, sheet: Double): Double {
    val (_, y) = backgroundWords() ?: return 0.0
    return position(y, box - sheet) ?: 0.0
}

/** The x and y words of `background-position`, the y `center` when left out; `top`/`bottom` alone are the y. */
private fun Decls.backgroundWords(): Pair<String, String>? {
    val words = value("background-position")?.trim()?.split(Regex("""\s+""")) ?: return null
    if (words.size == 1 && (words[0] == "top" || words[0] == "bottom")) return "center" to words[0]
    val (x, y) = if (words.size >= 2 && (words[0] == "top" || words[0] == "bottom")) words[1] to words[0] else words[0] to (words.getOrNull(1) ?: "center")
    return x to y
}

/** One background-position word as an offset in px, given the room ([free] = box − picture). */
private fun position(word: String, free: Double): Double? = when {
    word == "0" -> 0.0
    word == "left" || word == "top" -> 0.0
    word == "center" -> free / 2
    word == "right" || word == "bottom" -> free
    word.endsWith("%") -> word.removeSuffix("%").toDoubleOrNull()?.let { it / 100 * free }
    else -> pxRegex.find(word)?.groupValues?.get(1)?.toDouble()
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
    for (match in Regex("""(?:^|(?<=\}))\s*(?:\.text\s*>\s*)?\.emote\.([A-Za-z0-9_-]+)(:hover)?(::?(?:before|after))?\s*\{([^{}]*)\}""").findAll(clean)) {
        // `.emote.GSN:before` and `::before` alike, kept as `GSN::before`; `:hover` ones as `GSN:hover`, `GSN:hover::before`.
        val pseudo = match.groupValues[3].trimStart(':').takeIf { it.isNotEmpty() }?.let { "::$it" }.orEmpty()
        val decls = out.getOrPut(match.groupValues[1] + match.groupValues[2] + pseudo) { HashMap() }
        for (decl in match.groupValues[4].split(';')) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            // Custom properties keep their case: `var(--t-NoTears)` names `--t-NoTears`.
            val name = decl.substring(0, colon).trim().let { if (it.startsWith("--")) it else it.lowercase() }
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
data class StripKeyframes(val positions: List<String>, val start: String?, val end: String? = null)

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
        var end: String? = null
        for (s in step.findAll(match.groupValues[2])) {
            val value = position.find(s.groupValues[2])?.groupValues?.get(1) ?: continue
            positions += value
            val selectors = s.groupValues[1].split(',').map { it.trim() }
            if ("0%" in selectors || "from" in selectors) start = value
            if ("100%" in selectors || "to" in selectors) end = value
        }
        if (positions.isNotEmpty()) out[match.groupValues[1]] = StripKeyframes(positions, start, end)
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
    /** Where the emote's own rules put the strip (`background-position` x): where keyframes that leave an end out go to. */
    ownX: Double,
    resolve: (String) -> Double?,
): Sprite? {
    for (part in topLevelParts(values.last())) {
        val steps = Regex("""steps\((\d+)""").find(part)?.groupValues?.get(1)?.toInt() ?: continue
        val name = part.trim().split(Regex("""\s+""")).firstOrNull { it in keyframes } ?: continue
        val strip = keyframes.getValue(name)
        // Keyframes written with variables only move a strip once this emote fills them in.
        if (strip.positions.none { resolve(it) != null }) continue
        if (frameWidth <= 0 || sheetWidth <= frameWidth || steps <= 0) continue
        val times = timeRegex.findAll(part).map { t -> t.groupValues[1].toDouble().let { if (t.groupValues[2] == "s") it * 1000 else it }.toLong() }.toList()
        // No time: a longhand gives it (WOOF's `animation-duration`).
        val durationMs = times.firstOrNull() ?: 0L
        // What's left once the name, times and steps are taken out is the
        // iteration count and the fill mode.
        val rest = part.replace(Regex("""calc\(.*\)"""), " ").replace(timeRegex, " ")
            .replace(Regex("""steps\([^)]*\)"""), " ").replace(name, " ")
        val iterations = when {
            "infinite" in rest -> Int.MAX_VALUE
            else -> Regex("""(?:^|\s)(\d+)(?:\s|$)""").find(rest)?.groupValues?.get(1)?.toInt() ?: 1
        }
        // From the 0% position to the 100% one, where a keyframe left out is
        // the emote's own (WOOF runs back from -1020px to its own 0), each step
        // moving the strip by the travel over the steps.
        val from = strip.start?.let(resolve) ?: ownX
        val to = strip.end?.let(resolve) ?: ownX.takeIf { it != from } ?: strip.positions.lastOrNull()?.let(resolve) ?: ownX
        val startX = kotlin.math.round(-from).toInt()
        val stepX = ((from - to) / steps).takeIf { it != 0.0 }?.let { kotlin.math.round(it).toInt() }
        val words = rest.split(Regex("""\s+"""))
        return Sprite(
            sheetWidth, steps, durationMs.coerceAtLeast(0), iterations, "forwards" in rest || "both" in rest, startX, times.getOrElse(1) { 0L }, stepX,
            reverse = "reverse" in words || "alternate-reverse" in words, alternate = "alternate" in words || "alternate-reverse" in words,
        )
    }
    return null
}

/** `radial-gradient(black a%, transparent b%)` as fractions; null for any other mask. */
private fun readRadialMask(value: String): Pair<Float, Float>? =
    Regex("""radial-gradient\(\s*black\s+([\d.]+)%\s*,\s*transparent\s+([\d.]+)%\s*\)""").find(value)?.groupValues?.let { it[1].toFloat() / 100f to it[2].toFloat() / 100f }

/** The `::before` and `::after` rules of an emote, in that order, as far as there are any. */
private fun overlayRules(rules: Map<String, Decls>, prefix: String): List<Decls> =
    listOfNotNull(rules["$prefix::before"], rules["$prefix::after"])

/** A pseudo-element with text to draw, or null (an empty `content`, a picture, nothing). */
private fun overlayOf(decls: Decls, moving: Map<String, List<KeyFrame>>, emoteUrl: String): Overlay? {
    val content = decls.value("content")?.trim() ?: return null
    if (decls.value("display")?.trim() == "none") return null
    val text = Regex("""^["'](.*)["']$""").find(content)?.groupValues?.get(1) ?: return null
    // A picture: `background(-image): url(...)` or `inherit`, the emote's own.
    val background = (decls.value("background-image") ?: decls.value("background"))?.trim()
    val image = when {
        background == null -> null
        background.startsWith("inherit") -> "inherit"
        else -> Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""").find(background)?.groupValues?.get(1)?.let { url ->
            if (url.startsWith("http") || url.startsWith("data:")) url else emoteUrl.substringBeforeLast('/') + "/" + url
        }
    }
    val shadows = decls.value("box-shadow")?.let(::readBoxShadows)
    val animated = decls.value("animation")?.let { a -> topLevelParts(a).any { part -> part.trim().split(Regex("""\s+""")).any { w -> moving[w]?.any { it.boxShadows != null } == true } } } == true
    if (text.isBlank() && image == null && shadows == null && !animated) return null
    fun len(name: String) = decls.value(name)?.trim()?.let { if (it == "0") 0.0 else pxRegex.find(it)?.groupValues?.get(1)?.toDouble() }?.toFloat()
    val fontSize = len("font-size") ?: 13f
    val argb = decls.value("color")?.let { readFilter("drop-shadow(0 0 0 $it)")?.glows?.firstOrNull()?.argb }
    var rest = decls.value("transform")?.let(::readTransform) ?: Pose.Rest
    decls.value("opacity")?.trim()?.toFloatOrNull()?.let { rest = rest.copy(opacity = it) }
    decls.value("filter")?.let(::readFilter)?.takeIf { !it.isNone }?.let { rest = rest.copy(filter = it) }
    val box = fontSize * 1.2
    val (originX, originY) = readOrigin(decls.value("transform-origin"), (len("width") ?: (fontSize * 1.2f)).toDouble(), box)
    // Absolutely placed by top and left, or (`display: block`) where its margins put it.
    val top = len("top") ?: len("margin-top") ?: 0f
    val left = len("left") ?: len("margin-left")
    var motions = decls.value("animation")?.let { motionsOf(topLevelParts(it), moving) }.orEmpty()
    decls.value("animation-delay")?.let(::cssMillis)?.let { ms -> motions = motions.map { it.copy(delayMs = ms) } }
    // Its `top` and `left` keyframes as changes from where it stands.
    motions = motions.map { m ->
        m.copy(restOrigin = originX to originY, restRight = len("right") ?: Float.NaN, frames = m.frames.map { f ->
            f.copy(top = f.top?.let { it - top }, left = f.left?.let { it - (left ?: 0f) }, origin = f.originText?.let { readOrigin(it, box, box) })
        })
    }
    val shadow = decls.value("text-shadow")?.takeIf { it.trim() != "none" }?.let { readFilter("drop-shadow(${it.substringBefore(',')})")?.glows?.firstOrNull() }
    val width = decls.value("width")?.trim()
    val mask = (decls.value("mask-image") ?: decls.value("-webkit-mask-image"))?.let { m ->
        Regex("""linear-gradient\(\s*(-?[\d.]+)deg\s*,\s*black\s+([\d.]+)%\s*,\s*transparent\s+([\d.]+)%\s*\)""").find(m)?.groupValues?.let {
            listOf(it[1].toFloat(), it[2].toFloat() / 100f, it[3].toFloat() / 100f)
        }
    }
    return Overlay(
        text, fontSize, argb, left, len("right"), top, rest, originX, originY, motions,
        italic = decls.value("font-style")?.trim() == "italic", letterSpacing = len("letter-spacing") ?: 0f, shadow = shadow,
        lineHeight = len("line-height"),
        widthPx = width?.let { if (it == "0") 0f else pxRegex.find(it)?.groupValues?.get(1)?.toFloat() },
        widthFraction = width?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.div(100f),
        heightPx = len("height"),
        blend = decls.value("mix-blend-mode")?.trim()?.takeIf { it != "normal" },
        image = image,
        mask = mask,
        backgroundWidth = decls.value("background-size")?.trim()?.split(Regex("""\s+"""))?.firstOrNull()?.let { pxRegex.find(it)?.groupValues?.get(1)?.toFloat() },
        backgroundX = decls.value("background-position")?.trim()?.split(Regex("""\s+"""))?.getOrNull(0)?.let { if (it == "0") 0f else pxRegex.find(it)?.groupValues?.get(1)?.toFloat() } ?: 0f,
        backgroundY = decls.value("background-position")?.trim()?.split(Regex("""\s+"""))?.getOrNull(1)?.let { if (it == "0") 0f else pxRegex.find(it)?.groupValues?.get(1)?.toFloat() } ?: 0f,
        repeatX = decls.value("background-repeat")?.trim()?.let { it == "repeat-x" || it == "repeat" } ?: false,
        heightFraction = decls.value("height")?.trim()?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.div(100f),
        flow = decls.value("position")?.trim() != "absolute" && decls.value("top") == null && decls.value("left") == null && decls.value("right") == null,
        monospace = decls.value("font-family")?.contains("mono", ignoreCase = true) == true || decls.value("font-family")?.contains("Courier", ignoreCase = true) == true,
        boxShadows = shadows ?: if (animated) emptyList() else null,
    )
}
