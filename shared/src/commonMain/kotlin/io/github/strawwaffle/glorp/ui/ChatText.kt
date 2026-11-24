package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.Pose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * Each chat line is one text element with its pictures painted in (see
 * [ChatText]), rather than text with a small component for each emote,
 * badge and link. Measured replaying the busiest real chat on a mid-range
 * phone: at a steady 40 messages a second, janky frames fell from 52% to 14%
 * and CPU by 30%. Dev builds can draw the old way with `--es chattext 0`.
 */
object ChatRendering {
    var singleNode = true

    /**
     * When chat last ran busy (over [BUSY_PER_SECOND] new lines a second):
     * until a little after, moving emotes draw at 30 frames a second instead
     * of every frame. Measured replaying a real 40-a-second evening full of
     * shaking emotes, every-frame animation took about 40% more CPU.
     */
    var busyUntil = 0L
        private set
    private val arrivals = ArrayDeque<Pair<Long, Int>>()

    /** [count] new lines just came in. */
    fun arrived(count: Int) {
        val now = Clock.System.now().toEpochMilliseconds()
        arrivals.addLast(now to count)
        while (arrivals.isNotEmpty() && arrivals.first().first < now - 2_000) arrivals.removeFirst()
        if (arrivals.sumOf { it.second } > BUSY_PER_SECOND * 2) busyUntil = now + 3_000
    }

    val busy: Boolean get() = Clock.System.now().toEpochMilliseconds() < busyUntil

    private const val BUSY_PER_SECOND = 15

    /** Dev builds (`--es emote-cdn https://r2cdn.destiny.gg`): emotes from another copy of the site than chat's, to compare them. */
    var emoteCdn: String? = null

    /**
     * Dev builds (`--es emote-clock <ms>`): every line drawn as at this moment,
     * standing still, so a line sent [n] ms before it shows its emotes [n] ms
     * in. For comparing emotes frame by frame with the site's.
     */
    var clockAt: Long? = null
}

/** Something drawn in a line's text, at a placeholder the size of it. */
internal sealed interface InlineItem {
    val widthDp: Int
    val heightDp: Int

    class EmoteItem(val emote: Emote, val sentAt: Long) : InlineItem {
        // Its margins take room or, negative, give it back to the neighbours.
        override val widthDp get() = (emote.width + emote.marginLeft + emote.marginRight).roundToInt().coerceAtLeast(0)
        override val heightDp get() = emote.height
    }

    class BadgeItem(val url: String, val label: String, override val widthDp: Int, override val heightDp: Int) : InlineItem

    class PlatformItem(val platform: String) : InlineItem {
        override val widthDp get() = 19
        override val heightDp get() = 16
    }
}

/**
 * A line ready to lay out and draw: its text, with what can be tapped marked
 * by string annotations (`nick`, `url`, `embed`, `reveal`), and a placeholder
 * for each picture in [items], in the same order.
 */
internal class PreparedLine(
    val text: AnnotatedString,
    val placeholders: List<AnnotatedString.Range<Placeholder>>,
    val items: List<InlineItem>,
    val mentionsMe: Boolean,
    val rainbowName: IntRange?,
    /** Embeds by key, for a tap on an `embed` annotation. */
    val embeds: Map<String, io.github.strawwaffle.glorp.site.Embed>,
) {
    /** Whether something is moving that needs every frame: a strip stepping, an emote shaking, turning or sliding. */
    fun movesFast(now: Long): Boolean = items.any { item ->
        if (item !is InlineItem.EmoteItem || item.sentAt <= 0) return@any false
        val t = now - item.sentAt
        val e = item.emote
        e.sprite?.frameAt(t) != null || e.motions.any { m ->
            t >= m.delayMs && t < m.endMs && m.frames.any { it.transform != null || it.background != null || it.clip != null || it.width != null || it.left != null }
        }
    }

    /** An emote still moving, or a name in the moving rainbow: the row needs a clock. */
    fun moves(now: Long): Boolean = rainbowName != null || items.any {
        it is InlineItem.EmoteItem && it.sentAt > 0 &&
            (it.emote.motions.any { m -> m.iterations == Int.MAX_VALUE } || now - it.sentAt < it.emote.settlesAfterMs)
    }
}

