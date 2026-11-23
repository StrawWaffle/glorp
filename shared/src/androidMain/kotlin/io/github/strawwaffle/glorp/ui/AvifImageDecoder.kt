package io.github.strawwaffle.glorp.ui

import android.graphics.Bitmap
import coil3.ImageLoader
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
 * Decodes AVIF pictures (the first frame of an animated one) with libavif.
 * Emotes are small, so the whole picture is decoded at its own size.
 */
class AvifImageDecoder(private val source: ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        buffer.flip()
        val info = AvifDecoder.Info()
        check(AvifDecoder.getInfo(buffer, bytes.size, info) && info.width > 0 && info.height > 0) { "Not an AVIF picture libavif can read" }
        val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        if (!AvifDecoder.decode(buffer, bytes.size, bitmap)) {
            bitmap.recycle()
            error("libavif couldn't decode the picture")
        }
        return DecodeResult(bitmap.asImage(), isSampled = false)
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
