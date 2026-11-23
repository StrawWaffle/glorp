package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A whisper that just arrived, under the bar for a few seconds: tap to open the conversation. */
@Composable
fun WhisperBanner(nick: String, text: String, onOpen: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Palette.Blue.copy(alpha = 0.18f)).clickable(onClickLabel = "Open the conversation", onClick = onOpen)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GlorpIcons.Mail, contentDescription = null, tint = Palette.Blue, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("Whisper from $nick", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(text, color = Palette.TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(48.dp).clickable(onClickLabel = "Close", onClick = onClose), contentAlignment = Alignment.Center) {
            Icon(GlorpIcons.Close, contentDescription = "Close", tint = Palette.TextDim, modifier = Modifier.size(18.dp))
        }
    }
}
