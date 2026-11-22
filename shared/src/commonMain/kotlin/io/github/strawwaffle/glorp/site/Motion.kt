package io.github.strawwaffle.glorp.site

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor

/**
 * How an emote is moved, turned, scaled, faded or brightened at one moment:
 * what the stylesheet's `transform`, `opacity` and `filter: brightness()`
 * keyframes come to. Distances are CSS pixels.
 */
data class Pose(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val rotate: Float = 0f,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val opacity: Float = 1f,
    val brightness: Float = 1f,
) {
    companion object {
        val Rest = Pose()
    }
}

/** One keyframe: where in the cycle (0 to 1) and what it sets; null for what it leaves alone. */
class KeyFrame(val at: Float, val transform: Pose?, val opacity: Float?, val brightness: Float?)

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
        interpolate(p, { it.transform }, base.copy(opacity = 1f, brightness = 1f)) { a, b, f ->
            Pose(lerp(a.tx, b.tx, f), lerp(a.ty, b.ty, f), lerp(a.rotate, b.rotate, f), lerp(a.sx, b.sx, f), lerp(a.sy, b.sy, f))
        }?.let { pose = pose.copy(tx = it.tx, ty = it.ty, rotate = it.rotate, sx = it.sx, sy = it.sy) }
        interpolate(p, { it.opacity }, 1f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(opacity = it) }
        interpolate(p, { it.brightness }, 1f) { a, b, f -> lerp(a, b, f) }?.let { pose = pose.copy(brightness = it) }
        return pose
    }

    /**
     * The value of one property at [p], between the keyframes that set it.
     * Where the first or last keyframe doesn't, the emote's own value
     * ([rest]) stands in, as in CSS. Null when no keyframe touches it.
     */
    private inline fun <T> interpolate(p: Float, pick: (KeyFrame) -> T?, rest: T, mix: (T, T, Float) -> T): T? {
        var before: Pair<Float, T>? = null
        var after: Pair<Float, T>? = null
        var any = false
        for (frame in frames) {
            val value = pick(frame) ?: continue
            any = true
            if (frame.at <= p) before = frame.at to value
            if (frame.at >= p && after == null) after = frame.at to value
        }
        if (!any) return null
        val from = before ?: (0f to rest)
        val to = after ?: (1f to rest)
        if (to.first <= from.first) return from.second
        val local = (p - from.first) / (to.first - from.first)
        return mix(from.second, to.second, easing.of(local))
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
            val brightness = decls["filter"]?.let(::readBrightness)
            if (transform == null && opacity == null && brightness == null) continue
            for (at in s.groupValues[1].split(',')) {
                val offset = when (val word = at.trim().lowercase()) {
                    "from" -> 0f
                    "to" -> 1f
                    else -> word.removeSuffix("%").toFloatOrNull()?.div(100f)
                } ?: continue
                frames += KeyFrame(offset, transform, opacity, brightness)
            }
        }
        if (frames.isNotEmpty()) out[match.groupValues[1]] = frames.sortedBy { it.at }
    }
    return out
}

private fun declarations(body: String): Map<String, String> {
    val out = HashMap<String, String>()
    for (decl in body.split(';')) {
        val colon = decl.indexOf(':')
        if (colon > 0) out[decl.substring(0, colon).trim().lowercase()] = decl.substring(colon + 1).trim()
    }
    return out
}

private val function = Regex("""([a-zA-Z0-9]+)\(([^()]*)\)""")

/** A `transform` value as one pose; null when it uses something not drawn here (a variable, a skew). */
internal fun readTransform(value: String): Pose? {
    if ("var(" in value || "calc(" in value) return null
    if (value.trim() == "none") return Pose.Rest
    var pose = Pose.Rest
    for (f in function.findAll(value)) {
        val args = f.groupValues[2].split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        fun px(i: Int) = args.getOrNull(i)?.let(::length) ?: 0f
        pose = when (f.groupValues[1].lowercase()) {
            "translate" -> pose.copy(tx = pose.tx + px(0), ty = pose.ty + px(1))
            "translate3d" -> pose.copy(tx = pose.tx + px(0), ty = pose.ty + px(1))
            "translatex" -> pose.copy(tx = pose.tx + px(0))
            "translatey" -> pose.copy(ty = pose.ty + px(0))
            "rotate", "rotatez" -> pose.copy(rotate = pose.rotate + (args.firstOrNull()?.let(::angle) ?: 0f))
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

/**
 * The `brightness()` in a `filter`. Only when it's the filter's one colour
 * change: brightness alone from a mix of grayscale, sepia and hue turns
 * would wash the emote out rather than tint it. A glow (`drop-shadow`) is
 * left out.
 */
private fun readBrightness(value: String): Float? {
    if ("var(" in value) return null
    val others = function.findAll(value).map { it.groupValues[1].lowercase() }.filter { it != "brightness" && it != "drop-shadow" && it != "rgba" && it != "rgb" }
    if (others.any()) return null
    val arg = Regex("""brightness\(([^)]*)\)""").find(value)?.groupValues?.get(1)?.trim() ?: return null
    return if (arg.endsWith("%")) arg.removeSuffix("%").toFloatOrNull()?.div(100f) else arg.toFloatOrNull()
}

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

/** `transform-origin` as fractions of the emote's width and height; the centre by default. */
internal fun readOrigin(value: String?): Pair<Float, Float> {
    if (value == null) return 0.5f to 0.5f
    val words = value.trim().split(Regex("""\s+"""))
    fun of(word: String, horizontal: Boolean): Float? = when (word) {
        "left" -> if (horizontal) 0f else null
        "right" -> if (horizontal) 1f else null
        "top" -> if (!horizontal) 0f else null
        "bottom" -> if (!horizontal) 1f else null
        "center" -> 0.5f
        else -> if (word.endsWith("%")) word.removeSuffix("%").toFloatOrNull()?.div(100f) else null
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