/** What a tap on a line found. */
internal sealed interface LineTap {
    class Nick(val nick: String) : LineTap
    class Url(val url: String) : LineTap
    class EmbedTap(val embed: io.github.strawwaffle.glorp.site.Embed) : LineTap
    data object Reveal : LineTap
    class EmoteTap(val prefix: String) : LineTap
}

/**
 * Pictures for [ChatText], as painters, shared by every line: drawn straight
 * from here on a line's first frame once loaded. A load finishing redraws
 * the lines that wanted it. Kept to the most recent [MOST], as their bitmaps
 * are held while here (Coil's own cache still has the rest).
 */
internal object ChatImages {
    private const val MOST = 160
    private val painters = mutableStateMapOf<String, Painter>()
    private val order = LinkedHashSet<String>()
    private val loading = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun get(url: String, context: PlatformContext): Painter? {
        painters[url]?.let { return it }
        if (loading.add(url)) {
            scope.launch {
                val loader = SingletonImageLoader.get(context)
                val request = ImageRequest.Builder(context).data(url).size(coil3.size.Size.ORIGINAL).build()
                val result = runCatching { loader.execute(request) }.getOrNull()
                loading.remove(url)
                if (result is SuccessResult) {
                    painters[url] = playingPainter(result.image, context) ?: result.image.asPainter(context)
                    order.remove(url); order.add(url)
                    while (order.size > MOST) painters.remove(order.first().also { order.remove(it) })
                }
            }
        }
        return null
    }
}

/** A picture that plays by itself (a GIF) as a painter that keeps drawing its current frame; null for a still one. */
internal expect fun playingPainter(image: coil3.Image, context: PlatformContext): Painter?

/**
 * Where an emote is drawn, as the site places it: each emote's stylesheet
 * gives it `margin-top: -height` and `top: height / 4`, so it adds nothing to
 * its line's height (its placeholder is only as tall as a hairline, sitting
 * on the baseline) and hangs down a quarter of its height from the baseline,
 * reaching up over the line above.
 */
