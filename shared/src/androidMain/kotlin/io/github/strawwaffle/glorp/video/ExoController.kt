package io.github.strawwaffle.glorp.video

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
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
        }

        override fun onTracksChanged(tracks: Tracks) {
            _state.update { it.copy(qualities = qualities(tracks), live = player.isCurrentMediaItemLive) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(current = playingQuality()) }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            _state.update { it.copy(live = player.isCurrentMediaItemLive) }
        }
    }

    init {
        player.addListener(listener)
    }

    override fun play() {
        // Resuming a live stream picks up where it paused; the LIVE mark shows
        // how far behind that is, and one tap goes back.
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
        _state.update { it.copy(atLiveEdge = atEdge, live = live) }
    }

    private val window = androidx.media3.common.Timeline.Window()

    fun release() {
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
    }
}
