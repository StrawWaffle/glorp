package io.github.strawwaffle.glorp.site

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A CSS `filter`: its colour functions in order (brightness, contrast,
 * saturate, grayscale, sepia, invert, opacity, hue-rotate), which come to
 * one colour matrix, and its `drop-shadow`s, drawn as glows.
 */
data class Filter(val functions: List<Fn> = emptyList(), val glows: List<Glow> = emptyList()) {
    /** One function; [amount] is 1 for 100%, degrees for `hue-rotate`. */
    data class Fn(val name: String, val amount: Float)

    /** A `drop-shadow`: offset and blur in CSS px, colour as 0xAARRGGBB. */
    data class Glow(val dx: Float, val dy: Float, val blur: Float, val argb: Long)

    val isNone: Boolean get() = functions.isEmpty() && glows.isEmpty()

    /**
     * The colour functions as one 4×5 matrix (rows r, g, b, a; offsets in
     * 0..255, as Android's ColorMatrix has them), or null when they change
     * nothing.
     */
    fun colorMatrix(): FloatArray? = colorStages().let { if (it.size == 1) it[0] else if (it.isEmpty()) null else it.fold(it[0]) { acc, m -> if (m === it[0]) acc else multiply(m, acc) } }

    /**
     * The colour functions as matrices applied one after another, each
     * clamped to 0..1 before the next, as browsers do: COOMER's
     * `saturate(60)` then `hue-rotate` turns its face pink, where one folded
     * matrix would carry the overflow on and paint it solid red. Functions
     * that can't overflow share a stage.
     */
    fun colorStages(): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var m: FloatArray? = null
        for (fn in functions) {
            val next = matrixOf(fn) ?: continue
            m = if (m == null) next else multiply(next, m)
            if (canOverflow(fn)) { out += m; m = null }
        }
        m?.let { out += it }
        return out
    }

    /**
     * Between this filter and [other] at [f], as CSS blends them: function by
     * function when one list starts like the other (the shorter filled out
     * with ones that change nothing), otherwise switching halfway.
     */
    fun mix(other: Filter, f: Float): Filter {
        val a = functions
        val b = other.functions
        val n = maxOf(a.size, b.size)
        val sameStart = (0 until minOf(a.size, b.size)).all { a[it].name == b[it].name }
        val fns = if (!sameStart) (if (f < 0.5f) a else b)
        else List(n) { i ->
            val from = a.getOrNull(i) ?: identity(b[i].name)
            val to = b.getOrNull(i) ?: identity(a[i].name)
            Fn(from.name, from.amount + (to.amount - from.amount) * f)
        }
        val glows = if (glows.size == other.glows.size) glows.zip(other.glows) { x, y ->
            Glow(x.dx + (y.dx - x.dx) * f, x.dy + (y.dy - x.dy) * f, x.blur + (y.blur - x.blur) * f, mixColor(x.argb, y.argb, f))
        } else if (f < 0.5f) glows else other.glows
        return Filter(fns, glows)
    }

    companion object {
        val None = Filter()

        private fun identity(name: String) = Fn(name, if (name in listOf("brightness", "contrast", "saturate", "opacity")) 1f else 0f)
    }
}

private val known = setOf("brightness", "contrast", "saturate", "grayscale", "sepia", "invert", "opacity", "hue-rotate")

/**
 * A `filter` value, or null when it uses something this can't draw (a
 * variable, a url). `blur()` is left out; the rest is kept.
 */
internal fun readFilter(value: String): Filter? {
    val v = value.trim()
    if (v == "none") return Filter.None
    if ("var(" in v || "url(" in v) return null
    val fns = ArrayList<Filter.Fn>()
    val glows = ArrayList<Filter.Glow>()
    var i = 0
    while (i < v.length) {
        val open = v.indexOf('(', i)
        if (open < 0) break
        val name = v.substring(i, open).trim().lowercase()
        var depth = 1
        var j = open + 1
        while (j < v.length && depth > 0) {
            when (v[j]) { '(' -> depth++; ')' -> depth-- }
            j++
        }
        val arg = v.substring(open + 1, j - 1).trim()
        i = j
        when (name) {
            "drop-shadow" -> readGlow(arg)?.let { glows += it }
            "hue-rotate" -> fns += Filter.Fn(name, angleOf(arg) ?: return null)
            in known -> fns += Filter.Fn(name, amountOf(arg) ?: return null)
            "blur" -> {}
            else -> return null
        }
    }
    return Filter(fns, glows)
}

private fun amountOf(arg: String): Float? = when {
    arg.isEmpty() -> 1f
    arg.endsWith("%") -> arg.removeSuffix("%").toFloatOrNull()?.div(100f)
    else -> arg.toFloatOrNull()
}

private fun angleOf(arg: String): Float? = when {
    arg.isEmpty() || arg == "0" -> 0f
    arg.endsWith("deg") -> arg.removeSuffix("deg").toFloatOrNull()
    arg.endsWith("turn") -> arg.removeSuffix("turn").toFloatOrNull()?.times(360)
    arg.endsWith("rad") -> arg.removeSuffix("rad").toFloatOrNull()?.times(180 / PI.toFloat())
    else -> null
}

