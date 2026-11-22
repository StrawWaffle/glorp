package io.github.strawwaffle.glorp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/** Whether the player fills the screen, and how to change it. */
class Fullscreen(val active: Boolean = false, val set: (Boolean) -> Unit = {})

val LocalFullscreen = compositionLocalOf { Fullscreen() }

/**
 * Hands the whole screen to the video while [active]: the system bars hide
 * (a swipe brings them back for a moment) and the screen turns sideways.
 * Everything goes back as it was when it ends.
 */
@Composable
internal expect fun SystemFullscreen(active: Boolean)
