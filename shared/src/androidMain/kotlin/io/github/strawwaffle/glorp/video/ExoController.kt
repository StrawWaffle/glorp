package io.github.strawwaffle.glorp.video

import io.github.strawwaffle.glorp.diag.AppLog
import androidx.annotation.OptIn
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/** [VideoController] over an ExoPlayer. Call [release] instead of releasing the player. */
@OptIn(UnstableApi::class)
class ExoController(private val player: ExoPlayer) : VideoController {
    private val _state = MutableStateFlow(VideoState())
    override val state: StateFlow<VideoState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE) }
            if (playbackState == Player.STATE_READY) {
                // Retries count again only once it has played a while: a stream
                // that keeps failing right after starting gives up in the end.
                handler.removeCallbacks(steady)
                handler.postDelayed(steady, 30_000)
                _state.update { it.copy(failed = false) }
            } else {
                handler.removeCallbacks(steady)
            }
            // A live stream that ended (its playlist closed): as good as gone,
            // so it's looked up again and shows as offline.
            if (playbackState == Player.STATE_ENDED && wasLive) fail()
            watchLoading()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = watchLoading()

        override fun onPlayerError(error: PlaybackException) {
            handler.removeCallbacks(retry)
            AppLog.note("player", "${error.errorCodeName}, retries $retries", error)
            // A live stream fallen too far behind: jump back to live.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // A dropped connection may come straight back: try the same
            // address a couple of times. Refused outright (an expired
            // address, a stream that ended), or still failing, it needs
            // looking up again, which the screen does.
            val refused = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode in 400..499
            if (!refused && retries < IN_PLACE_RETRIES) {
                retries++
                retrying = true
                handler.postDelayed(retry, 2_000L * retries)
            } else {
                fail()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.update { it.copy(qualities = qualities(tracks), live = player.isCurrentMediaItemLive) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(current = playingQuality()) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (player.isCurrentMediaItemLive) wasLive = true
            _state.update { it.copy(live = player.isCurrentMediaItemLive) }
        }
    }

    init {
        player.addListener(listener)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0

    // Was live: it no longer says so once its playlist has closed.
    private var wasLive = false

    /** Between a dropped connection and trying it again: not playing, but meaning to. */
    var retrying = false
        private set

    private val steady = Runnable { retries = 0 }

    private val retry = Runnable {
        retrying = false
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    /** Loading for too long while meant to be playing: as good as failed. */
    private val stuck = Runnable { fail() }

    private fun watchLoading() {
        handler.removeCallbacks(stuck)
        if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
            handler.postDelayed(stuck, STUCK_AFTER_MS)
        }
    }

    private fun fail() {
        handler.removeCallbacks(stuck)
        handler.removeCallbacks(retry)
        retrying = false
        // Nothing more to fetch until the screen decides what to do.
        player.stop()
        _state.update { it.copy(failed = true, playing = false) }
    }

    override fun restart() {
        handler.removeCallbacks(retry)
        retrying = false
        retries = 0
        _state.update { it.copy(failed = false) }
        player.stop()
        if (player.isCurrentMediaItemLive || player.isCurrentMediaItemDynamic) player.seekToDefaultPosition()
        player.playWhenReady = true
        player.prepare()
    }

    override fun play() {
        // Resuming a live stream picks up where it paused; the LIVE mark shows
        // how far behind that is, and one tap goes back.
        if (_state.value.failed) return restart()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()

    override fun goLive() {
        player.seekToDefaultPosition()
        player.play()
        refresh()
    }

    override fun choose(quality: Quality?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (quality != null) {
            val (group, track) = quality.id.split(':').map(String::toInt)
            val tracks = player.currentTracks.groups.getOrNull(group) ?: return
            builder.setOverrideForType(TrackSelectionOverride(tracks.mediaTrackGroup, track))
        }
        player.trackSelectionParameters = builder.build()
        _state.update { it.copy(chosen = quality) }
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs)
        refresh()
    }

    override fun refresh() {
        // How far the playhead is behind where the player joins a live stream,
        // measured in the stream's own time. (`currentLiveOffset` compares the
        // stream's timestamps with the phone's clock, and is off by however
        // much the clock is.)
        val live = player.isCurrentMediaItemLive
        var atEdge = true
        if (live && !player.currentTimeline.isEmpty) {
            player.currentTimeline.getWindow(player.currentMediaItemIndex, window)
            atEdge = player.currentPosition >= window.defaultPositionMs - LIVE_EDGE_MS
        }
        // For a live stream this is how far back its playlist reaches; for a
        // recording, its length.
        val duration = player.duration.takeIf { it != C.TIME_UNSET && player.isCurrentMediaItemSeekable } ?: 0
        _state.update { it.copy(atLiveEdge = atEdge, live = live, positionMs = player.currentPosition, durationMs = duration, catchingUp = catchingUp(live)) }
    }

    private val window = androidx.media3.common.Timeline.Window()

    private var lastOffsetMs = C.TIME_UNSET
    private var lastOffsetAt = 0L

    /**
     * Whether the live offset is shrinking faster than time passes: Media3
     * speeds a live stream up without reporting it as the player's speed,
     * so it's read off the offset between two refreshes.
     */
    private fun catchingUp(live: Boolean): Boolean {
        val offset = if (live && player.isPlaying) player.currentLiveOffset else C.TIME_UNSET
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastOffsetMs
        val since = now - lastOffsetAt
        lastOffsetMs = offset
        lastOffsetAt = now
        if (offset == C.TIME_UNSET || previous == C.TIME_UNSET || since !in 500..3_000) return false
        // Gaining more than a tenth of a second each second on live.
        return (previous - offset) * 10 > since
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        player.removeListener(listener)
        player.release()
    }

    private fun qualities(tracks: Tracks): List<Quality> =
        tracks.groups.withIndex()
            .filter { (_, group) -> group.type == C.TRACK_TYPE_VIDEO }
            .flatMap { (g, group) ->
                (0 until group.length).filter(group::isTrackSupported).mapNotNull { t ->
                    val format = group.getTrackFormat(t)
                    if (format.height <= 0) null
                    else Quality("$g:$t", format.height, format.frameRate.takeIf { it > 0 }?.roundToInt())
                }
            }
            .distinctBy { it.label }
            .sortedWith(compareByDescending<Quality> { it.height }.thenByDescending { it.fps ?: 0 })

    private fun playingQuality(): Quality? {
        val format = player.videoFormat ?: return null
        if (format.height <= 0) return null
        return Quality("", format.height, format.frameRate.takeIf { it > 0 }?.roundToInt())
    }

    private companion object {
        // A few seconds of slack: buffering stalls put a stream a little behind
        // without it being worth a "go live".
        const val LIVE_EDGE_MS = 8_000L

        /** Tries of the same address after an error, before looking the stream up again. */
        const val IN_PLACE_RETRIES = 2

        /** Loading this long without playing counts as failed. */
        const val STUCK_AFTER_MS = 25_000L
    }
}