/** `0 0 10px #FFFFFF80`, `0 0 0.5px black`, `1px 1px 0 rgba(0,0,0,.5)`: lengths and a colour in either order. */
private fun readGlow(arg: String): Filter.Glow? {
    val colour = Regex("""(rgba?\([^)]*\)|#[0-9a-fA-F]{3,8}|[a-zA-Z]+)""").findAll(arg).lastOrNull()
    val lengths = (if (colour != null) arg.removeRange(colour.range) else arg).trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
        .map { if (it == "0") 0f else it.removeSuffix("px").toFloatOrNull() ?: return null }
    if (lengths.size < 2) return null
    val argb = colour?.value?.let(::colourOf) ?: 0xFF000000
    return Filter.Glow(lengths[0], lengths[1], lengths.getOrElse(2) { 0f }, argb)
}

private fun colourOf(text: String): Long? {
    val t = text.lowercase()
    if (t.startsWith("#")) {
        val h = t.removePrefix("#")
        val full = when (h.length) {
            3 -> h.map { "$it$it" }.joinToString("") + "ff"
            4 -> h.map { "$it$it" }.joinToString("")
            6 -> h + "ff"
            8 -> h
            else -> return null
        }
        val rgba = full.toLongOrNull(16) ?: return null
        return ((rgba and 0xFF) shl 24) or (rgba ushr 8)
    }
    if (t.startsWith("rgb")) {
        val n = t.substringAfter('(').substringBefore(')').split(',', ' ', '/').filter { it.isNotBlank() }
        if (n.size < 3) return null
        val r = n[0].trim().toFloatOrNull() ?: return null
        val g = n[1].trim().toFloatOrNull() ?: return null
        val b = n[2].trim().toFloatOrNull() ?: return null
        val a = n.getOrNull(3)?.trim()?.let { if (it.endsWith("%")) it.removeSuffix("%").toFloatOrNull()?.div(100f) else it.toFloatOrNull() } ?: 1f
        return (kotlin.math.round(a * 255).toLong().coerceIn(0, 255) shl 24) or (r.toLong().coerceIn(0, 255) shl 16) or (g.toLong().coerceIn(0, 255) shl 8) or b.toLong().coerceIn(0, 255)
    }
    return when (t) {
        "black" -> 0xFF000000
        "white" -> 0xFFFFFFFF
        "red" -> 0xFFFF0000
        "transparent" -> 0x00000000
        else -> null
    }
}

private fun mixColor(a: Long, b: Long, f: Float): Long {
    fun ch(c: Long, s: Int) = ((c ushr s) and 0xFF).toFloat()
    fun m(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * f).toLong().coerceIn(0, 255) shl s
    return m(24) or m(16) or m(8) or m(0)
}

/**
 * Whether a function pushes colours far enough past 0..1 that the next one
 * must see them clamped first. Mild ones (a 1.25 saturate, a hue turn) fold
 * into one matrix with what follows, which looks the same and saves a layer
 * a frame; COOMER's `saturate(60)` gets a stage of its own.
 */
private fun canOverflow(fn: Filter.Fn): Boolean = when (fn.name) {
    "brightness", "contrast", "saturate" -> fn.amount > 2f
    else -> false
}

/** The Filter Effects spec's matrix for one function. */
private fun matrixOf(fn: Filter.Fn): FloatArray? {
    val a = fn.amount
    return when (fn.name) {
        "brightness" -> if (a == 1f) null else floatArrayOf(a, 0f, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, 0f, a, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
        "contrast" -> if (a == 1f) null else (127.5f * (1 - a)).let { o -> floatArrayOf(a, 0f, 0f, 0f, o, 0f, a, 0f, 0f, o, 0f, 0f, a, 0f, o, 0f, 0f, 0f, 1f, 0f) }
        "opacity" -> if (a == 1f) null else floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, a.coerceIn(0f, 1f), 0f)
        "invert" -> if (a == 0f) null else (1 - 2 * a.coerceIn(0f, 1f)).let { s -> val o = 255f * a.coerceIn(0f, 1f); floatArrayOf(s, 0f, 0f, 0f, o, 0f, s, 0f, 0f, o, 0f, 0f, s, 0f, o, 0f, 0f, 0f, 1f, 0f) }
        "saturate" -> if (a == 1f) null else saturate(a)
        "grayscale" -> if (a == 0f) null else saturate(1 - a.coerceIn(0f, 1f))
        "sepia" -> if (a == 0f) null else a.coerceIn(0f, 1f).let { s ->
            floatArrayOf(
                0.393f + 0.607f * (1 - s), 0.769f - 0.769f * (1 - s), 0.189f - 0.189f * (1 - s), 0f, 0f,
                0.349f - 0.349f * (1 - s), 0.686f + 0.314f * (1 - s), 0.168f - 0.168f * (1 - s), 0f, 0f,
                0.272f - 0.272f * (1 - s), 0.534f - 0.534f * (1 - s), 0.131f + 0.869f * (1 - s), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        }
        "hue-rotate" -> if (a % 360f == 0f) null else {
            val r = a * PI.toFloat() / 180f
            val c = cos(r); val s = sin(r)
            floatArrayOf(
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        }
        else -> null
    }
}

private fun saturate(s: Float) = floatArrayOf(
    0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0f, 0f,
    0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0f, 0f,
    0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0f, 0f,
    0f, 0f, 0f, 1f, 0f,
)

/** [a] after [b]: both 4×5 colour matrices with a fifth column of offsets. */
private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (r in 0 until 4) {
        for (c in 0 until 5) {
            var v = if (c == 4) a[r * 5 + 4] else 0f
            for (k in 0 until 4) v += a[r * 5 + k] * b[k * 5 + c]
            out[r * 5 + c] = v
        }
    }
    return out
}
