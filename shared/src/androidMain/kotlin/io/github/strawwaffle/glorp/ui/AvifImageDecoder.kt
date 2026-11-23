package io.github.strawwaffle.glorp.ui

import android.graphics.Bitmap
import coil3.ImageLoader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.ByteString.Companion.encodeUtf8
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer

/**
 * Decodes AVIF pictures with libavif. Emotes are small, so the whole picture
 * is decoded at its own size; an animated one (SHROOMSTINY) has every frame
 * decoded up front and plays as an [AvifFrames] drawable.
 */
class AvifImageDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.flip()
        val info = AvifDecoder.Info()
        check(AvifDecoder.getInfo(buffer, bytes.size, info) && info.width > 0 && info.height > 0) { "Not an AVIF picture libavif can read" }
        // A huge picture linked in chat would take hundreds of megabytes, or
        // be too large to draw at all: not shown, rather than crash the app.
        check(info.width.toLong() * info.height <= MAX_PIXELS) { "AVIF picture too large to show (${info.width}×${info.height})" }
        frames(buffer, info.width, info.height)?.let { return DecodeResult(it.asImage(shareable = false), isSampled = false) }
        val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        if (!AvifDecoder.decode(buffer, bytes.size, bitmap)) {
            bitmap.recycle()
            error("libavif couldn't decode the picture")
        }
        return DecodeResult(bitmap.asImage(), isSampled = false)
    }

    /** Every frame of an animated picture, or null for a still one (or one too big to hold whole). */
    private fun frames(buffer: ByteBuffer, width: Int, height: Int): AvifFrames? {
        val decoder = AvifDecoder.create(buffer) ?: return null
        try {
            val count = decoder.frameCount
            if (count <= 1 || count.toLong() * width * height > MAX_FRAME_PIXELS) return null
            val durations = decoder.frameDurations
            val bitmaps = ArrayList<Bitmap>(count)
            for (i in 0 until count) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                if (decoder.nextFrame(bitmap) != 0) { bitmap.recycle(); bitmaps.forEach { it.recycle() }; return null }
                bitmaps += bitmap
            }
            val ms = LongArray(count) { ((durations.getOrNull(it) ?: 0.1) * 1000).toLong().coerceAtLeast(20) }
            return AvifFrames(bitmaps, ms)
        } finally {
            decoder.release()
        }
    }

    private companion object {
        // 4K: about 33 MB once decoded.
        const val MAX_PIXELS = 3840L * 2160
        // All the frames of an animation together: about 16 MB.
        const val MAX_FRAME_PIXELS = 4_000_000L
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (isAvif(result)) AvifImageDecoder(result.source) else null

        private fun isAvif(result: SourceFetchResult): Boolean {
            if (result.mimeType == "image/avif") return true
            // An ISO media file whose brand is AVIF: `....ftypavif` or `....ftypavis`.
            val peek = result.source.source().peek()
            return peek.rangeEquals(4, FTYP) && (peek.rangeEquals(8, AVIF) || peek.rangeEquals(8, AVIS))
        }

        private companion object {
            val FTYP = "ftyp".encodeUtf8()
            val AVIF = "avif".encodeUtf8()
            val AVIS = "avis".encodeUtf8()
        }
    }
}

/** An animated AVIF's frames, played in a loop for as long as it's started. */
class AvifFrames(private val frames: List<Bitmap>, private val durationsMs: LongArray) : Drawable(), Animatable {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var index = 0
    private var running = false
    private val advance = Runnable {
        index = (index + 1) % frames.size
        invalidateSelf()
        scheduleNext()
    }

    private fun scheduleNext() {
        if (running) scheduleSelf(advance, SystemClock.uptimeMillis() + durationsMs[index])
    }

    override fun draw(canvas: Canvas) {
        canvas.drawBitmap(frames[index], null, bounds, paint)
    }

    override fun start() {
        if (running) return
        running = true
        scheduleNext()
    }

    override fun stop() {
        running = false
        unscheduleSelf(advance)
    }

    override fun isRunning() = running
    override fun getIntrinsicWidth() = frames[0].width
    override fun getIntrinsicHeight() = frames[0].height
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
