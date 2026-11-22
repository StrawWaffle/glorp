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
fun PlayerControls(controller: VideoController, modifier: Modifier = Modifier) {
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
        if (state.buffering) {
            CircularProgressIndicator(color = Palette.Glorp, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        if (!shown) return@Box
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { touched++; if (state.playing) controller.pause() else controller.play() }) {
                Text(if (state.playing) "❚❚" else "▶", color = Color.White, fontSize = 16.sp)
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
            }
            Spacer(Modifier.weight(1f))
            if (state.qualities.isNotEmpty()) {
                Box {
                    TextButton(onClick = { touched++; menuOpen = true }) {
                        Text(
                            state.chosen?.label ?: ("Auto" + (state.current?.let { " (${it.label})" } ?: "")),
                            color = Color.White,
                            fontSize = 12.sp,
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
