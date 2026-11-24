package io.github.strawwaffle.glorp.site

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * How an emote is moved, turned, scaled, faded or coloured at one moment:
 * what the stylesheet's `transform`, `opacity` and `filter` keyframes come to, and how far its picture is slid inside its box
 * ([bx], [by]: `background-position` keyframes, from where the emote's own
 * rules put it). Distances are CSS pixels. [ox], [oy] are a
 * `transform-origin` keyframe's pivot as fractions of the emote's size (NaN:
 * the emote's own), and [clip] a `clip-path: polygon()` as x, y fractions.
 */
data class Pose(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val rotate: Float = 0f,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val opacity: Float = 1f,
    val filter: Filter = Filter.None,
    val bx: Float = 0f,
    val by: Float = 0f,
    val ox: Float = Float.NaN,
    val oy: Float = Float.NaN,
    val clip: List<Float>? = null,
    /** How much wider than its own box it's drawn, and how far its margin moves it, in px (PepoPunch's punch). */
    val dw: Float = 0f,
    val dml: Float = 0f,
    /** For an [Overlay]: how far its `top` and `left` keyframes move it, in px. */
    val dtop: Float = 0f,
    val dleft: Float = 0f,
    /** For an [Overlay] whose keyframes change its text (HACKERMAN typing): the text, and its size, line height, width (fraction of the emote's) and spacing in px. */
    val text: String? = null,
    val fontPx: Float = Float.NaN,
    val lineHeightPx: Float = Float.NaN,
    val widthFraction: Float = Float.NaN,
    val letterPx: Float = Float.NaN,
    /** For an [Overlay] placed by `right` keyframes (NoTears' banana flying back): its `right` in px. */
    val rightPx: Float = Float.NaN,
) {
    companion object {
        val Rest = Pose()
    }
}

/** One keyframe: where in the cycle (0 to 1) and what it sets; null for what it leaves alone. */
data class KeyFrame(
    val at: Float,
    val transform: Pose?,
    val opacity: Float?,
    val filter: Filter?,
    /** `background-position` in CSS pixels (x, y), as written; once in an emote's [Motion], relative to the emote's own. */
    val background: Pair<Float, Float>? = null,
    /** `transform-origin` as written; an emote's [Motion] has it as [origin], fractions of its size. */
    val originText: String? = null,
    val origin: Pair<Float, Float>? = null,
    /** `clip-path: polygon()` as x, y fractions of the emote's box. */
    val clip: List<Float>? = null,
    /** `animation-timing-function` set in this keyframe: how the stretch from here to the next one eases. */
    val easing: Easing? = null,
    /** An overlay's `content`, `font-size`, `line-height`, `width` (as a fraction) and `letter-spacing`, which change in steps. */
    val content: String? = null,
    val fontSize: Float? = null,
    val lineHeight: Float? = null,
    val widthFraction: Float? = null,
    val letterSpacing: Float? = null,
    /** An overlay's `right` in px, as written. */
    val right: Float? = null,
    /** `width` and `margin-left` in px as written; in an emote's [Motion], the change from its own. */
    val width: Float? = null,
    val marginLeft: Float? = null,
    /** `top` and `left` in px as written (an overlay falling); in an [Overlay]'s motion, the change from where it stands. */
    val top: Float? = null,
    val left: Float? = null,
)

/** A timing function, as CSS has them. */
sealed interface Easing {
    fun of(x: Float): Float

    data class Bezier(val x1: Float, val y1: Float, val x2: Float, val y2: Float) : Easing {
        override fun of(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            // Solve for the curve parameter whose x is [x], then read its y.
            var lo = 0f
            var hi = 1f
            var t = x
            repeat(20) {
                val cx = curve(t, x1, x2)
                if (abs(cx - x) < 1e-4f) return curve(t, y1, y2)
                if (cx < x) lo = t else hi = t
                t = (lo + hi) / 2
            }
            return curve(t, y1, y2)
        }

        private fun curve(t: Float, a: Float, b: Float): Float {
            val u = 1 - t
            return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
        }
    }

    data class Steps(val count: Int, val start: Boolean) : Easing {
        override fun of(x: Float): Float {
            val step = floor(x * count) + if (start) 1 else 0
            return (step / count).coerceIn(0f, 1f)
        }
    }

    data object Linear : Easing {
        override fun of(x: Float) = x
    }

    companion object {
        val Ease = Bezier(0.25f, 0.1f, 0.25f, 1f)
        val EaseIn = Bezier(0.42f, 0f, 1f, 1f)
        val EaseOut = Bezier(0f, 0f, 0.58f, 1f)
        val EaseInOut = Bezier(0.42f, 0f, 0.58f, 1f)
    }
}

/** One running `animation` on an emote: its keyframes and timing. */
data class Motion(
    val frames: List<KeyFrame>,
    val durationMs: Long,
    val delayMs: Long,
    /** [Int.MAX_VALUE] for `infinite`. */
    val iterations: Int,
    val easing: Easing,
    val alternate: Boolean,
    val reverse: Boolean,
    /** `forwards`: keeps the last keyframe's pose after it ends. */
    val holdLast: Boolean,
    /** The emote's own pivot, where a keyframe sets `transform-origin` and the next doesn't. */
    val restOrigin: Pair<Float, Float> = 0.5f to 0.5f,
    /** An overlay's own `right`, where its keyframes leave it out; NaN where it has none. */
    val restRight: Float = Float.NaN,
) {
    val endMs: Long get() = if (iterations == Int.MAX_VALUE) Long.MAX_VALUE else delayMs + durationMs * iterations

    /** Applies this motion at [elapsedMs] after the message was sent onto [pose]; the later motion wins a property. */
    fun apply(elapsedMs: Long, pose: Pose): Pose {
        val t = elapsedMs - delayMs
        if (t < 0) return pose
        val progress: Float = if (elapsedMs >= endMs) {
            if (!holdLast) return pose
            val lastIteration = iterations - 1
            directed(1f, lastIteration)
        } else {
            val iteration = (t / durationMs).toInt()
            directed((t % durationMs).toFloat() / durationMs, iteration)
        }
        return sample(progress, pose)
    }

    private fun directed(p: Float, iteration: Int): Float {
        var x = if (reverse) 1 - p else p
        if (alternate && iteration % 2 == 1) x = 1 - x
        return x
    }

    private fun sample(p: Float, base: Pose): Pose {
        var pose = base
        // Where a keyframe leaves the transform out, the emote's still one stands in.
        interpolate(p, { it.transform }, base.copy(opacity = 1f, filter = Filter.None)) { a, b, f ->
            Pose(lerp(a.tx, b.tx, f), lerp(a.ty, b.ty, f), lerp(a.rotate, b.rotate, f), lerp(a.sx, b.sx, f), lerp(a.sy, b.sy, f))
        }?.let { pose = pose.copy(tx = it.tx, ty = it.ty, rotate = it.rotate, sx = it.sx, sy = it.sy) }
        interpolate(p, { it.opacity }, 1f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(opacity = it) }
        interpolate(p, { it.filter }, base.filter) { a, b, f -> a.mix(b, f) }?.let { pose = pose.copy(filter = it) }
        interpolate(p, { it.background }, 0f to 0f) { a, b, f -> lerp(a.first, b.first, f) to lerp(a.second, b.second, f) }
            ?.let { pose = pose.copy(bx = it.first, by = it.second) }
        interpolate(p, { it.origin }, restOrigin) { a, b, f -> lerp(a.first, b.first, f) to lerp(a.second, b.second, f) }
            ?.let { pose = pose.copy(ox = it.first, oy = it.second) }
        if (frames.any { it.clip != null }) pose = pose.copy(clip = clipAt(p))
        interpolate(p, { it.width }, 0f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(dw = it) }
        interpolate(p, { it.marginLeft }, 0f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(dml = it) }
        interpolate(p, { it.top }, 0f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(dtop = it) }
        interpolate(p, { it.left }, 0f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(dleft = it) }
        frames.firstOrNull { it.right != null }?.right?.let { first ->
            interpolate(p, { it.right }, if (restRight.isNaN()) first else restRight) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(rightPx = it) }
        }
        if (frames.any { it.content != null || it.fontSize != null || it.lineHeight != null || it.widthFraction != null || it.letterSpacing != null }) {
            stepAt(p) { it.content }?.let { pose = pose.copy(text = it) }
            stepAt(p) { it.fontSize }?.let { pose = pose.copy(fontPx = it) }
            stepAt(p) { it.lineHeight }?.let { pose = pose.copy(lineHeightPx = it) }
            stepAt(p) { it.widthFraction }?.let { pose = pose.copy(widthFraction = it) }
            stepAt(p) { it.letterSpacing }?.let { pose = pose.copy(letterPx = it) }
        }
        return pose
    }

    /** The value the last keyframe at or before [p] sets, for what changes in steps (an overlay's text). */
    private inline fun <T> stepAt(p: Float, pick: (KeyFrame) -> T?): T? {
        var out: T? = null
        for (frame in frames) {
            if (frame.at > p) break
            pick(frame)?.let { out = it }
        }
        return out
    }

    /**
     * The clip at [p]. Between two polygons of as many points, each point
     * moves; to or from none (where a keyframe leaves it out), it switches
     * halfway, as CSS does with what it can't blend.
     */
    private fun clipAt(p: Float): List<Float>? {
        var before: Pair<Float, List<Float>>? = null
        var after: Pair<Float, List<Float>>? = null
        for (frame in frames) {
            val value = frame.clip ?: continue
            if (frame.at <= p) before = frame.at to value
            if (frame.at >= p && after == null) after = frame.at to value
        }
        val fromAt = before?.first ?: 0f
        val toAt = after?.first ?: 1f
        val from = before?.second
        val to = after?.second
        if (toAt <= fromAt) return from ?: to
        val f = easing.of((p - fromAt) / (toAt - fromAt))
        if (from == null || to == null || from.size != to.size) return if (f < 0.5f) from else to
        return from.indices.map { lerp(from[it], to[it], f) }
    }

    /**
     * The value of one property at [p], between the keyframes that set it.
     * Where the first or last keyframe doesn't, the emote's own value
     * ([rest]) stands in, as in CSS. Null when no keyframe touches it.
     */
    private inline fun <T> interpolate(p: Float, pick: (KeyFrame) -> T?, rest: T, mix: (T, T, Float) -> T): T? {
        var before: Pair<Float, T>? = null
        var beforeFrame: KeyFrame? = null
        var after: Pair<Float, T>? = null
        var any = false
        for (frame in frames) {
            val value = pick(frame) ?: continue
            any = true
            if (frame.at <= p) { before = frame.at to value; beforeFrame = frame }
            if (frame.at >= p && after == null) after = frame.at to value
        }
        if (!any) return null
        val from = before ?: (0f to rest)
        val to = after ?: (1f to rest)
        if (to.first <= from.first) return from.second
        val local = (p - from.first) / (to.first - from.first)
        // A keyframe's own timing function eases the stretch after it, as in CSS.
        return mix(from.second, to.second, (beforeFrame?.easing ?: easing).of(local))
    }
}

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** Every `@keyframes` that moves, turns, scales, fades or brightens, by name. */
internal fun motionKeyframes(css: String): Map<String, List<KeyFrame>> {
    val out = HashMap<String, List<KeyFrame>>()
    val block = Regex("""@keyframes\s+([\w-]+)\s*\{((?:[^{}]*\{[^{}]*\})*)\s*\}""")
    val step = Regex("""([^{}]+)\{([^{}]*)\}""")
    for (match in block.findAll(css)) {
        val frames = ArrayList<KeyFrame>()
        for (s in step.findAll(match.groupValues[2])) {
            val decls = declarations(s.groupValues[2])
            val transform = decls["transform"]?.let(::readTransform)
            val opacity = decls["opacity"]?.trim()?.let(::number)
            val filter = (decls["filter"] ?: decls["-webkit-filter"])?.let(::readFilter)
            val background = decls["background-position"]?.let(::readBackgroundPosition)
            val origin = decls["transform-origin"]
            val clip = decls["clip-path"]?.let(::readPolygon)
            fun px(name: String) = decls[name]?.trim()?.let { if (it == "0") 0f else it.takeIf { v -> v.endsWith("px") }?.removeSuffix("px")?.toFloatOrNull() }
            val width = px("width")
            val marginLeft = px("margin-left")
            val top = px("top")
            val left = px("left")
            val stepEasing = decls["animation-timing-function"]?.let(::readEasing)
            val content = decls["content"]?.trim()?.let { Regex("""^["'](.*)["']$""").find(it)?.groupValues?.get(1) }
            val fontSize = px("font-size")
            val lineHeight = px("line-height")
            val widthFraction = decls["width"]?.trim()?.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.div(100f)
            val letterSpacing = px("letter-spacing")
            val right = px("right")
            if (right == null && transform == null && opacity == null && filter == null && background == null && origin == null && clip == null && width == null &&
                marginLeft == null && top == null && left == null && content == null && fontSize == null && lineHeight == null && widthFraction == null && letterSpacing == null
            ) continue
            for (at in s.groupValues[1].split(',')) {
                val offset = when (val word = at.trim().lowercase()) {
                    "from" -> 0f
                    "to" -> 1f
                    else -> word.removeSuffix("%").toFloatOrNull()?.div(100f)
                } ?: continue
                frames += KeyFrame(offset, transform, opacity, filter, background, origin, clip = clip, width = width, marginLeft = marginLeft, top = top, left = left, easing = stepEasing,
                    content = content, fontSize = fontSize, lineHeight = lineHeight, widthFraction = widthFraction, letterSpacing = letterSpacing, right = right,
                )
            }
        }
        if (frames.isNotEmpty()) out[match.groupValues[1]] = frames.sortedBy { it.at }
    }
    return out
}

