package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.LinkFlag
import io.github.strawwaffle.glorp.site.LinkPreview
import io.github.strawwaffle.glorp.site.PreviewKind
import io.ktor.http.Url

/**
 * A link tapped in chat, with the preview the site shows on hover: what it
 * is, then open it (in the app that handles it, or the browser), watch it
 * here (YouTube), copy it, or close. A link in a message marked NSFW, NSFL or
 * spoilers shows its preview only when asked, as the site leaves it out.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LinkCard(
    url: String,
    kind: PreviewKind,
    flag: LinkFlag?,
    load: suspend () -> LinkPreview?,
    onWatch: (Embed) -> Unit,
    onDismiss: () -> Unit,
) {
    val links = LocalUriHandler.current
    @Suppress("DEPRECATION") // The newer clipboard API is suspend-only and platform-typed.
    val clipboard = LocalClipboardManager.current
    // A video's title is harmless; pictures and posts wait when the message is flagged.
    var revealed by remember(url) { mutableStateOf(flag == null || kind == PreviewKind.Video) }
    var loading by remember(url) { mutableStateOf(true) }
    var preview by remember(url) { mutableStateOf<LinkPreview?>(null) }
    var copied by remember(url) { mutableStateOf(false) }
    if (revealed) {
        LaunchedEffect(url) {
            preview = load()
            loading = false
        }
    }
    val host = remember(url) { runCatching { Url(url).host.removePrefix("www.") }.getOrDefault(url) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Navy,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                !revealed -> Hidden(flag!!) { revealed = true }
                loading -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Palette.Glorp, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                }
                else -> when (val p = preview) {
                    is LinkPreview.Video -> VideoPreview(p)
                    is LinkPreview.Post -> PostPreview(p)
                    is LinkPreview.Picture -> Picture(p.url, "Picture from $host", 380)
                    null -> Text("No preview for this link.", color = Palette.TextDim, fontSize = 14.sp)
                }
            }
            Text(url, color = Palette.TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val embed = (preview as? LinkPreview.Video)?.embed
                if (embed != null) {
                    Button(
                        onClick = { onWatch(embed); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
                    ) { Text("Watch here") }
                }
                val openLabel = when {
                    embed != null -> "Open in YouTube"
                    kind is PreviewKind.Post -> "Open in X"
                    else -> "Open $host"
                }
                val open = { runCatching { links.openUri(url) }; onDismiss() }
                if (embed == null) {
                    Button(
                        onClick = open,
                        colors = ButtonDefaults.buttonColors(containerColor = Palette.Blue, contentColor = Palette.OnAction),
                    ) { Text(openLabel) }
                } else {
                    OutlinedButton(onClick = open) { Text(openLabel, color = Palette.White) }
                }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url)); copied = true }) {
                    Text(if (copied) "Copied" else "Copy link", color = Palette.White)
                }
                OutlinedButton(onClick = onDismiss) { Text("Close", color = Palette.TextDim) }
            }
        }
    }
}

@Composable
private fun Hidden(flag: LinkFlag, onShow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Black, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("The message is marked ${flag.label}, so the preview is hidden.", color = Palette.Orange, fontSize = 14.sp)
        OutlinedButton(onClick = onShow) { Text("Show the preview", color = Palette.White) }
    }
}

@Composable
private fun VideoPreview(p: LinkPreview.Video) {
    p.title.thumbnail?.let {
        AsyncImage(
            it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        PlatformIcon(p.title.platform, 18.dp, Modifier.padding(end = 8.dp))
        Text(p.title.title, color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    p.title.channel?.let { Text(it, color = Palette.TextDim, fontSize = 13.sp) }
}

@Composable
private fun PostPreview(p: LinkPreview.Post) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(p.name, color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("@${p.handle}", color = Palette.TextDim, fontSize = 13.sp)
    }
    if (p.text.isNotBlank()) Text(p.text, color = Palette.White, fontSize = 15.sp)
    p.image?.let { Picture(it, null, 320) }
}

/** A picture as big as fits, or a line saying it couldn't be loaded (gone, private, not a picture). */
@Composable
private fun Picture(url: String, description: String?, maxHeight: Int) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Text("Couldn't load the picture.", color = Palette.TextDim, fontSize = 14.sp)
    } else {
        AsyncImage(
            url,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            onError = { failed = true },
            modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight.dp).clip(RoundedCornerShape(10.dp)),
        )
    }
}
