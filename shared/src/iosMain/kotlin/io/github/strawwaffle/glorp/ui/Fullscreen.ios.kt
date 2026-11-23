package io.github.strawwaffle.glorp.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun SystemFullscreen(active: Boolean) {
    // Not yet: the iOS app has no native player to give the screen to.
}

@androidx.compose.runtime.Composable
internal actual fun SystemBarsLight(light: Boolean) {}