internal fun Density.emoteRect(emote: Emote, placeholder: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect {
    val h = emote.height.dp.toPx()
    // `vertical-align: -5px` lowers it that much more.
    val bottom = placeholder.bottom + h / 4 - emote.verticalAlign.dp.toPx()
    val left = placeholder.left + emote.marginLeft.dp.toPx()
    return androidx.compose.ui.geometry.Rect(left, bottom - h, left + emote.width.dp.toPx(), bottom)
}

/** Time far past any finite animation: everything shows how it rests. */
private const val SETTLED_MS = Long.MAX_VALUE / 4

/**
 * One line of chat as a single element: the text laid out once, its emotes,
 * badges and platform marks painted at their placeholders, and one handler
 * for taps and holds. It takes the place of [androidx.compose.material3.Text]
 * with inline content, which made a small component (with its own tap
 * handler and image request) for every picture and every link.
 */
@Composable
internal fun ChatText(
    prepared: PreparedLine,
    style: TextStyle,
    density: Density,
    modifier: Modifier,
    animate: Boolean,
    onTap: (LineTap) -> Unit,
    onHold: (() -> Unit)?,
) {
    val context = LocalPlatformContext.current
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val holder = remember { LayoutHolder() }
    // Platform marks are vectors, made once each for this line.
    val platforms = HashMap<String, Painter>()
    for (item in prepared.items) {
        if (item is InlineItem.PlatformItem && item.platform !in platforms) {
            key(item.platform) { platforms[item.platform] = rememberVectorPainter(PlatformIcons.vectorOf(item.platform).first) }
        }
    }
    // The clock everything moving in this line reads while drawing; only a
    // line with something moving keeps it going, and stops it when settled.
    val fixed = ChatRendering.clockAt
    val clock = remember(prepared) { mutableLongStateOf(fixed ?: SETTLED_MS) }
    if (fixed == null && animate && prepared.moves(Clock.System.now().toEpochMilliseconds())) {
        LaunchedEffect(prepared) {
            while (true) {
                val now = Clock.System.now().toEpochMilliseconds()
                withFrameMillis { }
                clock.longValue = now
                if (!prepared.moves(now)) break
                // A rainbow sweeping, a tint changing, tears falling over an
                // emote: 30 frames a second looks the same and halves the
                // drawing in a busy chat. Shakes, spins and strips get every frame.
                // So does everything while chat runs busy (see ChatRendering.busy).
                delay(if (prepared.movesFast(now) && !ChatRendering.busy) 8 else 33)
            }
            clock.longValue = SETTLED_MS
        }
    }

    val semantics = Modifier.semantics {
        text = prepared.text
        if (onHold != null) onLongClick("Message options") { onHold(); true }
        val actions = ArrayList<CustomAccessibilityAction>()
        for (range in prepared.text.getStringAnnotations(0, prepared.text.length)) {
            val label = prepared.text.substring(range.start, range.end)
            when (range.tag) {
                "nick" -> actions += CustomAccessibilityAction("About $label") { onTap(LineTap.Nick(range.item)); true }
                "url" -> actions += CustomAccessibilityAction("Open $label") { onTap(LineTap.Url(range.item)); true }
                "embed" -> prepared.embeds[range.item]?.let { e -> actions += CustomAccessibilityAction("Watch $label") { onTap(LineTap.EmbedTap(e)); true } }
                "reveal" -> actions += CustomAccessibilityAction("Show message") { onTap(LineTap.Reveal); true }
            }
        }
        if (actions.isNotEmpty()) customActions = actions
    }
    val taps = Modifier.pointerInput(prepared, onHold) {
        detectTapGestures(
            onLongPress = { onHold?.invoke() },
            onTap = { at -> holder.hit(prepared, at, this)?.let(onTap) },
        )
    }
    val drawText = Modifier.drawBehind {
        val layout = holder.layout ?: return@drawBehind
        val rainbow = prepared.rainbowName?.let { holder.rainbow(layout, it) }
        if (rainbow == null) drawText(layout)
        else {
            val now = clock.longValue
            // The rainbow is laid over the name's letters only (SrcAtop), in a
            // layer just the size of the name, so the emotes drawn after can
            // still reach outside the row.
            drawIntoCanvas { it.saveLayer(rainbow.second.inflate(2f), Paint()) }
            drawText(layout)
            drawRainbow(rainbow, if (now == SETTLED_MS) 0f else (now % 3_000) / 3_000f)
            drawIntoCanvas { it.restore() }
            drawIntoCanvas { it.save(); it.clipRect(rainbow.second.inflate(2f), androidx.compose.ui.graphics.ClipOp.Difference) }
            drawText(layout)
            drawIntoCanvas { it.restore() }
        }
    }
    val drawPictures = Modifier.drawBehind {
        val layout = holder.layout ?: return@drawBehind
        val now = clock.longValue
        val rects = layout.placeholderRects
        // Emotes raised by a z-index keyframe are drawn after the rest, over them.
        val raised = prepared.items.indices.filter { i ->
            val item = prepared.items[i]
            item is InlineItem.EmoteItem && animate && item.sentAt > 0 && now != SETTLED_MS && item.emote.motions.isNotEmpty() &&
                item.emote.motions.fold(item.emote.rest) { p, m -> m.apply(now - item.sentAt, p) }.z > 0f
        }.toSet()
        val order = if (raised.isEmpty()) prepared.items.indices else prepared.items.indices.filter { it !in raised } + raised
        for (i in order) {
            val item = prepared.items[i]
            val rect = rects.getOrNull(i) ?: continue
            when (item) {
                is InlineItem.BadgeItem -> ChatImages.get(item.url, context)?.let { p ->
                    translate(rect.left, rect.top) { with(p) { draw(Size(rect.width, rect.height)) } }
                }
                is InlineItem.PlatformItem -> platforms[item.platform]?.let { p ->
                    val color = PlatformIcons.vectorOf(item.platform).second
                    val side = 15.dp.toPx()
                    translate(rect.left, rect.top + (rect.height - side) / 2) {
                        with(p) { draw(Size(side, side), colorFilter = ColorFilter.tint(color)) }
                    }
                }
                is InlineItem.EmoteItem -> ChatImages.get(item.emote.url, context)?.let { p ->
                    val elapsed = if (!animate || item.sentAt <= 0 || now == SETTLED_MS) SETTLED_MS else now - item.sentAt
                    val at = emoteRect(item.emote, rect)
                    drawEmote(item.emote, p, at.left, at.top, at.height, elapsed, still = !animate, measurer) { url -> ChatImages.get(url, context) }
                }
            }
        }
    }
    // A line whose pictures move draws them in a layer of their own, so each
    // new frame of an emote re-records only the pictures, not the line's text.
    val layer = remember(prepared) { animate && prepared.moves(Clock.System.now().toEpochMilliseconds()) }
    Layout(modifier.then(semantics).then(taps).then(drawText).then(if (layer) Modifier.graphicsLayer() else Modifier).then(drawPictures)) { _, constraints ->
        val layout = measurer.measure(
            prepared.text,
            style,
            placeholders = prepared.placeholders,
            constraints = Constraints(maxWidth = constraints.maxWidth),
            density = density,
        )
        holder.layout = layout
        layout(constraints.maxWidth, layout.size.height) {}
    }
}

/** The line's layout, kept for drawing and for finding what a tap hit. Not state: a new layout comes with a new measure. */
private class LayoutHolder {
    var layout: TextLayoutResult? = null
        set(value) {
            field = value
            outline = null
        }
    private var outline: Pair<Path, androidx.compose.ui.geometry.Rect>? = null

    fun rainbow(layout: TextLayoutResult, range: IntRange): Pair<Path, androidx.compose.ui.geometry.Rect>? {
        if (range.last >= layout.layoutInput.text.length) return null
        return outline ?: layout.getPathForRange(range.first, range.last + 1).let { it to it.getBounds() }.also { outline = it }
    }

    fun hit(prepared: PreparedLine, at: Offset, density: Density): LineTap? {
        val layout = layout ?: return null
        // A picture first: an emote opens its card.
        layout.placeholderRects.forEachIndexed { i, rect ->
            val item = prepared.items.getOrNull(i)
            if (rect != null && item is InlineItem.EmoteItem) {
                if (density.emoteRect(item.emote, rect).contains(at)) return LineTap.EmoteTap(item.emote.prefix)
            }
        }
        // Then the text under the finger, if the finger is on a line's text.
        val line = layout.getLineForVerticalPosition(at.y)
        if (at.x < layout.getLineLeft(line) || at.x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForPosition(at).let { if (it > 0 && it >= layout.getLineEnd(line)) it - 1 else it }
        val found = prepared.text.getStringAnnotations(offset, offset + 1).firstOrNull() ?: return null
        return when (found.tag) {
            "nick" -> LineTap.Nick(found.item)
            "url" -> LineTap.Url(found.item)
            "embed" -> prepared.embeds[found.item]?.let { LineTap.EmbedTap(it) }
            "reveal" -> LineTap.Reveal
            else -> null
        }
    }
}

/** The site's rainbow (flairs.css): eight hues at 100% saturation, 65% lightness, once across the name. */
private val rainbowColors = (0..8).map { androidx.compose.ui.graphics.Color.hsl(it * 45f, 1f, 0.65f) }

/** Laid over the name's letters only; the row draws in a layer of its own for this (see ChatRow). */
private fun DrawScope.drawRainbow(outline: Pair<Path, androidx.compose.ui.geometry.Rect>, phase: Float) {
    val (path, bounds) = outline
    val width = bounds.width.coerceAtLeast(1f)
    val from = bounds.left + phase * width
    drawPath(path, Brush.linearGradient(rainbowColors, Offset(from, 0f), Offset(from + width, 0f), TileMode.Repeated), blendMode = BlendMode.SrcAtop)
}

/**
 * An emote as [EmoteImage] draws it: the frame of its strip for [elapsedMs],
 * placed by its background, moved, turned and scaled about its origin.
 */
private fun DrawScope.drawEmote(
    emote: Emote, painter: Painter, left: Float, top: Float, heightPx: Float, elapsedMs: Long, still: Boolean,
    measurer: TextMeasurer, pictures: (String) -> Painter?,
) {
    val sprite = emote.sprite
    val scale = heightPx / emote.height
    val h = heightPx
    val at = if (still) 0L else elapsedMs
    val moving = emote.motions.isNotEmpty() || emote.rest != Pose.Rest
    val pose = when {
        !moving -> Pose.Rest
        still -> emote.rest
        else -> emote.motions.fold(emote.rest) { p, m -> m.apply(at, p) }
    }
    // Keyframes may widen the box it's drawn in (PepoPunch's punch).
    val w = (emote.width + pose.dw) * scale
    val frame = when {
        sprite == null -> 0
        sprite.frameAt(at) != null -> sprite.frameAt(at)!!
        sprite.holdLast && at >= sprite.totalMs -> minOf(sprite.frames, sprite.sheetWidth / emote.width - 1)
        else -> 0
    }
    val playing = sprite?.frameAt(at) != null
    val base = if (playing) sprite?.startX ?: emote.offsetX else emote.offsetX
    val sheetPx = emote.sheetWidth * scale
    val raw = (base + frame * (sprite?.stepX ?: emote.width)) * scale
    // A tiling picture wraps round; one that doesn't is simply off to the side.
    val offset = if (emote.repeatX && sheetPx > 0f) ((raw % sheetPx) + sheetPx) % sheetPx else raw
    val stages = pose.filter.colorStages()
    val filter = stages.firstOrNull()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
    // A keyframe's own pivot (BLADE stabs from its corner) over the emote's.
    val pivot = Offset((if (pose.ox.isNaN()) emote.originX else pose.ox) * w, (if (pose.oy.isNaN()) emote.originY else pose.oy) * h)
    // `left` keyframes move it from where it stands (GIGACHAD's BLADE sliding in).
    val dx = left + pose.tx.dp.toPx() + (pose.dml + pose.dleft) * scale
    val dy = top + pose.ty.dp.toPx()
    withTransform({
        translate(dx, dy)
        if (pose.rotate != 0f) rotate(pose.rotate, pivot)
        if (pose.sx != 1f || pose.sy != 1f) scale(pose.sx, pose.sy, pivot)
        if (pose.skewX != 0f || pose.skewY != 0f) skew(pose.skewX, pose.skewY, pivot)
    }) {
        val shape = pose.clip?.let { c ->
            Path().apply {
                moveTo(c[0] * w, c[1] * h)
                for (i in 2 until c.size - 1 step 2) lineTo(c[i] * w, c[i + 1] * h)
                close()
            }
        }
        if (shape != null) clipPath(shape) { drawSheet(emote, painter, w, h, offset, scale, pose, filter, stages) }
        else drawSheet(emote, painter, w, h, offset, scale, pose, filter, stages)
        if (emote.overlays.isNotEmpty()) clipRect(0f, 0f, w, h) {
            for (overlay in emote.overlays) drawOverlay(overlay, at, still, scale, w, h, measurer) { url ->
                if (url == "inherit") painter else pictures(url)
            }
        }
    }
}

/** Overlay text laid out once per look, shared by every line: an emoji at a size. */
private val overlayLayouts = HashMap<String, androidx.compose.ui.text.TextLayoutResult>()

/** A `::before` or `::after`: its text or picture where and as its rules and keyframes put it. */
private fun DrawScope.drawOverlay(
    overlay: io.github.strawwaffle.glorp.site.Overlay, at: Long, still: Boolean, scale: Float, boxWidth: Float, boxHeight: Float,
    measurer: TextMeasurer, pictures: (String) -> Painter?,
) {
    val pose = if (still) overlay.rest else overlay.motions.fold(overlay.rest) { p, m -> m.apply(at, p) }
    if (pose.opacity <= 0f || pose.sx == 0f || pose.sy == 0f) return
    val fraction = if (pose.widthFraction.isNaN()) overlay.widthFraction else pose.widthFraction
    val widthPx = fraction?.let { it * boxWidth } ?: overlay.widthPx?.let { it * scale }
    val image = overlay.image
    val layout: androidx.compose.ui.text.TextLayoutResult?
    val bw: Float
    val bh: Float
    if (image != null) {
        layout = null
        bw = widthPx ?: boxWidth
        bh = overlay.heightPx?.let { it * scale } ?: boxHeight
    } else {
        val text = pose.text ?: overlay.text
        val px = (if (pose.fontPx.isNaN()) overlay.fontSize else pose.fontPx) * scale
        val lineHeight = (if (pose.lineHeightPx.isNaN()) overlay.lineHeight else pose.lineHeightPx)?.let { it * scale } ?: (px * 1.2f)
        val letters = (if (pose.letterPx.isNaN()) overlay.letterSpacing else pose.letterPx) * scale
        val color = overlay.argb?.let { androidx.compose.ui.graphics.Color(it.toInt()) } ?: androidx.compose.ui.graphics.Color.White
        val key = "$text|$px|$lineHeight|$letters|$widthPx|${overlay.argb}|${overlay.italic}|${overlay.shadow}|$density|$fontScale"
        layout = overlayLayouts.getOrPut(key) {
            if (overlayLayouts.size > 200) overlayLayouts.clear()
            val sp = { v: Float -> (v / fontScale / density).sp }
            val style = TextStyle(
                color = color,
                fontSize = sp(px),
                lineHeight = sp(lineHeight),
                fontStyle = if (overlay.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                letterSpacing = sp(letters),
                shadow = overlay.shadow?.let { androidx.compose.ui.graphics.Shadow(androidx.compose.ui.graphics.Color(it.argb.toInt()), Offset(it.dx * scale, it.dy * scale), it.blur * scale) },
            )
            // With a width it wraps to it (HACKERMAN's lines of digits); without, one line.
            if (widthPx != null && widthPx > 0f) measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx.roundToInt().coerceAtLeast(1)), density = this)
            else measurer.measure(text, style, softWrap = false, density = this)
        }
        bw = layout.size.width.toFloat()
        bh = layout.size.height.toFloat()
    }
    val x = when {
        !pose.rightPx.isNaN() -> boxWidth - pose.rightPx * scale - bw
        else -> overlay.left?.let { it * scale } ?: overlay.right?.let { boxWidth - it * scale - bw } ?: 0f
    } + (pose.tx + pose.dleft) * scale
    val y = overlay.top * scale + (pose.ty + pose.dtop) * scale
    val pivot = Offset((if (pose.ox.isNaN()) overlay.originX else pose.ox) * bw, (if (pose.oy.isNaN()) overlay.originY else pose.oy) * bh)
    val stages = pose.filter.colorStages()
    val blend = blendOf(overlay.blend)
    val bounds = androidx.compose.ui.geometry.Rect(0f, 0f, bw, bh)
    withTransform({
        translate(x, y)
        if (pose.rotate != 0f) rotate(pose.rotate, pivot)
        if (pose.sx != 1f || pose.sy != 1f) scale(pose.sx, pose.sy, pivot)
    }) {
        // Blended with what's under it, its colour stages inside that, and a mask inside those.
        if (blend != null) drawContext.canvas.saveLayer(bounds, Paint().apply { blendMode = blend })
        for (stage in stages.asReversed()) {
            drawContext.canvas.saveLayer(bounds, Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(stage)) })
        }
        val mask = overlay.mask
        if (mask != null) drawContext.canvas.saveLayer(bounds, Paint())
        val alpha = pose.opacity.coerceIn(0f, 1f)
        if (layout != null) drawText(layout, alpha = alpha)
        else pictures(image!!)?.let { p -> with(p) { draw(Size(bw, bh), alpha = alpha) } }
        if (mask != null) {
            // CSS angles: 180deg runs top to bottom, 90deg left to right.
            val r = mask[0] * kotlin.math.PI.toFloat() / 180f
            val dx = kotlin.math.sin(r)
            val dy = -kotlin.math.cos(r)
            val half = (kotlin.math.abs(bw * dx) + kotlin.math.abs(bh * dy)) / 2
            val c = Offset(bw / 2, bh / 2)
            val start = c - Offset(dx * half, dy * half)
            val end = c + Offset(dx * half, dy * half)
            drawRect(
                Brush.linearGradient(
                    mask[1] to androidx.compose.ui.graphics.Color.Black,
                    mask[2] to androidx.compose.ui.graphics.Color.Transparent,
                    start = start, end = end,
                ),
                size = Size(bw, bh),
                blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        }
        repeat(stages.size) { drawContext.canvas.restore() }
        if (blend != null) drawContext.canvas.restore()
    }
}

