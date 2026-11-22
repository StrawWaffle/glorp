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
import androidx.media3.exoplayer.ExoPlayer
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
    class Active(val url: String, val player: ExoPlayer, val controller: ExoController, val session: MediaSession)

    var active: Active? = null
        private set

    /** The player for [playable]: the one already playing it, or a new one. */
    fun attach(context: Context, playable: Playable, background: Boolean): Active {
        active?.let { if (it.url == playable.hlsUrl) return it.also { start(context, background) } }
        release(context)
        val app = context.applicationContext
        val player = ExoPlayer.Builder(app)
            // Pauses for calls and other apps' sound, as a video app should.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
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
            override fun onPlayerError(error: PlaybackException) {
                // A live stream fallen too far behind: jump back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
        })
        val item = MediaItem.Builder().setUri(playable.hlsUrl)
            .setMimeType(if (playable.mp4) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8).build()
        val start = playable.startSeconds
        if (start != null && !playable.live) player.setMediaItem(item, start * 1000L) else player.setMediaItem(item)
        player.playWhenReady = true
        player.prepare()
        // The session gives the picture-in-picture window, headset buttons and
        // the notification play and pause.
        val session = MediaSession.Builder(app, player).setId("glorp").build()
        return Active(playable.hlsUrl, player, ExoController(player), session).also {
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
