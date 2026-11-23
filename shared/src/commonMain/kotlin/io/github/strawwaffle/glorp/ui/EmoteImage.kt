package io.github.strawwaffle.glorp.ui

import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.Pose
import kotlin.time.Clock

/**
 * An emote at chat's size. Its sprite strip and its hand-made movements play
 * for as long as the stylesheet would run them, counted from when the
 * message was sent, then rest: lines from the backlog, or scrolled back to,
 * don't replay, and nothing keeps ticking once they're over. [sentAt] 0
 * (the emote picker) shows it still.
 */
@Composable
fun EmoteImage(emote: Emote, sentAt: Long, modifier: Modifier = Modifier) {
    if (emote.isWholePicture) {
        AsyncImage(
            model = emote.url,
            contentDescription = emote.prefix,
            modifier = modifier.size(emote.width.dp, emote.height.dp).clipToBounds(),
        )
        return
    }
    SheetImage(emote, sentAt, modifier)
}

/** Time far past any finite animation: everything shows how it rests. */
private const val SETTLED = Long.MAX_VALUE / 4

/**
 * A window onto a bigger picture (one frame or variant of a strip, placed by
 * the stylesheet's background size and position), stepped through and moved
 * as the stylesheet animates it.
 */
@Composable
private fun SheetImage(emote: Emote, sentAt: Long, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(emote.url) {
        ImageRequest.Builder(context).data(emote.url).size(CoilSize.ORIGINAL).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val sprite = emote.sprite
    // How long since the message was sent, as of this frame. Read only while
    // drawing, so a new frame redraws this emote without recomposing or
    // re-laying out the line around it.
    val elapsed = remember(emote, sentAt) { mutableLongStateOf(SETTLED) }
    // chat-gui's "disable animations" (`animation: none`): the first frame, and only the still transform.
    val still = !LocalChatLook.current.animations

    if (sentAt > 0 && !still) {
        LaunchedEffect(emote, sentAt) {
            val ends = emote.settlesAfterMs
            val elapsedAtStart = Clock.System.now().toEpochMilliseconds() - sentAt
            if (elapsedAtStart >= ends && emote.motions.none { it.iterations == Int.MAX_VALUE }) return@LaunchedEffect
            val firstFrame = withFrameMillis { it }
            // A strip with nothing else moving only needs a redraw when its
            // frame changes: sleep to the next frame's start instead of
            // redrawing on every vsync. withFrameMillis still holds it while
            // the app is off screen.
            val step = sprite?.takeIf { emote.motions.isEmpty() && it.frames > 0 }?.let { (it.durationMs / it.frames).coerceAtLeast(16) }
            while (true) {
                val now = withFrameMillis { it } - firstFrame + elapsedAtStart
                if (now >= ends) break
                elapsed.longValue = now
                if (step != null) delay((step - now % step).coerceAtLeast(1))
                // Moving: about 60 frames a second at most, which looks the
                // same and halves the drawing on a 120 Hz screen.
                else delay(8)
            }
            elapsed.longValue = SETTLED
        }
    }

    val moving = emote.motions.isNotEmpty() || emote.rest != Pose.Rest
    val layer = if (!moving) Modifier else Modifier.graphicsLayer {
        val pose = if (still) emote.rest else pose(emote, elapsed.longValue)
        translationX = pose.tx.dp.toPx()
        translationY = pose.ty.dp.toPx()
        rotationZ = pose.rotate
        scaleX = pose.sx
        scaleY = pose.sy
        alpha = pose.opacity.coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(emote.originX, emote.originY)
    }
    Canvas(modifier.then(layer).size(emote.width.dp, emote.height.dp).clipToBounds()) {
        val at = if (still) 0L else elapsed.longValue
        val frame = when {
            sprite == null -> 0
            at < sprite.totalMs -> ((at % sprite.durationMs) * sprite.frames / sprite.durationMs).toInt()
            sprite.holdLast -> minOf(sprite.frames, sprite.sheetWidth / emote.width - 1)
            else -> 0
        }
        val now = if (moving) pose(emote, at) else Pose.Rest
        val brightness = now.brightness
        val filter = if (brightness == 1f) null
        else ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
        val scale = size.height / emote.height
        // While the strip plays, its keyframes place it (from their start);
        // at rest, the emote's own background-position does.
        val playing = sprite != null && at < sprite.totalMs
        val base = if (playing) sprite?.startX ?: emote.offsetX else emote.offsetX
        val offset = (base + frame * emote.width) * scale
        clipRect {
            // A picture slid inside its box (GIGACHAD arriving) as well.
            translate(left = -offset + now.bx * scale, top = now.by * scale) {
                with(painter) { draw(Size(emote.sheetWidth * scale, emote.sheetHeight * scale), colorFilter = filter) }
            }
        }
    }
}

private fun pose(emote: Emote, elapsedMs: Long): Pose =
    emote.motions.fold(emote.rest) { pose, motion -> motion.apply(elapsedMs, pose) }
