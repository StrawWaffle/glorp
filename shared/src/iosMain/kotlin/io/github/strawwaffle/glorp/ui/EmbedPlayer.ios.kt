package io.github.strawwaffle.glorp.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A WKWebView (and later AVPlayer) comes with the iOS app.
@Composable
internal actual fun EmbedPlayer(url: String, origin: String, modifier: Modifier) {
    Text("Video isn't available on iOS yet.", color = Palette.TextDim, modifier = modifier)
}
