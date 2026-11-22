package io.github.strawwaffle.glorp.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * Requests for a YouTube recording's files, made the way the Grayjay
 * plugin's request modifier makes them: the byte range goes in the address
 * (`&range=start-end`) rather than a Range header, each request is numbered
 * (`&rn=`), and the plugin's headers (the User-Agent of the client the
 * addresses were issued to) go with it. Only `googlevideo.com/videoplayback`
 * requests change; anything else passes straight through.
 */
@OptIn(UnstableApi::class)
class YouTubeRangesDataSource(
    private val upstream: DataSource,
    private val headers: Map<String, String>,
    private val requests: AtomicInteger,
) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        if (uri.host?.endsWith(".googlevideo.com") != true || uri.path?.startsWith("/videoplayback") != true) {
            return upstream.open(dataSpec)
        }
        val rewritten = rewrite(dataSpec)
        if (Playback.logLive) {
            android.util.Log.d("GlorpYouTube", "${rewritten.uri.getQueryParameter("itag")} range=${rewritten.uri.getQueryParameter("range")} rn=${rewritten.uri.getQueryParameter("rn")} headers=${rewritten.httpRequestHeaders.keys} from ${dataSpec.position}+${dataSpec.length}")
        }
        return try {
            upstream.open(rewritten)
        } catch (e: java.io.IOException) {
            if (Playback.logLive) android.util.Log.d("GlorpYouTube", "failed: $e ${rewritten.uri.toString().take(400)}")
            throw e
        }
    }

    private fun rewrite(spec: DataSpec): DataSpec {
        val uri = spec.uri
        val builder = uri.buildUpon()
        if (uri.getQueryParameter("range") == null) {
            val start = spec.position
            val end = when {
                spec.length != C.LENGTH_UNSET.toLong() -> start + spec.length - 1
                else -> uri.getQueryParameter("clen")?.toLongOrNull()?.minus(1)
            }
            builder.appendQueryParameter("range", if (end != null) "$start-$end" else "$start-")
        }
        if (uri.getQueryParameter("rn") == null) builder.appendQueryParameter("rn", requests.getAndIncrement().toString())
        // The range is in the address now: ask for the whole of what it names.
        return spec.buildUpon()
            .setUri(builder.build())
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpRequestHeaders(spec.httpRequestHeaders + headers)
            .build()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(private val upstream: DataSource.Factory, private val headers: Map<String, String>) : DataSource.Factory {
        private val requests = AtomicInteger(0)
        override fun createDataSource(): DataSource = YouTubeRangesDataSource(upstream.createDataSource(), headers, requests)
    }
}
