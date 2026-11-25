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
/** The chat's one text measurer, shared by its lines (none outside a chat). */
internal val LocalChatMeasurer = androidx.compose.runtime.staticCompositionLocalOf<TextMeasurer?> { null }

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

    /** chat-gui's splat picture for finished combos of 10 and more, once read from the site (see ChatViewModel). */
    private val splat = androidx.compose.runtime.mutableStateOf<String?>(null)
    var comboSplat: String?
        get() = splat.value
        set(value) { splat.value = value }
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

    /**
     * A combo's tail, as chat-gui draws it: the count (bigger from 5, 10, 20,
     * 30 and 50 hits), "X", then "HITS" (which flashes on every hit) while it
     * runs, or "C-C-C-COMBO" (sliding in) once another message ends it, over
     * the site's splat fading to grey from 10 hits.
     */
    class ComboItem(
        val count: Int,
        val lastHitAt: Long,
        val endedAt: Long,
        override val widthDp: Int,
    ) : InlineItem {
        override val heightDp get() = 28
        val group get() = when {
            count >= 50 -> 50; count >= 30 -> 30; count >= 20 -> 20; count >= 10 -> 10; count >= 5 -> 5; else -> 2
        }
        /** The count's size, as a multiple of chat's text. */
        val countScale get() = when (group) { 50 -> 5f; 30 -> 4f; 20 -> 3f; 10 -> 2f; 5 -> 1.5f; else -> 1.15f }
        // HITS flashing, C-C-C-COMBO sliding in, and from 10 hits the splat greying out.
        // Not while chat runs busy (see ChatRendering.busy): there a combo shows how each flash and slide ends.
        fun moves(now: Long) = !ChatRendering.busy && ((endedAt == 0L && now - lastHitAt in 0 until HIT_MS) ||
            (endedAt > 0 && now - endedAt in 0 until (if (group >= 10) GREYOUT_MS else COMPLETE_MS)))

        companion object {
            const val HIT_MS = 600L
            const val COMPLETE_MS = 1000L
            const val GREYOUT_MS = 3500L
        }
    }

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
    /** Its text already measured off the main thread (see RowPrefetch); used if the width, style and density still match. */
    @kotlin.concurrent.Volatile
    var measured: TextLayoutResult? = null

    /**
     * When only sprite strips are moving in this line: how long until the
     * next one steps, so it can sleep till then. Null when anything else
     * moves (a shake, a fade, an overlay, a rainbow, a combo), which needs
     * frames as before.
     */
    fun nextStepMs(now: Long): Long? {
        if (rainbowName != null) return null
        var next = Long.MAX_VALUE
        for (item in items) {
            when (item) {
                is InlineItem.ComboItem -> if (item.moves(now)) return null
                is InlineItem.EmoteItem -> {
                    if (item.sentAt <= 0) continue
                    val t = now - item.sentAt
                    val e = item.emote
                    if (e.motions.any { t >= it.delayMs && t < it.endMs }) return null
                    if (e.overlays.any { o -> o.motions.any { t >= it.delayMs && t < it.endMs } }) return null
                    val s = e.sprite ?: continue
                    if (s.frameAt(t) == null) {
                        // Waiting out its delay: until it starts.
                        if (t < s.delayMs) next = minOf(next, s.delayMs - t)
                        continue
                    }
                    val stepMs = (s.durationMs / s.frames.coerceAtLeast(1)).coerceAtLeast(1)
                    val into = (t - s.delayMs) % stepMs
                    next = minOf(next, stepMs - into, s.totalMs - t)
                }
                else -> {}
            }
        }
        return if (next == Long.MAX_VALUE) null else next
    }

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
    fun moves(now: Long): Boolean = rainbowName != null || items.any { it is InlineItem.ComboItem && it.moves(now) } || items.any {
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
    // One state slot per picture: a line drawing it is redrawn when that one
    // loads, not when any does. Kept in order of use, so the emotes drawn most
    // stay and the least used go first.
    private val slots = LinkedHashMap<String, androidx.compose.runtime.MutableState<Painter?>>(16, 0.75f, true)
    private val loading = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Pictures that play by themselves (GIF, animated PNG or AVIF): a line drawing one gets a layer of its own. */
    private val playing = mutableStateMapOf<String, Boolean>()

    fun plays(url: String): Boolean = playing[url] == true

    fun get(url: String, context: PlatformContext): Painter? {
        val slot = slots.getOrPut(url) { androidx.compose.runtime.mutableStateOf(null) }
        slot.value?.let { return it }
        if (loading.add(url)) {
            scope.launch {
                val loader = SingletonImageLoader.get(context)
                // Cached by the file's own name: the site puts its version in
                // the address, which changes on every release though the picture doesn't.
                val key = cacheKey(url)
                val request = ImageRequest.Builder(context).data(url).size(coil3.size.Size.ORIGINAL)
                    .memoryCacheKey(key).diskCacheKey(key).build()
                val result = runCatching { loader.execute(request) }.getOrNull()
                loading.remove(url)
                if (result is SuccessResult) {
                    val moving = playingPainter(result.image, context)
                    if (moving != null) playing[url] = true
                    slots.getOrPut(url) { androidx.compose.runtime.mutableStateOf(null) }.value = moving ?: result.image.asPainter(context)
                    while (slots.size > MOST) {
                        val oldest = slots.keys.first()
                        slots.remove(oldest)?.value = null
                        playing.remove(oldest)
                    }
                }
            }
        }
        return null
    }

    /** The picture's file name (`66596c571d8e5.png`), unique per upload; the whole address for anything else. */
    internal fun cacheKey(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/')
        return if (url.startsWith("data:") || name.length < 8) url else "pic:$name"
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
    /** Holding the line; with the name held, when the finger is on one. */
    onHold: ((String?) -> Unit)?,
) {
    val context = LocalPlatformContext.current
    // One measurer for the whole chat (LocalChatMeasurer), not one per line.
    val measurer = LocalChatMeasurer.current ?: rememberTextMeasurer(cacheSize = 0)
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
                withFrameMillis { }
                // The time of the frame being drawn, not of before it was waited for.
                val now = Clock.System.now().toEpochMilliseconds()
                clock.longValue = now
                if (!prepared.moves(now)) break
                // A strip stepping (and nothing else moving) only needs a new
                // frame when its next step comes: most step every 50 to 100 ms.
                val step = prepared.nextStepMs(now)
                if (step != null) { delay(step.coerceAtLeast(8)); continue }
                // A rainbow sweeping, a tint changing, tears falling over an
                // emote: 30 frames a second looks the same and halves the
                // drawing in a busy chat. Shakes, spins and slides get every frame;
                // so does everything while chat runs busy (see ChatRendering.busy).
                delay(if (prepared.movesFast(now) && !ChatRendering.busy) 8 else 33)
            }
            clock.longValue = SETTLED_MS
        }
    }

    val semantics = Modifier.semantics {
        text = prepared.text
        if (onHold != null) onLongClick("Message options") { onHold(null); true }
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
            onLongPress = { at -> onHold?.invoke((holder.hit(prepared, at, this) as? LineTap.Nick)?.nick) },
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
            drawIntoCanvas { it.saveLayer(rainbow.second.inflate(2f), layer()) }
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
            item is InlineItem.EmoteItem && animate && item.sentAt > 0 && now != SETTLED_MS && item.emote.raisable &&
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
                is InlineItem.ComboItem -> drawCombo(item, rect, now, measurer) { url -> ChatImages.get(url, context) }
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
    // So does one with a picture that plays by itself (a GIF): its frames would re-record the text too.
    val playsItself = prepared.items.any { it is InlineItem.EmoteItem && ChatImages.plays(it.emote.url) }
    val layer = remember(prepared, playsItself) { playsItself || (animate && prepared.moves(Clock.System.now().toEpochMilliseconds())) }
    Layout(modifier.then(semantics).then(taps).then(drawText).then(if (layer) Modifier.graphicsLayer() else Modifier).then(drawPictures)) { _, constraints ->
        val ready = prepared.measured
        val layout = if (ready != null && ready.layoutInput.constraints.maxWidth == constraints.maxWidth && ready.layoutInput.style == style &&
            ready.layoutInput.density.density == density.density && ready.layoutInput.density.fontScale == density.fontScale
        ) ready
        else measurer.measure(
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
    // A still filter's stages and colour filter are worked out once per emote, not every frame.
    val stillFilter = pose.filter === emote.rest.filter
    val stages = if (stillFilter) emote.restStages else pose.filter.colorStages()
    val filter = if (stillFilter) {
        (emote.drawCache as? StillFilter ?: StillFilter(stages.firstOrNull()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }).also { emote.drawCache = it }).filter
    } else stages.firstOrNull()?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
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
        val radial = emote.radialMask
        if (radial != null) drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, w, h), layer())
        if (shape != null) clipPath(shape) { drawSheet(emote, painter, w, h, offset, scale, pose, filter, stages) }
        else drawSheet(emote, painter, w, h, offset, scale, pose, filter, stages)
        if (radial != null) {
            // CSS's default: an ellipse the box's shape, reaching its corners.
            withTransform({ scale(1f, h / w, Offset(w / 2, h / 2)) }) {
                drawCircle(
                    Brush.radialGradient(radial.first to androidx.compose.ui.graphics.Color.Black, radial.second to androidx.compose.ui.graphics.Color.Transparent, center = Offset(w / 2, h / 2), radius = w / 2 * 1.4142f),
                    radius = w, center = Offset(w / 2, h / 2), blendMode = BlendMode.DstIn,
                )
            }
            drawContext.canvas.restore()
        }
        if (emote.overlays.isNotEmpty()) clipRect(0f, 0f, w, h) {
            for (overlay in emote.overlays) drawOverlay(overlay, at, still, scale, w, h, measurer) { url ->
                if (url == "inherit") painter else pictures(url)
            }
        }
    }
}

