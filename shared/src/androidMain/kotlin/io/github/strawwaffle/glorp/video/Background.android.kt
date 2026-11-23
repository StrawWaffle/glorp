package io.github.strawwaffle.glorp.video

actual fun nativePlaybackPlaying(): Boolean = Playback.active?.player?.isPlaying == true