/**
 * A `background-position` in pixels; a lone value leaves the other centred,
 * which for these pictures (as tall as their box) is 0. Null for keywords
 * and percentages, which would need the picture's size.
 */
internal fun readBackgroundPosition(value: String): Pair<Float, Float>? {
    val parts = value.trim().split(Regex("""\s+"""))
    if (parts.isEmpty() || parts.size > 2) return null
    fun px(word: String): Float? = if (word == "0") 0f else word.takeIf { it.endsWith("px") }?.removeSuffix("px")?.toFloatOrNull()
    val x = px(parts[0]) ?: return null
    val y = if (parts.size == 2) px(parts[1]) ?: return null else 0f
    return x to y
}

/** `polygon(x% y%, …)` as x, y fractions; null for anything else (px, a shape, `none`). */
internal fun readPolygon(value: String): List<Float>? {
    val inside = Regex("""^polygon\((.*)\)$""").find(value.trim())?.groupValues?.get(1) ?: return null
    val out = ArrayList<Float>()
    for (point in inside.split(',')) {
        val xy = point.trim().split(Regex("""\s+"""))
        if (xy.size != 2) return null
        for (v in xy) out += (if (v == "0") 0f else v.takeIf { it.endsWith("%") }?.removeSuffix("%")?.toFloatOrNull()?.div(100f)) ?: return null
    }
    return out.takeIf { it.size >= 6 }
}

