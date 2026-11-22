package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.strawwaffle.glorp.video.VideoController
import kotlinx.coroutines.delay

private val LiveRed = Color(0xFFE5322D)

/**
 * Controls made for live streams: play and pause, a LIVE mark that shows
 * whether you're at the live edge and takes you back there, and the stream's
 * qualities. No timeline: a live stream has no end to scrub towards.
 *
 * They appear on a tap and hide after a few seconds. While hidden nothing is
 * polled.
 */
@Composable
fun PlayerControls(controller: VideoController, modifier: Modifier = Modifier, retry: () -> Unit = controller::restart) {
    val state by controller.state.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf(true) }
    // Bumped by every tap on a control, so the hide timer starts over.
    var touched by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(shown, touched, menuOpen, state.playing) {
        if (!shown) return@LaunchedEffect
        // Poll the live offset once a second, only while the controls are up.
        repeat(if (state.playing && !menuOpen) HIDE_AFTER_SECONDS else Int.MAX_VALUE) {
            controller.refresh()
            delay(1_000)
        }
        shown = false
    }

    Box(
        modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { shown = !shown; touched++ },
    ) {
        if (state.failed) {
            // It tries again by itself; this is for not waiting.
            Column(
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("The stream stopped. Trying again…", color = Palette.TextDim, fontSize = 13.sp)
                TextButton(onClick = { touched++; retry() }) {
                    Text("Retry now", color = Palette.Glorp, fontWeight = FontWeight.Bold)
                }
            }
        } else if (state.buffering) {
            CircularProgressIndicator(color = Palette.Glorp, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        // The floating window has the system's own play and pause.
        if (!shown || LocalPictureInPicture.current.active) return@Box
        val timeline = state.durationMs > 0 && (!state.live || state.durationMs >= REWINDABLE_MS)
        if (timeline) {
            // Recordings get a timeline, and so do live streams that can be
            // rewound a long way (a VOD still being recorded); a live stream
            // with only the last few seconds has nothing to scrub.
            var dragging by remember { mutableStateOf<Float?>(null) }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 40.dp, start = 12.dp, end = 12.dp)) {
                Slider(
                    value = dragging ?: (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f),
                    onValueChange = { touched++; dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { controller.seekTo((it * state.durationMs).toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = Palette.Glorp, activeTrackColor = Palette.Glorp),
                )
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { touched++; if (state.playing) controller.pause() else controller.play() }) {
                Text(if (state.playing) "❚❚" else "▶", color = Color.White, fontSize = 16.sp)
            }
            if (timeline && !state.live) {
                Text("${clock(state.positionMs)} / ${clock(state.durationMs)}", color = Color.White, fontSize = 12.sp)
            }
            if (state.live) {
                val atEdge = state.atLiveEdge && state.playing
                TextButton(onClick = { touched++; controller.goLive() }) {
                    Box(Modifier.size(8.dp).background(if (atEdge) LiveRed else Palette.TextDim, CircleShape))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (atEdge) "LIVE" else "GO LIVE",
                        color = if (atEdge) Color.White else Palette.TextDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                    )
                }
                // Catching up: how much faster than normal it's playing.
                if (state.catchingUp) {
                    Text("Catching up", color = Palette.Glorp, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            val fullscreen = LocalFullscreen.current
            TextButton(onClick = { touched++; fullscreen.set(!fullscreen.active) }) {
                Text(if (fullscreen.active) "Exit full screen" else "Full screen", color = Color.White, fontSize = 12.sp)
            }
            if (state.qualities.isNotEmpty()) {
                Box {
                    TextButton(onClick = { touched++; menuOpen = true }) {
                        Text(
                            state.chosen?.label ?: ("Auto" + (state.current?.let { " (${it.label})" } ?: "")),
                            color = Color.White,
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Auto", fontWeight = if (state.chosen == null) FontWeight.Bold else null) },
                            onClick = { menuOpen = false; controller.choose(null) },
                        )
                        for (quality in state.qualities) {
                            DropdownMenuItem(
                                text = { Text(quality.label, fontWeight = if (state.chosen == quality) FontWeight.Bold else null) },
                                onClick = { menuOpen = false; controller.choose(quality) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val HIDE_AFTER_SECONDS = 4
private const val REWINDABLE_MS = 10 * 60 * 1000L

/** 1:02:03 or 2:03. */
internal fun clock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    val ss = s.toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$ss" else "$m:$ss"
}
