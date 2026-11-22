package io.github.strawwaffle.glorp.video

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Which AngelThump edge a stream is coming from. Starts on the one the
 * lookup timed fastest; moves to the nearest backup when that one fails or
 * keeps stalling.
 */
class EdgeChoice(@Volatile var current: String?) {
    /** Leaves [edge] for its nearest backup, if it's still the current one. */
    fun leave(edge: String) {
        if (current == edge) current = AngelThumpEdges.backups(edge).firstOrNull() ?: edge
    }
}

/**
 * Sends requests for AngelThump's edges to [choice]'s edge. When opening one
 * fails, the same request goes to the two nearest other edges before the
 * error reaches the player, as the edge picker userscript does with a stalled
 * segment; a read that fails halfway moves the next request on. Everything
 * else passes straight through.
 */
@OptIn(UnstableApi::class)
class EdgeFailoverDataSource(private val upstream: DataSource, private val choice: EdgeChoice) : DataSource {
    private var openedEdge: String? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host
        if (host == null || !AngelThumpEdges.isEdge(host)) {
            openedEdge = null
            return upstream.open(dataSpec)
        }
        val first = choice.current ?: host
        var failure: IOException? = null
        for (edge in listOf(first) + AngelThumpEdges.backups(first).take(2)) {
            try {
                val length = upstream.open(dataSpec.withUri(dataSpec.uri.onHost(edge)))
                openedEdge = edge
                if (edge != first) choice.current = edge
                return length
            } catch (e: IOException) {
                failure = e
                runCatching { upstream.close() }
            }
        }
        throw failure!!
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        upstream.read(buffer, offset, length)
    } catch (e: IOException) {
        openedEdge?.let(choice::leave)
        throw e
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        openedEdge = null
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val choice: EdgeChoice) : DataSource.Factory {
        override fun createDataSource(): DataSource = EdgeFailoverDataSource(upstream.createDataSource(), choice)
    }
}

private fun Uri.onHost(host: String): Uri = buildUpon().encodedAuthority(host).build()