/** A CSS `mix-blend-mode` as Compose's. */
private fun blendOf(name: String?): BlendMode? = when (name) {
    "multiply" -> BlendMode.Multiply
    "screen" -> BlendMode.Screen
    "overlay" -> BlendMode.Overlay
    "darken" -> BlendMode.Darken
    "lighten" -> BlendMode.Lighten
    "color-dodge" -> BlendMode.ColorDodge
    "color-burn" -> BlendMode.ColorBurn
    "hard-light" -> BlendMode.Hardlight
    "soft-light" -> BlendMode.Softlight
    "difference" -> BlendMode.Difference
    "exclusion" -> BlendMode.Exclusion
    "hue" -> BlendMode.Hue
    "saturation" -> BlendMode.Saturation
    "color" -> BlendMode.Color
    "luminosity" -> BlendMode.Luminosity
    else -> null
}

private fun DrawScope.drawSheet(emote: Emote, painter: Painter, w: Float, h: Float, offset: Float, scale: Float, pose: Pose, filter: ColorFilter?, stages: List<FloatArray>) {
    // A `drop-shadow` glow: the emote's shape in the glow's colour, spread
    // out by drawing it round a ring or two (a cheap stand-in for a blur).
    for (glow in pose.filter.glows) {
        val color = androidx.compose.ui.graphics.Color(glow.argb.toInt())
        if (color.alpha == 0f) continue
        // Eight copies round one ring (four for a thin outline): a glow that
        // pulses forever (a user's YEE) is drawn every frame, so few copies.
        val r = glow.blur * scale
        val count = if (r < 1.5f) 4 else 8
        val points = (0 until count).map { k -> r / 2 to k * 2 * kotlin.math.PI.toFloat() / count }
        val tint = ColorFilter.tint(color.copy(alpha = 1f), BlendMode.SrcIn)
        val each = (color.alpha * 2f / points.size).coerceAtMost(1f) * pose.opacity.coerceIn(0f, 1f)
        for ((ring, angle) in points) {
            translate(glow.dx * scale + ring * kotlin.math.cos(angle), glow.dy * scale + ring * kotlin.math.sin(angle)) {
                clipRect(0f, 0f, w, h) {
                    translate(left = -offset + pose.bx * scale, top = (emote.offsetY + pose.by) * scale) {
                        with(painter) { draw(Size(emote.sheetWidth * scale, emote.sheetHeight * scale), alpha = each, colorFilter = tint) }
                    }
                }
            }
        }
    }
    // Each later colour stage in a layer of its own, which clamps what's under it first.
    val layers = stages.drop(1).asReversed()
    for (stage in layers) {
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, w, h), Paint().apply { colorFilter = ColorFilter.colorMatrix(ColorMatrix(stage)) })
    }
    run {
        clipRect(0f, 0f, w, h) {
            // A picture slid inside its box (GIGACHAD arriving) as well.
            translate(left = -offset + pose.bx * scale, top = (emote.offsetY + pose.by) * scale) {
                with(painter) { draw(Size(emote.sheetWidth * scale, emote.sheetHeight * scale), alpha = pose.opacity.coerceIn(0f, 1f), colorFilter = filter) }
            }
            // Its next tile, where the strip's end leaves the box short.
            val sheetPx = emote.sheetWidth * scale
            if (emote.repeatX && offset - pose.bx * scale + w > sheetPx) {
                translate(left = -offset + pose.bx * scale + sheetPx, top = (emote.offsetY + pose.by) * scale) {
                    with(painter) { draw(Size(sheetPx, emote.sheetHeight * scale), alpha = pose.opacity.coerceIn(0f, 1f), colorFilter = filter) }
                }
            }
        }
    }
    repeat(layers.size) { drawContext.canvas.restore() }
}

/** CSS `skew(x, y)` in degrees about [pivot]. */
private fun androidx.compose.ui.graphics.drawscope.DrawTransform.skew(xDeg: Float, yDeg: Float, pivot: Offset) {
    val m = androidx.compose.ui.graphics.Matrix()
    m.values[androidx.compose.ui.graphics.Matrix.SkewX] = kotlin.math.tan(xDeg * kotlin.math.PI.toFloat() / 180f)
    m.values[androidx.compose.ui.graphics.Matrix.SkewY] = kotlin.math.tan(yDeg * kotlin.math.PI.toFloat() / 180f)
    translate(pivot.x, pivot.y)
    transform(m)
    translate(-pivot.x, -pivot.y)
}
