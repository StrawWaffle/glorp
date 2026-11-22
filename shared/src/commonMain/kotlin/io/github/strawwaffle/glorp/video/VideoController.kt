package io.github.strawwaffle.glorp.video

import kotlinx.coroutines.flow.StateFlow

/** One quality a stream offers. */
data class Quality(
    /** The player's own handle for it, opaque to the controls. */
    val id: String,
    val height: Int,
    val fps: Int?,
) {
    /** As streaming sites write it: "1080p60", "480p". */
    val label: String get() = "${height}p" + (fps?.takeIf { it > 30 }?.toString() ?: "")
}

/** What the controls show. */
data class VideoState(
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val live: Boolean = false,
    /** Live and close to the newest part of the stream. */
    val atLiveEdge: Boolean = true,
    /** Highest first. */
    val qualities: List<Quality> = emptyList(),
    /** The one picked by hand; null lets the player choose. */
    val chosen: Quality? = null,
    /** What is playing right now. */
    val current: Quality? = null,
)

/** The controls' view of a player, so one set of controls serves every platform's player. */
interface VideoController {
    val state: StateFlow<VideoState>
    fun play()
    fun pause()
    fun goLive()
    /** Null goes back to choosing automatically. */
    fun choose(quality: Quality?)
    /** Reads how far behind live the player is. Called only while the controls are on screen. */
    fun refresh()
}
