package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.Sprite
import kotlin.time.Clock

/**
 * An emote at chat's size. A sprite strip plays for as long as the stylesheet
 * would run it, counted from when the message was sent, then rests on its
 * final frame: lines from the backlog, or scrolled back to, don't replay, and
 * nothing keeps ticking once the animation is over.
 */
@Composable
fun EmoteImage(emote: Emote, sentAt: Long, modifier: Modifier = Modifier) {
    val sprite = emote.sprite
    if (sprite == null) {
        AsyncImage(
            model = emote.url,
            contentDescription = emote.prefix,
            modifier = modifier.size(emote.width.dp, emote.height.dp),
        )
        return
    }
    SpriteImage(emote, sprite, sentAt, modifier)
}

@Composable
private fun SpriteImage(emote: Emote, sprite: Sprite, sentAt: Long, modifier: Modifier) {
    val context = LocalPlatformContext.current
    val request = remember(emote.url) {
        ImageRequest.Builder(context).data(emote.url).size(CoilSize.ORIGINAL).build()
    }
    val painter = rememberAsyncImagePainter(request)
    val finalFrame = if (sprite.holdLast) minOf(sprite.frames, sprite.sheetWidth / emote.width - 1) else 0
    val frame = remember(emote, sentAt) { mutableIntStateOf(finalFrame) }

    LaunchedEffect(emote, sentAt) {
        val startedAt = Clock.System.now().toEpochMilliseconds()
        val elapsedAtStart = startedAt - sentAt
        if (elapsedAtStart >= sprite.totalMs) return@LaunchedEffect
        val firstFrame = withFrameMillis { it }
        while (true) {
            val elapsed = withFrameMillis { it } - firstFrame + elapsedAtStart
            if (elapsed >= sprite.totalMs) break
            frame.intValue = ((elapsed % sprite.durationMs) * sprite.frames / sprite.durationMs).toInt()
        }
        frame.intValue = finalFrame
    }

    // The frame is read only while drawing, so a new frame redraws this canvas
    // without recomposing the line around it.
    Canvas(modifier.size(emote.width.dp, emote.height.dp)) {
        val scale = size.height / emote.height
        val offset = frame.intValue * emote.width * scale
        clipRect {
            translate(left = -offset) {
                with(painter) { draw(Size(sprite.sheetWidth * scale, size.height)) }
            }
        }
    }
}
