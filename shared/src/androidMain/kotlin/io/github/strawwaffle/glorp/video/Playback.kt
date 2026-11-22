package io.github.strawwaffle.glorp.video

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LivePlaybackSpeedControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * The one native player, owned here rather than by a screen, so it can keep
 * playing sound when the screen goes off and be picked up again, without
 * buffering, when the app comes back.
 *
 * A screen [attach]es to play a stream and [detach]es when it leaves. Kept
 * playing in the background, the player lives in [PlaybackService], which
 * shows the media notification; otherwise it is released at once, so no
 * decoder or network is held for nothing.
 */
@OptIn(UnstableApi::class)
object Playback {
    class Active(val url: String, val player: ExoPlayer, val controller: ExoController, val session: MediaSession, val edges: EdgeChoice)

    var active: Active? = null
        private set

    /**
     * Dev builds: once a second, how far behind live the player is, how much
     * is buffered and how fast it plays, to logcat under `GlorpLive`, for
     * watching catch-up on real streams.
     */
    var logLive = false
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val logTick = object : Runnable {
        override fun run() {
            val current = active ?: return
            val player = current.player
            if (player.isCurrentMediaItemLive) {
                android.util.Log.d(
                    "GlorpLive",
                    "offset=${player.currentLiveOffset}ms buffered=${player.totalBufferedDuration}ms " +
                        "state=${player.playbackState} playing=${player.isPlaying}" +
                        (current.edges.current?.let { " edge=$it" } ?: ""),
                )
            }
            ticker.postDelayed(this, 1_000)
        }
    }

    /** The player for [playable]: the one already playing it, or a new one. */
    /**
     * The fastest a live stream may play to catch up, from the menu's
     * setting; 1 turns catching up off. Read by the player as it plays, so a
     * change applies at once.
     */
    @Volatile
    var catchUpMax = 1.5f

