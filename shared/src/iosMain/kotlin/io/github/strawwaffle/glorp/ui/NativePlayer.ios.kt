package io.github.strawwaffle.glorp.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.strawwaffle.glorp.video.Playable

// AVPlayer plays HLS natively; it comes with the iOS app.
@Composable
internal actual fun NativePlayer(playable: Playable, modifier: Modifier, reload: () -> Unit) {
    Text("Video isn't available on iOS yet.", color = Palette.TextDim, modifier = modifier)
}