/** One timing function: `ease-in`, `cubic-bezier(…)`, `steps(4, end)`. */
internal fun readEasing(value: String): Easing? {
    val v = value.trim().lowercase()
    val fn = Regex("""^(cubic-bezier|steps)\((.*)\)$""").find(v)
    if (fn != null) {
        val args = fn.groupValues[2].split(',').map { it.trim() }
        return if (fn.groupValues[1] == "steps") Easing.Steps(args[0].toIntOrNull() ?: 1, args.getOrNull(1)?.let { it == "start" || it == "jump-start" } == true)
        else args.mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.let { Easing.Bezier(it[0], it[1], it[2], it[3]) }
    }
    return when (v) {
        "linear" -> Easing.Linear
        "ease" -> Easing.Ease
        "ease-in" -> Easing.EaseIn
        "ease-out" -> Easing.EaseOut
        "ease-in-out" -> Easing.EaseInOut
        "step-start" -> Easing.Steps(1, true)
        "step-end" -> Easing.Steps(1, false)
        else -> null
    }
}

private fun declarations(body: String): Map<String, String> {
    val out = HashMap<String, String>()
    for (decl in body.split(';')) {
        val colon = decl.indexOf(':')
        if (colon > 0) out[decl.substring(0, colon).trim().let { if (it.startsWith("--")) it else it.lowercase() }] = decl.substring(colon + 1).trim()
    }
    return out
}

