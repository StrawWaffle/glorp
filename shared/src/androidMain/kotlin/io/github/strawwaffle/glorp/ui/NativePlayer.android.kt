package io.github.strawwaffle.glorp.ui

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.strawwaffle.glorp.video.ExoController
import io.github.strawwaffle.glorp.video.Playable
import io.github.strawwaffle.glorp.video.Playback

@OptIn(UnstableApi::class)
@Composable
internal actual fun NativePlayer(playable: Playable, modifier: Modifier, reload: () -> Unit) {
    val context = LocalContext.current
    val background by rememberUpdatedState(LocalBackgroundAudio.current)
    // The catch-up setting applies to the playing stream at once.
    val catchUp = LocalCatchUp.current
    SideEffect { Playback.catchUpMax = catchUp }
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
        controller?.let { PlayerControls(it, Modifier.fillMaxSize(), retry = { it.restart(); reload() }) }
    }

    // Failed: start it again and look the stream up again (its address may
    // have expired), waiting longer each time it fails again, up to a minute
    // between tries, so a stream that's gone isn't asked for over and over.
    val failed = controller?.state?.collectAsStateWithLifecycle()?.value?.failed == true
    val playing = controller?.state?.collectAsStateWithLifecycle()?.value?.playing == true
    var failures by remember { mutableIntStateOf(0) }
    LaunchedEffect(playing) { if (playing) failures = 0 }
    LaunchedEffect(failed, controller) {
        val current = controller ?: return@LaunchedEffect
        if (!failed) return@LaunchedEffect
        delay(minOf(3_000L shl minOf(failures, 5), 60_000L))
        failures++
        current.restart()
        reload()
    }

    // On screen: attach to the player (the one already playing this stream,
    // picked up without buffering again, or a new one). Off screen: keep only
    // the sound going, if that's on and it was playing; otherwise release.
    LifecycleStartEffect(playable.hlsUrl) {
        val active = Playback.attach(context, playable, background)
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
