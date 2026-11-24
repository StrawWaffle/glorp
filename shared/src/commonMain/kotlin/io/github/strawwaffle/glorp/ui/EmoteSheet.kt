package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatInputState
import io.github.strawwaffle.glorp.chat.mayUse
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Emote
import io.github.strawwaffle.glorp.site.EmoteInfo
import kotlin.time.Clock

/**
 * An emote tapped in chat, as the site's emote card (right-click) shows it:
 * the emote, its name, who made it and who may use it, with adding it to
 * the message and making it a favourite (first in the picker, kept with the
 * account as on the site).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EmoteSheet(emote: Emote, info: EmoteInfo?, account: Account?, input: ChatInputState, onDismiss: () -> Unit, hover: Emote? = null) {
    // Played from the start while the card is open; held, the way the site plays it when pointed at.
    val openedAt = remember(emote.prefix) { Clock.System.now().toEpochMilliseconds() }
    var heldAt by remember(emote.prefix) { androidx.compose.runtime.mutableLongStateOf(0L) }
    val favorite = emote.prefix in input.favoriteEmotes
    val usable = account != null && mayUse(emote, account.features, account.subTier, account.roles)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Navy,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Big enough to see: small emotes up to twice their size, big ones fitted.
            val scale = minOf(2f, 240f / emote.width, 140f / emote.height)
            val shown = if (heldAt > 0 && hover != null) hover else emote
            Box(
                Modifier.fillMaxWidth().heightIn(min = 72.dp).then(
                    if (hover == null) Modifier
                    else Modifier.pointerInput(hover) {
                        detectTapGestures(onPress = {
                            heldAt = Clock.System.now().toEpochMilliseconds()
                            tryAwaitRelease()
                            heldAt = 0L
                        })
                    },
                ),
                contentAlignment = Alignment.Center,
            ) {
                EmotePreview(
                    shown,
                    startedAt = if (heldAt > 0 && hover != null) heldAt else openedAt,
                    modifier = Modifier.size((emote.width * scale).dp, (emote.height * scale).dp),
                )
            }
            Text(emote.prefix, color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (hover != null) Text("Hold it to see what it does when pointed at on the site", color = Palette.TextDim, fontSize = 13.sp)
            info?.creator?.let { Text("By $it", color = Palette.TextDim, fontSize = 14.sp) }
            Text(
                when {
                    info?.patreonOnly == true -> "For Patreon members"
                    emote.minimumSubTier > 0 -> "For tier ${emote.minimumSubTier} subscribers"
                    else -> "For everyone"
                } + if (account != null && !usable) " · not one you can use yet" else "",
                color = Palette.TextDim,
                fontSize = 13.sp,
            )
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (usable) {
                    Button(
                        onClick = { input.addEmote(emote.prefix); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
                    ) { Text("Add to message") }
                }
                OutlinedButton(onClick = { input.setFavorite(emote.prefix, !favorite) }) {
                    Text(if (favorite) "★ Favourite" else "☆ Make a favourite", color = Palette.White)
                }
                OutlinedButton(onClick = onDismiss) { Text("Close", color = Palette.TextDim) }
            }
        }
    }
}
