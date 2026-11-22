package io.github.strawwaffle.glorp.ui

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import io.github.strawwaffle.glorp.video.ExoController
import io.github.strawwaffle.glorp.video.Playable

@OptIn(UnstableApi::class)
@Composable
internal actual fun NativePlayer(playable: Playable, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember {
        // Only the video surface: the controls are Glorp's own, made for live.
        PlayerView(context).apply {
            setBackgroundColor(Color.BLACK)
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
        }
    }
    var controller by remember { mutableStateOf<ExoController?>(null) }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        controller?.let { PlayerControls(it, Modifier.fillMaxSize()) }
    }

    // A player exists only while the app is on screen: decoders and the
    // network are let go of as soon as it leaves. (Picture in picture will
    // be the way to keep watching.)
    LifecycleStartEffect(playable.hlsUrl) {
        val player = ExoPlayer.Builder(context)
            // Pauses for calls and other apps' sound, as a video app should.
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Auto picks nothing above what the screen can show: a 1080p60 stream
        // on a small view costs decode work and data for nothing. A quality
        // picked by hand overrides this.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setViewportSizeToPhysicalDisplaySize(context, true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                view.keepScreenOn = isPlaying
            }

            override fun onPlayerError(error: PlaybackException) {
                // A live stream fallen too far behind: jump back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
            }
        })
        player.setMediaItem(MediaItem.Builder().setUri(playable.hlsUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build())
        player.playWhenReady = true
        player.prepare()
        view.player = player
        val exo = ExoController(player)
        controller = exo
        // A media session gives the picture-in-picture window (and headset
        // buttons) play and pause. Unique id: the old one may not be gone yet.
        val session = MediaSession.Builder(context, player).setId("glorp-${System.nanoTime()}").build()
        onStopOrDispose {
            controller = null
            view.player = null
            view.keepScreenOn = false
            session.release()
            exo.release()
        }
    }
}