private val function = Regex("""([a-zA-Z0-9]+)\(([^()]*)\)""")

/** A `transform` value as one pose; null when it uses something not drawn here (a variable, a skew). */
internal fun readTransform(written: String): Pose? {
    // Two numbers multiplied or divided, as in mhm's `scale(calc(28/36))`, are worked out; anything else can't be.
    val value = simpleCalc.replace(written) { m ->
        val a = m.groupValues[1].toDouble(); val b = m.groupValues[3].toDouble()
        (if (m.groupValues[2] == "*") a * b else if (b != 0.0) a / b else 1.0).toString()
    }
    if ("var(" in value || "calc(" in value) return null
    if (value.trim() == "none") return Pose.Rest
    var pose = Pose.Rest
    for (f in function.findAll(value)) {
        val args = f.groupValues[2].split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        fun px(i: Int) = args.getOrNull(i)?.let(::length) ?: 0f
        // A translation after a scale or a turn moves in the scaled, turned
        // directions, as CSS applies the functions in order: `scale(-1,1)
        // translate(-2px,3px)` moves 2 px right.
        fun moved(x: Float, y: Float): Pose {
            val sx = x * pose.sx; val sy = y * pose.sy
            val r = pose.rotate * (kotlin.math.PI.toFloat() / 180f)
            val c = kotlin.math.cos(r); val s = kotlin.math.sin(r)
            return pose.copy(tx = pose.tx + sx * c - sy * s, ty = pose.ty + sx * s + sy * c)
        }
        pose = when (f.groupValues[1].lowercase()) {
            "translate" -> moved(px(0), px(1))
            "translate3d" -> moved(px(0), px(1))
            "translatex" -> moved(px(0), 0f)
            "translatey" -> moved(0f, px(0))
            "rotate", "rotatez" -> pose.copy(rotate = pose.rotate + (args.firstOrNull()?.let(::angle) ?: 0f))
            // A turn about the z axis only, written in 3D (PARDNER's spinning gun).
            "rotate3d" -> if (args.size == 4 && number(args[0]) == 0f && number(args[1]) == 0f) pose.copy(rotate = pose.rotate + (angle(args[3]) ?: 0f) * (if ((number(args[2]) ?: 1f) < 0) -1 else 1)) else return null
            "scale" -> {
                val x = args.getOrNull(0)?.let(::number) ?: 1f
                val y = args.getOrNull(1)?.let(::number) ?: x
                pose.copy(sx = pose.sx * x, sy = pose.sy * y)
            }
            "scalex" -> pose.copy(sx = pose.sx * (args.firstOrNull()?.let(::number) ?: 1f))
            "scaley" -> pose.copy(sy = pose.sy * (args.firstOrNull()?.let(::number) ?: 1f))
            "scale3d" -> pose.copy(sx = pose.sx * (args.getOrNull(0)?.let(::number) ?: 1f), sy = pose.sy * (args.getOrNull(1)?.let(::number) ?: 1f))
            else -> return null
        }
    }
    return pose
}

