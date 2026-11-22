package io.github.strawwaffle.glorp.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.Rect

/**
 * What the platform's picture-in-picture needs from the screens, and tells
 * them. On platforms without it, the defaults do nothing.
 */
class PictureInPicture(
    /** True while the app is a small floating video window. */
    val active: Boolean = false,
    /** Whether leaving the app should shrink it to the video: true while a player is showing. */
    val setAvailable: (Boolean) -> Unit = {},
    /** Where the video is in the window, so the shrink animates from it. */
    val setVideoBounds: (Rect) -> Unit = {},
)

val LocalPictureInPicture = compositionLocalOf { PictureInPicture() }

/** Whether the native player keeps its sound going when the app is left or the screen goes off. */
val LocalBackgroundAudio = compositionLocalOf { false }

/** Whether a live stream that falls behind plays a little faster until it's back. */
val LocalCatchUp = compositionLocalOf { true }
