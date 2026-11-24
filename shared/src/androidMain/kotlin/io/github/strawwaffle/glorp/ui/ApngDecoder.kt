package io.github.strawwaffle.glorp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.ByteString.Companion.encodeUtf8
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * Animated PNGs, composed frame by frame as browsers do. Many emotes (Listening)
 * store each frame after the first as a small patch over the one before;
 * Android's own decoder shows just the patch. Each frame is decoded as a PNG
 * of its own, laid on a canvas the size of the picture by its blend and
 * dispose rules, and kept whole; they play as an [AvifFrames] drawable.
 */
class ApngDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult? {
        val bytes = source.source().use { it.readByteArray() }
        val frames = compose(bytes) ?: return null
        return DecodeResult(frames.asImage(shareable = false), isSampled = false)
    }

    private class Chunk(val type: String, val data: ByteArray)

    private class Frame(
        val w: Int, val h: Int, val x: Int, val y: Int, val delayMs: Long, val dispose: Int, val blend: Int,
        val data: ArrayList<ByteArray> = ArrayList(),
    )

    private fun chunks(bytes: ByteArray): List<Chunk> {
        val out = ArrayList<Chunk>()
        var i = 8
        val b = ByteBuffer.wrap(bytes)
        while (i + 8 <= bytes.size) {
            val len = b.getInt(i)
            if (len < 0 || i + 12 + len > bytes.size) break
            val type = String(bytes, i + 4, 4, Charsets.US_ASCII)
            out += Chunk(type, bytes.copyOfRange(i + 8, i + 8 + len))
            i += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    private fun compose(bytes: ByteArray): AvifFrames? {
        val all = chunks(bytes)
        val ihdr = all.firstOrNull { it.type == "IHDR" } ?: return null
        if (all.none { it.type == "acTL" }) return null
        val width = ByteBuffer.wrap(ihdr.data).getInt(0)
        val height = ByteBuffer.wrap(ihdr.data).getInt(4)
        // Chunks every frame needs before its data: the palette, transparency, colour space.
        val shared = all.filter { it.type in setOf("PLTE", "tRNS", "gAMA", "cHRM", "sRGB", "iCCP", "sBIT") }
        val frames = ArrayList<Frame>()
        var current: Frame? = null
        for (c in all) {
            when (c.type) {
                "fcTL" -> {
                    val d = ByteBuffer.wrap(c.data)
                    val num = d.getShort(20).toInt() and 0xFFFF
                    val den = (d.getShort(22).toInt() and 0xFFFF).let { if (it == 0) 100 else it }
                    current = Frame(d.getInt(4), d.getInt(8), d.getInt(12), d.getInt(16), (num * 1000L / den).coerceAtLeast(20), c.data[24].toInt(), c.data[25].toInt())
                    frames += current
                }
                // The default picture is the first frame only when an fcTL came before it.
                "IDAT" -> current?.data?.add(c.data)
                "fdAT" -> current?.data?.add(c.data.copyOfRange(4, c.data.size))
            }
        }
        if (frames.size <= 1 || frames.size.toLong() * width * height > 4_000_000L) return null
        val canvasBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBitmap)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val source = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        val over = Paint()
        val out = ArrayList<Bitmap>(frames.size)
        val delays = LongArray(frames.size)
        for ((k, f) in frames.withIndex()) {
            val patch = decodeFrame(ihdr, shared, f) ?: run { out.forEach { it.recycle() }; return null }
            val region = Rect(f.x, f.y, f.x + f.w, f.y + f.h)
            val saved = if (f.dispose == 2) Bitmap.createBitmap(canvasBitmap, 0, 0, width, height) else null
            canvas.drawBitmap(patch, null, region, if (f.blend == 0) source else over)
            patch.recycle()
            out += canvasBitmap.copy(Bitmap.Config.ARGB_8888, false)
            delays[k] = f.delayMs
            when (f.dispose) {
                1 -> canvas.drawRect(region, clear)
                2 -> saved?.let { canvas.drawBitmap(it, 0f, 0f, source); it.recycle() }
            }
        }
        canvasBitmap.recycle()
        return AvifFrames(out, delays)
    }

    /** One frame's data as a PNG of its own size. */
    private fun decodeFrame(ihdr: Chunk, shared: List<Chunk>, f: Frame): Bitmap? {
        val png = ByteArrayOutputStream()
        png.write(SIGNATURE)
        val header = ihdr.data.copyOf()
        ByteBuffer.wrap(header).putInt(0, f.w).putInt(4, f.h)
        writeChunk(png, "IHDR", header)
        for (c in shared) writeChunk(png, c.type, c.data)
        for (d in f.data) writeChunk(png, "IDAT", d)
        writeChunk(png, "IEND", ByteArray(0))
        val bytes = png.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val peek = result.source.source().peek()
            peek.request(4096)
            val head = peek.buffer.snapshot().let { it.substring(0, minOf(it.size, 4096)) }
            if (head.size < 8 || head.substring(0, 8).toByteArray().contentEquals(SIGNATURE).not()) return null
            // An animated one has its acTL before its first IDAT; a still PNG is left to the platform.
            val actl = head.indexOf("acTL".encodeUtf8())
            val idat = head.indexOf("IDAT".encodeUtf8())
            return if (actl >= 0 && (idat < 0 || actl < idat)) ApngDecoder(result.source) else null
        }
    }

    internal companion object {
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}

