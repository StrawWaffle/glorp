package io.github.strawwaffle.glorp.video

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.SlidingPercentile
import java.io.IOException

/**
 * How fast the network is, for live streams read at the live edge.
 *
 * Kick and Twitch segments are fetched while they're still being made (see
 * lowLatencyPlaylist), so a 2 s segment takes about 2 s to arrive however
 * fast the network is. Media3's own meter takes that as the network's speed,
 * reads the stream's own bitrate back, and Auto steps down until it reaches
 * the lowest quality.
 *
 * Here a download counts only when it says something about the network:
 * much quicker than the stretch of video it holds, or slower than real time.
 * One that kept pace with the video was waiting on the stream and is left
 * out, as is one that was retried (its time includes the wait between tries).
 *
 * At the edge nearly every download keeps pace, so after a slow patch the
 * estimate would stay low for good. While downloads keep pace and nothing
 * has been slow for a while, it rises a step now and then, letting Auto try
 * the next quality up; if the network can't carry it, the slow downloads
 * bring it back down.
 */
@OptIn(UnstableApi::class)
class LiveBandwidthMeter(context: Context) : BandwidthMeter, AnalyticsListener {
    // Main thread only: the player's listeners are called there.
    private val samples = SlidingPercentile(2000)
    private val retried = HashSet<Long>()
    private var lastSample = SystemClock.elapsedRealtime()
    private var lastSlow = 0L

    // Read on the player's own thread.
    @Volatile
    private var estimate = DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
    private val listeners = BandwidthMeter.EventListener.EventDispatcher()

    override fun getBitrateEstimate(): Long = estimate

    // Fed from finished loads instead of byte by byte (see onLoadCompleted).
    override fun getTransferListener(): TransferListener? = null

    override fun addEventListener(eventHandler: Handler, eventListener: BandwidthMeter.EventListener) =
        listeners.addListener(eventHandler, eventListener)

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) =
        listeners.removeListener(eventListener)

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        if (retried.size > 100) retried.clear()
        retried += loadEventInfo.loadTaskId
    }

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (retried.remove(loadEventInfo.loadTaskId)) return
        if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
        val bytes = loadEventInfo.bytesLoaded
        val loadMs = loadEventInfo.loadDurationMs
        val mediaMs = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
        if (bytes <= 0 || loadMs <= 0 || mediaLoadData.mediaStartTimeMs == C.TIME_UNSET || mediaMs <= 0) return
        val now = SystemClock.elapsedRealtime()
        when (speedOf(loadMs, mediaMs)) {
            Speed.KeptPace -> {
                if (now - lastSample < TRY_HIGHER_AFTER_MS || now - lastSlow < TRY_HIGHER_AFTER_SLOW_MS) return
                // A fresh start from one step up, so older samples don't pull it back.
                // At most 4 times the stream playing, so it doesn't climb forever at the top quality.
                val playing = bytes * 8_000 / mediaMs
                val higher = minOf(estimate * 3 / 2, maxOf(estimate, playing * 4))
                if (higher <= estimate) return
                samples.reset()
                add(now, bytes, higher)
            }
            Speed.Slow -> {
                lastSlow = now
                add(now, bytes, bytes * 8_000 / loadMs)
            }
            Speed.Fast -> add(now, bytes, bytes * 8_000 / loadMs)
        }
    }

    private fun add(now: Long, bytes: Long, bitrate: Long) {
        samples.addSample(kotlin.math.sqrt(bytes.toDouble()).toInt(), bitrate.toFloat())
        estimate = samples.getPercentile(0.5f).toLong()
        listeners.bandwidthSample((now - lastSample).toInt(), bytes, estimate)
        lastSample = now
    }

    enum class Speed { Fast, KeptPace, Slow }

    companion object {
        private const val TRY_HIGHER_AFTER_MS = 20_000L
        private const val TRY_HIGHER_AFTER_SLOW_MS = 60_000L

        /** A download that kept pace with its video was held back by the stream, not the network. */
        fun speedOf(loadMs: Long, mediaMs: Long): Speed = when {
            loadMs * 4 < mediaMs -> Speed.Fast
            loadMs * 10 > mediaMs * 11 -> Speed.Slow
            else -> Speed.KeptPace
        }
    }
}
