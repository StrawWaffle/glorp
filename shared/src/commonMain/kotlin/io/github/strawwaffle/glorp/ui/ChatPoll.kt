package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatViewModel
import io.github.strawwaffle.glorp.chat.Poll
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Clock

/**
 * The poll above chat, as chat-gui's poll frame: the question, a bar per
 * option, a timer running down. Tapping an option votes. A poll with many
 * options starts folded to its question, so it doesn't push chat off a
 * phone's screen.
 */
@Composable
internal fun PollCard(poll: Poll, onVote: (Int) -> Unit, onClose: () -> Unit) {
    // Folded by default only when it would take much of the screen; a new
    // poll starts as its own size decides.
    var folded by remember(poll.startedAt, poll.question) { mutableStateOf(poll.options.size > 4) }
    Column(Modifier.fillMaxWidth().background(Palette.Navy).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                poll.heading,
                color = Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${poll.votes} votes", color = Palette.TextDim, fontSize = 12.sp)
            CloseButton("Close the poll", onClose)
        }
        Text(
            poll.question,
            color = Palette.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
        )
        val winner = poll.winner
        val shown = when {
            !folded -> poll.options.indices.toList()
            winner != null -> listOf(winner)
            else -> emptyList()
        }
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(end = 8.dp)) {
            for (i in shown) PollOption(poll, i, winner == i, onVote)
        }
        if (poll.options.size > 4) {
            Text(
                if (folded) "Show all ${poll.options.size} options" else "Hide options",
                color = Palette.Blue,
                fontSize = 13.sp,
                modifier = Modifier.clickable { folded = !folded }.padding(vertical = 4.dp),
            )
        }
        if (poll.ended) {
            Text("Poll ended! ${poll.votes} votes cast.", color = Palette.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        } else {
            PollTimer(poll, Modifier.padding(top = 6.dp, end = 8.dp))
        }
    }
}

@Composable
private fun PollOption(poll: Poll, index: Int, winner: Boolean, onVote: (Int) -> Unit) {
    val mine = poll.myVote == index + 1
    val share = poll.percent(index)
    val fill = if (poll.hidden) 1f else (share / 100).toFloat()
    val barColor = when {
        winner -> Palette.Glorp
        poll.hidden -> Palette.NavyLight
        else -> Palette.Blue
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .then(if (mine) Modifier.border(1.dp, Palette.Orange, RoundedCornerShape(4.dp)) else Modifier)
            .clickable(enabled = !poll.ended) { onVote(index + 1) }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.width(22.dp))
            Text(poll.options[index], color = Palette.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                if (poll.hidden) "?" else "${share.roundToInt()}% (${poll.totals[index]} votes)",
                color = if (winner) Palette.Glorp else Palette.TextDim,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).background(Palette.Black, RoundedCornerShape(2.dp))
                .drawBehind { drawRect(barColor, size = Size(size.width * fill, size.height)) },
        )
    }
}

/** The time left as a bar that empties. It steps twice a second, not every frame: a poll runs up to ten minutes. */
@Composable
private fun PollTimer(poll: Poll, modifier: Modifier) {
    var left by remember(poll.startedAt) { mutableFloatStateOf(1f) }
    LaunchedEffect(poll.startedAt, poll.durationMs) {
        while (true) {
            val remaining = poll.endsAt - Clock.System.now().toEpochMilliseconds()
            left = (remaining.toFloat() / poll.durationMs).coerceIn(0f, 1f)
            if (remaining <= 0) break
            delay(500)
        }
    }
    Box(
        modifier.fillMaxWidth().height(3.dp).background(Palette.Black)
            .drawBehind { drawRect(Palette.Orange, size = Size(size.width * left, size.height)) },
    )
}

/**
 * The pinned message, drawn as its chat line, with a button to close it.
 * Closed, a small tab brings it back.
 */
@Composable
internal fun PinnedBar(
    pin: ChatViewModel.Pinned,
    flairs: Flairs,
    emotes: Emotes,
    me: String?,
    onEmbed: (Embed) -> Unit,
    onNick: (String) -> Unit,
    onShow: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().background(Palette.NavyLight).padding(start = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f).padding(top = 4.dp, bottom = 4.dp)) {
            Text("Pinned", color = Palette.Orange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            Box(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                ChatRow(pin.line, false, flairs, emotes, me, null, false, onEmbed, onNick)
            }
        }
        CloseButton("Close the pinned message") { onShow(false) }
    }
}

@Composable
private fun CloseButton(label: String, onClick: () -> Unit) {
    Text(
        "✕",
        color = Palette.TextDim,
        fontSize = 14.sp,
        modifier = Modifier.clickable(onClickLabel = label, onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