private val simpleCalc = Regex("""calc\(\s*(-?\d*\.?\d+)\s*([*/])\s*(-?\d*\.?\d+)\s*\)""")

private fun number(text: String): Float? = text.trim().removeSuffix("%").toFloatOrNull()?.let { if (text.trim().endsWith("%")) it / 100f else it }

private fun length(text: String): Float? = when {
    text.endsWith("px") -> text.removeSuffix("px").toFloatOrNull()
    text.endsWith("em") -> text.removeSuffix("em").toFloatOrNull()?.times(16)
    else -> text.toFloatOrNull()
}

private fun angle(text: String): Float? = when {
    text.endsWith("deg") -> text.removeSuffix("deg").toFloatOrNull()
    text.endsWith("turn") -> text.removeSuffix("turn").toFloatOrNull()?.times(360)
    text.endsWith("rad") -> text.removeSuffix("rad").toFloatOrNull()?.times(180 / PI.toFloat())
    text.endsWith("grad") -> text.removeSuffix("grad").toFloatOrNull()?.times(0.9f)
    else -> text.toFloatOrNull()
}

private val timeWord = Regex("""^(-?\d*\.?\d+)(ms|s)$""")

/**
 * Reads the parts of an `animation` shorthand that name moving keyframes,
 * such as `BLICKY-rotate ease-out 1.5s 1 0.15s`. Parts that step a sprite
 * strip are left to the sprite reader.
 */
