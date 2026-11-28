package io.github.strawwaffle.glorp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.Conversation
import io.github.strawwaffle.glorp.chat.Inbox
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs

/**
 * Whisper conversations, as chat-gui's whisper list and windows: newest
 * first, unread counts, and each one's messages with a box to reply. With a
 * login key only this session's whispers are here; the site's history needs
 * its cookies.
 */
@Composable
fun WhispersScreen(
    inbox: Inbox,
    /** The conversation to show first, from `/open` or a whisper tapped in chat. */
    start: String?,
    history: Boolean,
    account: io.github.strawwaffle.glorp.site.Account,
    connected: Boolean,
    /** The reply box's draft and history, sharing chat's suggestions. */
    input: io.github.strawwaffle.glorp.chat.ChatInputState,
    /** Opens the cookie sign-in, offered when only a login key is in. */
    onCookies: () -> Unit,
    flairs: Flairs,
    emotes: Emotes,
    onOpen: (String) -> Unit,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    /** Whispers [text] to the nick; false when it couldn't go. */
    onSend: (nick: String, text: String) -> Boolean,
    onEmbed: (Embed) -> Unit,
    onBack: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(start) }
    LaunchedEffect(start) { if (start != null) open = start }
    LaunchedEffect(open) {
        val nick = open
        if (nick != null) onOpen(nick) else { onClose(); onRefresh() }
    }
    val nick = open
    if (nick == null) {
        OnBack(true, onBack)
        ConversationList(inbox, history, onCookies, onPick = { open = it })
    } else {
        OnBack(true) { open = null }
        ConversationView(inbox[nick] ?: Conversation(nick), account, connected, input, flairs, emotes, { onSend(nick, it) }, onEmbed)
    }
}

@Composable
private fun ConversationList(inbox: Inbox, history: Boolean, onCookies: () -> Unit, onPick: (String) -> Unit) {
    var to by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().background(Palette.Black)) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("Whispers", color = Palette.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (!history) {
                    Text(
                        "Signed in with a login key, so only whispers from while Glorp is open show here. " +
                            "Sign in with destiny.gg for your history.",
                        color = Palette.TextDim,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "Sign in with destiny.gg",
                        color = Palette.Blue,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable(onClick = onCookies).padding(vertical = 8.dp),
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(to, { to = it.filter { c -> c.isLetterOrDigit() || c == '_' } }, "Whisper to…", Modifier.weight(1f), Palette.Navy) {
                    if (to.length >= 3) onPick(to)
                }
                Text(
                    "Open",
                    color = if (to.length >= 3) Palette.Blue else Palette.TextDim,
                    modifier = Modifier.clickable(enabled = to.length >= 3) { onPick(to) }.padding(12.dp),
                )
            }
        }
        val conversations = inbox.sorted
        if (conversations.isEmpty()) {
            item { Text("No whispers yet.", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        }
        items(conversations, key = { it.key }) { conversation ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(conversation.nick) }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(conversation.nick, color = Palette.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (conversation.preview.isNotEmpty()) {
                        Text(
                            (if (conversation.previewMine) "You: " else "") + conversation.preview,
                            color = Palette.TextDim,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (conversation.unread > 0) {
                    Text(
                        "${conversation.unread}",
                        color = Palette.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp).background(Palette.Orange, RoundedCornerShape(10.dp))
                            .padding(horizontal = 7.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationView(
    conversation: Conversation,
    account: io.github.strawwaffle.glorp.site.Account,
    connected: Boolean,
    input: io.github.strawwaffle.glorp.chat.ChatInputState,
    flairs: Flairs,
    emotes: Emotes,
    onSend: (String) -> Boolean,
    onEmbed: (Embed) -> Unit,
) {
    // Drawn as chat lines, so emotes and links look as they do in chat.
    val lines = remember(conversation.messages) {
        val all = conversation.messages.map { ChatLine.user(it.from, emptyList(), it.text, it.at, subTier = Int.MAX_VALUE) }
        // A run from one person shows their name once, as in chat.
        all.mapIndexed { i, line -> line to (i > 0 && continues(all[i - 1], line)) }.asReversed()
    }
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().background(Palette.Black)) {
        Text(
            "Whispers with ${conversation.nick}",
            color = Palette.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            modifier = Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (lines.isEmpty()) {
                Text("No messages yet.", color = Palette.TextDim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
            }
            // The same words from the same person in the same second share an
            // id; a second one gets a number, or the list would crash.
            val keys = remember(lines) {
                val seen = HashMap<String, Int>()
                lines.map { (line, _) ->
                    val n = seen.getOrElse(line.id) { 0 }
                    seen[line.id] = n + 1
                    if (n == 0) line.id else "${line.id}#$n"
                }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, reverseLayout = true) {
                itemsIndexed(lines, key = { i, _ -> keys[i] }) { _, (line, continued) ->
                    ChatRow(line, continued, flairs, emotes, account.nick, null, false, onEmbed, onNick = {})
                }
            }
        }
        conversation.problem?.let {
            Text(it, color = Palette.Orange, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().background(Palette.Navy).padding(horizontal = 16.dp, vertical = 6.dp))
        }
        // Chat's own input: the same emote and name suggestions and picker.
        ChatInput(
            input, emotes, account, connected,
            onSend = onSend,
            hint = "Whisper to ${conversation.nick} ...",
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier,
    background: androidx.compose.ui.graphics.Color,
    onDone: () -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Palette.White, fontSize = 15.sp),
        cursorBrush = SolidColor(Palette.Blue),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onDone() }),
        modifier = modifier.background(background, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = Palette.TextDim, fontSize = 15.sp)
                inner()
            }
        },
    )
}