/** An emote's still filter as a colour filter, kept on the emote ([Emote.drawCache]). */
private class StillFilter(val filter: ColorFilter?)

/**
 * The one paint layers are saved with: Android copies what it needs when a
 * layer is saved, so it's set and reused rather than made every frame.
 */
private val layerPaint = Paint()

private fun layer(colorFilter: ColorFilter? = null, blend: BlendMode = BlendMode.SrcOver): Paint =
    layerPaint.apply { this.colorFilter = colorFilter; this.blendMode = blend }

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
    val boxes = pose.boxShadows ?: overlay.boxShadows
    if (image != null || boxes != null) {
        layout = null
        bw = widthPx ?: boxWidth
        bh = overlay.heightFraction?.let { it * boxHeight } ?: overlay.heightPx?.let { it * scale } ?: boxHeight
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
                fontFamily = if (overlay.monospace) androidx.compose.ui.text.font.FontFamily.Monospace else null,
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
        if (blend != null) drawContext.canvas.saveLayer(bounds, layer(blend = blend))
        for (stage in stages.asReversed()) {
            drawContext.canvas.saveLayer(bounds, layer(ColorFilter.colorMatrix(ColorMatrix(stage))))
        }
        val mask = overlay.mask
        if (mask != null) drawContext.canvas.saveLayer(bounds, layer())
        val alpha = pose.opacity.coerceIn(0f, 1f)
        if (layout != null) drawText(layout, alpha = alpha)
        else if (image != null) pictures(image)?.let { p ->
            // As a background: its own size (the width given, the height keeping its shape), placed, tiled across if it repeats.
            val tileW = overlay.backgroundWidth?.let { it * scale } ?: bw
            val ratio = p.intrinsicSize.let { if (it.width > 0f && it.height > 0f && it.width.isFinite()) it.height / it.width else bh / bw }
            val tileH = if (overlay.backgroundWidth != null) tileW * ratio else bh
            clipRect(0f, 0f, bw, bh) {
                var tx = overlay.backgroundX * scale
                do {
                    translate(tx, overlay.backgroundY * scale) { with(p) { draw(Size(tileW, tileH), alpha = alpha) } }
                    tx += tileW
                } while (overlay.repeatX && tileW > 1f && tx < bw)
            }
        }
        else if (boxes != null) drawBoxShadows(boxes, bw, bh, scale, alpha)
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
        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, w, h), layer(ColorFilter.colorMatrix(ColorMatrix(stage))))
    }
    run {
        clipRect(0f, 0f, w, h) {
            // A picture slid inside its box (GIGACHAD arriving) as well.
            translate(left = -offset + pose.bx * scale, top = (emote.offsetY + pose.by) * scale) {
                with(painter) { draw(Size(emote.sheetWidth * scale, emote.sheetHeight * scale), alpha = pose.opacity.coerceIn(0f, 1f), colorFilter = filter) }
            }
            // Its next tiles, where the strip's end leaves the box short (ARRIVE's combo, many across).
            val sheetPx = emote.sheetWidth * scale
            if (emote.repeatX && sheetPx > 1f) {
                var x = -offset + pose.bx * scale + sheetPx
                while (x < w) {
                    translate(left = x, top = (emote.offsetY + pose.by) * scale) {
                        with(painter) { draw(Size(sheetPx, emote.sheetHeight * scale), alpha = pose.opacity.coerceIn(0f, 1f), colorFilter = filter) }
                    }
                    x += sheetPx
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

private val comboText = androidx.compose.ui.graphics.Color(0xFFDEDEDE)
private val comboLight = androidx.compose.ui.graphics.Color(0xFFFFF7F9)
private val comboDim = androidx.compose.ui.graphics.Color(0xFF999999)
private val comboRed = androidx.compose.ui.graphics.Color(0xFFB91010)

/** One piece of a combo's tail, laid out once per look. */
private fun DrawScope.comboPiece(measurer: TextMeasurer, text: String, px: Float, color: androidx.compose.ui.graphics.Color, bold: Boolean = false, italic: Boolean = false, letters: Float = 0f): androidx.compose.ui.text.TextLayoutResult {
    val key = "combo|$text|$px|${color.value}|$bold|$italic|$letters|$density|$fontScale"
    return overlayLayouts.getOrPut(key) {
        if (overlayLayouts.size > 200) overlayLayouts.clear()
        val sp = { v: Float -> (v / fontScale / density).sp }
        measurer.measure(
            text,
            TextStyle(
                color = color, fontSize = sp(px), letterSpacing = sp(letters * px),
                fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            ),
            softWrap = false, density = this,
        )
    }
}

/** Text with chat-gui's 1px black outline (four shadows). */
private fun DrawScope.outlined(layout: androidx.compose.ui.text.TextLayoutResult, x: Float, centreY: Float, alpha: Float = 1f, color: androidx.compose.ui.graphics.Color? = null) {
    val y = centreY - layout.size.height / 2f
    val o = 1.dp.toPx()
    for ((dx, dy) in listOf(-o to -o, o to -o, -o to o, o to o)) {
        drawText(layout, color = androidx.compose.ui.graphics.Color.Black, topLeft = Offset(x + dx, y + dy), alpha = alpha)
    }
    if (color != null) drawText(layout, color = color, topLeft = Offset(x, y), alpha = alpha)
    else drawText(layout, topLeft = Offset(x, y), alpha = alpha)
}

/** Combo tail widths by count, state and density: a busy combo is laid out again on every hit. */
@kotlin.concurrent.Volatile
private var comboWidths: Map<String, Int> = emptyMap()

/** How wide a combo's tail is at rest, in dp, for its placeholder. */
internal fun comboWidthDp(measurer: TextMeasurer, density: Density, count: Int, ended: Boolean): Int {
    val key = "$count|$ended|${density.density}|${density.fontScale}"
    comboWidths[key]?.let { return it }
    val width = measureComboWidth(measurer, density, count, ended)
    comboWidths = (if (comboWidths.size > 500) emptyMap() else comboWidths) + (key to width)
    return width
}

private fun measureComboWidth(measurer: TextMeasurer, density: Density, count: Int, ended: Boolean): Int {
    val base = 14f
    val group = when { count >= 50 -> 5f; count >= 30 -> 4f; count >= 20 -> 3f; count >= 10 -> 2f; count >= 5 -> 1.5f; else -> 1.15f }
    fun w(text: String, size: Float, bold: Boolean = false, italic: Boolean = false, letters: Float = 0f) = measurer.measure(
        text,
        TextStyle(
            fontSize = androidx.compose.ui.unit.TextUnit(size, androidx.compose.ui.unit.TextUnitType.Sp), letterSpacing = androidx.compose.ui.unit.TextUnit(letters * size, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
            fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        ),
        softWrap = false, density = density,
    ).size.width / density.density
    val space = w(" ", base)
    val tail = if (ended) (if (count >= 10) base * 0.6f else 0f) + w("C-C-C-COMBO", base, bold = count >= 10) else w("HITS", base, italic = true, letters = 0.05f)
    return (base * 0.6f + w("$count", base * group, bold = true, letters = -0.05f) + space + w("X", base) + space + tail + 2f).toInt() + 1
}

/** A combo's tail at [now]: see [InlineItem.ComboItem]. */
private fun DrawScope.drawCombo(item: InlineItem.ComboItem, rect: androidx.compose.ui.geometry.Rect, now: Long, measurer: TextMeasurer, pictures: (String) -> Painter?) {
    val px = 14.dp.toPx()
    val settled = now == SETTLED_MS || ChatRendering.busy
    val ended = item.endedAt > 0
    val big = item.group >= 10
    // From 10 hits, a finished combo: the splat behind it, the whole tail greying out over 3.5 s (from 75% of it).
    var grey = 0f
    if (ended && big) {
        val t = if (settled) item.endedAt + InlineItem.ComboItem.GREYOUT_MS else now - item.endedAt
        grey = ((t.toFloat() / InlineItem.ComboItem.GREYOUT_MS - 0.75f) / 0.25f).coerceIn(0f, 1f)
    }
    val greyFilter = if (grey > 0f) io.github.strawwaffle.glorp.site.Filter(listOf(io.github.strawwaffle.glorp.site.Filter.Fn("grayscale", grey))).colorMatrix() else null
    if (greyFilter != null) drawContext.canvas.saveLayer(rect.inflate(px * 3), layer(ColorFilter.colorMatrix(ColorMatrix(greyFilter))))
    if (ended && big) {
        ChatRendering.comboSplat?.let(pictures)?.let { p ->
            translate(rect.left, rect.top) { with(p) { draw(Size(rect.width, rect.height)) } }
        }
    }
    val cy = rect.center.y
    var x = rect.left + px * 0.6f
    val count = comboPiece(measurer, "${item.count}", px * item.countScale, comboText, bold = true, letters = -0.05f)
    outlined(count, x, cy)
    x += count.size.width
    val space = comboPiece(measurer, " ", px, comboText).size.width
    x += space
    val cross = comboPiece(measurer, "X", px, comboText)
    outlined(cross, x, cy)
    x += cross.size.width + space
    if (!ended) {
        // "HITS": red and twice the size at a hit, flickering, shrinking to 120% and fading to light over 0.6 s.
        val t = now - item.lastHitAt
        if (!settled && t in 0 until InlineItem.ComboItem.HIT_MS) {
            val p = t.toFloat() / InlineItem.ComboItem.HIT_MS
            val (size, color) = when {
                p < 0.01f -> 2f to comboRed
                p < 0.02f -> 1.9f to comboLight
                p < 0.03f -> 2f to comboRed
                p < 0.04f -> 1.9f to comboLight
                else -> {
                    val f = (p - 0.04f) / 0.96f
                    (2f + (1.2f - 2f) * f) to androidx.compose.ui.graphics.lerp(comboRed, comboLight, f)
                }
            }
            // Laid out once at chat's size and scaled while drawing: re-measuring every frame of a busy combo costs too much.
            val hits = comboPiece(measurer, "HITS", px, comboLight, italic = true, letters = 0.05f)
            withTransform({ scale(size, size, Offset(x, cy)) }) { outlined(hits, x, cy, color = color) }
        } else {
            outlined(comboPiece(measurer, "HITS", px, comboLight, italic = true, letters = 0.05f), x, cy)
        }
    } else {
        // "C-C-C-COMBO" comes in from the left, overshoots in white, and settles.
        val t = if (settled) InlineItem.ComboItem.COMPLETE_MS else now - item.endedAt
        val p = (t.toFloat() / InlineItem.ComboItem.COMPLETE_MS).coerceIn(0f, 1f)
        val own = if (big) comboLight else comboDim
        val (dx, alpha, color) = when {
            p < 0.02f -> Triple(-10f + 20f * (p / 0.02f), p / 0.02f, androidx.compose.ui.graphics.lerp(own, androidx.compose.ui.graphics.Color.White, p / 0.02f))
            else -> Triple(10f * (1f - (p - 0.02f) / 0.98f), 1f, androidx.compose.ui.graphics.lerp(androidx.compose.ui.graphics.Color.White, own, (p - 0.02f) / 0.98f))
        }
        if (big) x += px * 0.6f
        outlined(comboPiece(measurer, "C-C-C-COMBO", px, own, bold = big), x + dx.dp.toPx(), cy, alpha = alpha, color = color)
    }
    if (greyFilter != null) drawContext.canvas.restore()
}

/**
 * A box drawn only by its `box-shadow`s (HACKERMAN's scan line): an inset
 * one is a band along the edge it's offset from, one around the box a glow.
 */
private fun DrawScope.drawBoxShadows(shadows: List<io.github.strawwaffle.glorp.site.BoxShadow>, w: Float, h: Float, scale: Float, alpha: Float) {
    for (s in shadows) {
        val color = androidx.compose.ui.graphics.Color(s.argb.toInt())
        if (color.alpha == 0f) continue
        if (s.inset) {
            val dy = s.dy * scale
            val dx = s.dx * scale
            if (dy > 0) drawRect(color, Offset(0f, 0f), Size(w, dy), alpha = alpha)
            if (dy < 0) drawRect(color, Offset(0f, h + dy), Size(w, -dy), alpha = alpha)
            if (dx > 0) drawRect(color, Offset(0f, 0f), Size(dx, h), alpha = alpha)
            if (dx < 0) drawRect(color, Offset(w + dx, 0f), Size(-dx, h), alpha = alpha)
        } else {
            val blur = (s.blur * scale).coerceAtLeast(1f)
            val steps = 5
            for (i in 1..steps) {
                val grow = blur * i / steps
                drawRect(
                    color, Offset(s.dx * scale - grow, s.dy * scale - grow), Size(w + 2 * grow, h + 2 * grow),
                    alpha = alpha * color.alpha * (1f - i.toFloat() / (steps + 1)) / steps,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = blur / steps),
                )
            }
        }
    }
}

/**
 * One emote on its own, drawn as chat draws it ([drawEmote]: strips,
 * movements, filters, overlays), playing from [startedAt]. For the emote card.
 */
@Composable
internal fun EmotePreview(emote: Emote, startedAt: Long, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val measurer = rememberTextMeasurer()
    val clock = remember(emote, startedAt) { mutableLongStateOf(startedAt) }
    LaunchedEffect(emote, startedAt) {
        while (true) {
            withFrameMillis { }
            val now = Clock.System.now().toEpochMilliseconds()
            clock.longValue = now
            if (now - startedAt >= emote.settlesAfterMs) break
            delay(16)
        }
    }
    androidx.compose.foundation.Canvas(modifier) {
        val now = clock.longValue
        val painter = ChatImages.get(emote.url, context) ?: return@Canvas
        // The emote's box, scaled to fit; its overlays may reach outside it.
        val scale = minOf(size.width / emote.width.dp.toPx(), size.height / emote.height.dp.toPx())
        val h = emote.height.dp.toPx() * scale
        val w = emote.width.dp.toPx() * scale
        drawEmote(emote, painter, (size.width - w) / 2, (size.height - h) / 2, h, (now - startedAt).coerceAtLeast(0), still = false, measurer) { url ->
            ChatImages.get(url, context)
        }
    }
}