internal fun motionsOf(parts: List<String>, keyframes: Map<String, List<KeyFrame>>): List<Motion> = parts.mapNotNull { part ->
    var text = part.trim()
    // `calc(3.5s / var(--animation-speed, 1))` and the like: the time before the division.
    text = Regex("""calc\(\s*(\d*\.?\d+m?s)[^)]*\)+""").replace(text) { it.groupValues[1] }
    val easingFn = Regex("""(cubic-bezier|steps)\(([^)]*)\)""").find(text)
    var easing: Easing = Easing.Ease
    if (easingFn != null) {
        val args = easingFn.groupValues[2].split(',').map { it.trim() }
        easing = if (easingFn.groupValues[1] == "steps") {
            Easing.Steps(args[0].toIntOrNull() ?: 1, args.getOrNull(1)?.let { it == "start" || it == "jump-start" } == true)
        } else {
            val n = args.mapNotNull { it.toFloatOrNull() }
            if (n.size == 4) Easing.Bezier(n[0], n[1], n[2], n[3]) else Easing.Ease
        }
        text = text.replace(easingFn.value, " ")
    }
    var name: String? = null
    val times = ArrayList<Long>()
    var iterations = 1
    var alternate = false
    var reverse = false
    var hold = false
    for (word in text.split(Regex("""\s+""")).filter { it.isNotEmpty() }) {
        val time = timeWord.find(word)
        when {
            time != null -> times += time.groupValues[1].toDouble().let { if (time.groupValues[2] == "s") it * 1000 else it }.toLong()
            word == "infinite" -> iterations = Int.MAX_VALUE
            word.toIntOrNull() != null -> iterations = word.toInt()
            word.toDoubleOrNull() != null -> iterations = word.toDouble().toInt().coerceAtLeast(1)
            word == "linear" -> easing = Easing.Linear
            word == "ease" -> easing = Easing.Ease
            word == "ease-in" -> easing = Easing.EaseIn
            word == "ease-out" -> easing = Easing.EaseOut
            word == "ease-in-out" -> easing = Easing.EaseInOut
            word == "step-start" -> easing = Easing.Steps(1, true)
            word == "step-end" -> easing = Easing.Steps(1, false)
            word == "alternate" -> alternate = true
            word == "reverse" -> reverse = true
            word == "alternate-reverse" -> { alternate = true; reverse = true }
            word == "forwards" || word == "both" -> hold = true
            word in keyframes -> name = word
        }
    }
    val frames = name?.let(keyframes::get) ?: return@mapNotNull null
    val duration = times.getOrNull(0)?.takeIf { it > 0 } ?: return@mapNotNull null
    // A negative delay starts partway through, as in CSS.
    Motion(frames, duration, times.getOrNull(1) ?: 0, iterations.coerceAtLeast(1), easing, alternate, reverse, hold)
}

/**
 * `transform-origin` as fractions of the emote's width and height; the centre
 * by default. Lengths (BLADE's `-36px 32px`, or a bare `0`) need its size in px.
 */
internal fun readOrigin(value: String?, width: Double = 0.0, height: Double = 0.0): Pair<Float, Float> {
    if (value == null) return 0.5f to 0.5f
    val words = value.trim().split(Regex("""\s+"""))
    fun of(word: String, horizontal: Boolean): Float? = when (word) {
        "left" -> if (horizontal) 0f else null
        "right" -> if (horizontal) 1f else null
        "top" -> if (!horizontal) 0f else null
        "bottom" -> if (!horizontal) 1f else null
        "center" -> 0.5f
        "0" -> 0f
        else -> when {
            word.endsWith("%") -> word.removeSuffix("%").toFloatOrNull()?.div(100f)
            word.endsWith("px") -> word.removeSuffix("px").toDoubleOrNull()
                ?.let { px -> (if (horizontal) width else height).takeIf { it > 0 }?.let { (px / it).toFloat() } }
            else -> null
        }
    }
    var x = 0.5f
    var y = 0.5f
    when (words.size) {
        1 -> when (words[0]) {
            "top", "bottom" -> y = of(words[0], false) ?: 0.5f
            else -> x = of(words[0], true) ?: 0.5f
        }
        else -> {
            // `bottom left` names the vertical first.
            val (h, v) = if (words[0] == "top" || words[0] == "bottom") words[1] to words[0] else words[0] to words[1]
            x = of(h, true) ?: 0.5f
            y = of(v, false) ?: 0.5f
        }
    }
    return x to y
}
