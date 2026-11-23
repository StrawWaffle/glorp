package io.github.strawwaffle.glorp.video

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

/**
 * Auto quality, Media3's own, with two changes:
 *
 * - [minDurationForQualityIncreaseMs]: live streams a few seconds from live
 *   never have the 10 s of buffer Media3 wants before stepping up.
 * - [lowestOnly]: with sound only, video takes the smallest stream. Where
 *   sound and video come together (Kick, Twitch, AngelThump) that's what gets
 *   downloaded, and the step down happens at the next segment like any other,
 *   with nothing thrown away. Audio of its own (YouTube) keeps its quality.
 */
@OptIn(UnstableApi::class)
class QualitySelection(private val minDurationForQualityIncreaseMs: Int) :
    AdaptiveTrackSelection.Factory(
        minDurationForQualityIncreaseMs,
        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION,
    ) {

    override fun createAdaptiveTrackSelection(
        group: TrackGroup,
        tracks: IntArray,
        type: Int,
        bandwidthMeter: BandwidthMeter,
        adaptationCheckpoints: ImmutableList<AdaptiveTrackSelection.AdaptationCheckpoint>,
    ): AdaptiveTrackSelection = Selection(group, tracks, type, bandwidthMeter, adaptationCheckpoints)

    private inner class Selection(
        group: TrackGroup,
        tracks: IntArray,
        type: Int,
        bandwidthMeter: BandwidthMeter,
        adaptationCheckpoints: List<AdaptationCheckpoint>,
    ) : AdaptiveTrackSelection(
        group, tracks, type, bandwidthMeter,
        minDurationForQualityIncreaseMs.toLong(),
        AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS.toLong(),
        AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS.toLong(),
        AdaptiveTrackSelection.DEFAULT_MAX_WIDTH_TO_DISCARD,
        AdaptiveTrackSelection.DEFAULT_MAX_HEIGHT_TO_DISCARD,
        AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION,
        AdaptiveTrackSelection.DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
        adaptationCheckpoints,
        Clock.DEFAULT,
    ) {
        // Media3 doesn't step down while 25 s are buffered, as a recording's
        // usually are; with sound only it should, so it's told nothing is.
        override fun updateSelectedTrack(
            playbackPositionUs: Long,
            bufferedDurationUs: Long,
            availableDurationUs: Long,
            queue: List<androidx.media3.exoplayer.source.chunk.MediaChunk>,
            mediaChunkIterators: Array<out androidx.media3.exoplayer.source.chunk.MediaChunkIterator>,
        ) = super.updateSelectedTrack(
            playbackPositionUs, if (lowestOnly) 0 else bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators,
        )

        // Turning every track down leaves Media3 on the smallest one.
        override fun canSelectFormat(format: Format, trackBitrate: Int, effectiveBitrate: Long): Boolean =
            !(lowestOnly && format.isVideo()) && super.canSelectFormat(format, trackBitrate, effectiveBitrate)
    }

    companion object {
        /** Sound only: read on the player's own thread as each segment is picked. */
        @Volatile
        var lowestOnly = false

        private fun Format.isVideo() =
            height != Format.NO_VALUE || MimeTypes.isVideo(sampleMimeType) || MimeTypes.isVideo(containerMimeType)
    }
}
