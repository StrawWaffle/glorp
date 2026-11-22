package io.github.strawwaffle.glorp.ui

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.strawwaffle.glorp.video.ExoController
import io.github.strawwaffle.glorp.video.Playable
import io.github.strawwaffle.glorp.video.Playback

@OptIn(UnstableApi::class)
@Composable
internal actual fun NativePlayer(playable: Playable, modifier: Modifier) {
    val context = LocalContext.current
    val background by rememberUpdatedState(LocalBackgroundAudio.current)
    val catchUp = LocalCatchUp.current
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

    // On screen: attach to the player (the one already playing this stream,
    // picked up without buffering again, or a new one). Off screen: keep only
    // the sound going, if that's on and it was playing; otherwise release.
    LifecycleStartEffect(playable.hlsUrl) {
        val active = Playback.attach(context, playable, background, catchUp)
        view.player = active.player
        view.keepScreenOn = active.player.isPlaying
        val screenOn = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                view.keepScreenOn = isPlaying
            }
        }
        active.player.addListener(screenOn)
        controller = active.controller
        onStopOrDispose {
            active.player.removeListener(screenOn)
            controller = null
            view.player = null
            view.keepScreenOn = false
            Playback.detach(context, keepPlaying = background)
        }
    }
    // A different stream, or the player hidden: nothing to keep.
    DisposableEffect(playable.hlsUrl) {
        onDispose { Playback.release(context) }
    }
}
