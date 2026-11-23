package io.github.strawwaffle.glorp.ui

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import coil3.Image
import coil3.PlatformContext
import coil3.asDrawable
import kotlin.math.roundToInt

internal actual fun playingPainter(image: Image, context: PlatformContext): Painter? {
    val drawable = image.asDrawable(context.resources)
    return if (drawable is Animatable) PlayingDrawable(drawable) else null
}

/**
 * A GIF shared by every line showing it. It plays while some line draws it,
 * and stops itself once none has for a second (scrolled away, the app in the
 * background), starting again from where it was on the next draw.
 */
private class PlayingDrawable(private val drawable: Drawable) : Painter(), Drawable.Callback {
    private val handler = Handler(Looper.getMainLooper())
    /** Bumped on each new frame; read while drawing, so the lines showing it redraw. */
    private var frame by mutableIntStateOf(0)
    private var drawnAt = 0L
    private var playing = false

    init {
        drawable.callback = this
    }

    override val intrinsicSize: Size
        get() = Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())

    override fun applyAlpha(alpha: Float): Boolean {
        drawable.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        drawable.colorFilter = colorFilter?.asAndroidColorFilter()
        return true
    }

    override fun DrawScope.onDraw() {
        frame
        drawnAt = SystemClock.uptimeMillis()
        if (!playing) {
            playing = true
            (drawable as Animatable).start()
        }
        drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }

    override fun invalidateDrawable(who: Drawable) {
        if (SystemClock.uptimeMillis() - drawnAt > 1_000) {
            playing = false
            (drawable as Animatable).stop()
            return
        }
        frame++
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
        handler.postAtTime(what, `when`)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        handler.removeCallbacks(what)
    }
}