    fun attach(context: Context, playable: Playable, background: Boolean): Active {
        active?.let { if (it.url == playable.hlsUrl) return it.also { start(context, background) } }
        release(context)
        val app = context.applicationContext
        // AngelThump streams come from one of several edges; requests go to
        // the chosen one and fall over to its neighbours (see EdgeFailover).
        val edges = EdgeChoice(AngelThumpEdges.hostOf(playable.hlsUrl)?.takeIf(AngelThumpEdges::isEdge))
        val player = ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(EdgeFailoverDataSource.Factory(DefaultDataSource.Factory(app), edges)))
            // Pauses for calls and other apps' sound, as a video app should.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setLivePlaybackSpeedControl(catchUpControl())
            // Keeps the network up while the screen is off, but only while playing.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Auto picks nothing above what the screen can show: a 1080p60 stream
        // on a small view costs decode work and data for nothing. A quality
        // picked by hand overrides this.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setViewportSizeToPhysicalDisplaySize(app, true)
            .build()
        player.addListener(object : Player.Listener {
            private val stalls = ArrayDeque<Long>()
            private var wasReady = false

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) wasReady = true
                val edge = edges.current ?: return
                if (state != Player.STATE_BUFFERING || !wasReady || !player.playWhenReady) return
                // Three stalls within a minute on one edge: try the next.
                val now = android.os.SystemClock.elapsedRealtime()
                stalls.addLast(now)
                while (stalls.isNotEmpty() && now - stalls.first() > 60_000) stalls.removeFirst()
                if (stalls.size >= 3) {
                    stalls.clear()
                    edges.leave(edge)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A live stream fallen too far behind: jump back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
        })
        val item = MediaItem.Builder().setUri(playable.hlsUrl)
            .setMimeType(if (playable.mp4) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setMaxPlaybackSpeed(FASTEST_CATCH_UP)
                    .setMinPlaybackSpeed(0.97f)
                    .build(),
            )
            .build()
        val start = playable.startSeconds
        if (start != null && !playable.live) player.setMediaItem(item, start * 1000L) else player.setMediaItem(item)
        player.playWhenReady = true
        player.prepare()
        // The session gives the picture-in-picture window, headset buttons and
        // the notification play and pause.
        val session = MediaSession.Builder(app, player).setId("glorp").build()
        if (logLive) { ticker.removeCallbacks(logTick); ticker.postDelayed(logTick, 1_000) }
        return Active(playable.hlsUrl, player, ExoController(player), session, edges).also {
            active = it
            start(context, background)
        }
    }

    /**
     * The screen showing the player has gone. With [keepPlaying] (the app was
     * left while playing and background sound is on) the service carries on;
     * otherwise everything is released.
     */
    fun detach(context: Context, keepPlaying: Boolean) {
        val current = active ?: return
        if (keepPlaying && current.player.isPlaying) return
        release(context)
    }

    /**
     * Catching up with a live stream that's fallen behind (after a pause or a
     * stall): Media3's own control, tuned. Speed follows how far behind the
     * target it is (the setting's speed from 5 s, less as it closes; gentler
     * than that went unnoticed on a phone: 7 s took over half a minute), so it eases off as the gap
     * closes instead of hitting the edge at full speed. Within 1.5 s it plays
     * at normal speed, so small wobbles don't switch it on and off. The
     * target never comes closer to live than what's buffered safely allows,
     * so it doesn't run the buffer dry chasing the newest segment, and each
     * stall moves the target 2 s further back rather than racing to the
     * same spot to stall again. A seek back on purpose sets a new target,
     * so a rewind isn't undone.
     */
    private fun catchUpControl(): LivePlaybackSpeedControl = CappedSpeedControl(
        DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMaxPlaybackSpeed(FASTEST_CATCH_UP)
        .setProportionalControlFactor(0.1f)
        .setMaxLiveOffsetErrorMsForUnitSpeed(1_500)
            .setTargetLiveOffsetIncrementOnRebufferMs(2_000)
            .build(),
    )

    /**
     * Media3's control picks the target (how close to live is safe, given
     * the buffer and past stalls); the speed towards it follows the setting:
     * its full speed from [FULL_SPEED_BEHIND_US] behind the target, easing
     * down in proportion as the gap closes, normal speed within 1.5 s. Off
     * ([catchUpMax] 1), it plays at normal speed.
     */
    private class CappedSpeedControl(private val inner: DefaultLivePlaybackSpeedControl) : LivePlaybackSpeedControl by inner {
        override fun getAdjustedPlaybackSpeed(liveOffsetUs: Long, bufferedDurationUs: Long): Float {
            val suggested = inner.getAdjustedPlaybackSpeed(liveOffsetUs, bufferedDurationUs)
            val cap = catchUpMax
            if (cap <= 1f) return 1f
            val target = inner.targetLiveOffsetUs
            if (target == C.TIME_UNSET) return minOf(suggested, cap)
            val behindUs = liveOffsetUs - target
            if (kotlin.math.abs(behindUs) < UNIT_SPEED_WITHIN_US) return 1f
            val speed = 1f + (cap - 1f) * behindUs / FULL_SPEED_BEHIND_US
            return speed.coerceIn(0.97f, cap)
        }
    }

    private const val FULL_SPEED_BEHIND_US = 5_000_000f
    private const val UNIT_SPEED_WITHIN_US = 1_500_000L

    /** The highest the setting offers; the media item allows up to this, the setting picks below it. */
    private const val FASTEST_CATCH_UP = 2f

    fun release(context: Context) {
        val current = active ?: return
        active = null
        context.applicationContext.stopService(Intent(context.applicationContext, PlaybackService::class.java))
        current.session.release()
        current.controller.release()
    }

    // Started while the app is on screen: Android doesn't let an app start a
    // service once it's in the background. Media3 then keeps it in the
    // foreground, with the media notification, only while it plays.
    private fun start(context: Context, background: Boolean) {
        if (!background) return
        val app = context.applicationContext
        runCatching { app.startService(Intent(app, PlaybackService::class.java)) }
    }
}

/** Holds the player's session while sound plays with the app off screen. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        // Added straight away so the media notification can show it.
        Playback.active?.session?.let(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = Playback.active?.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The app was swiped away: stop rather than play on with no app.
        Playback.release(this)
        stopSelf()
    }

    override fun onDestroy() {
        // The session belongs to Playback, which releases it; only let go of it here.
        Playback.active?.session?.let(::removeSession)
        super.onDestroy()
    }
}
