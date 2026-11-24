package io.github.strawwaffle.glorp.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.strawwaffle.glorp.chat.ChatInputState
import io.github.strawwaffle.glorp.chat.ChatLine
import io.github.strawwaffle.glorp.chat.TAG_COLORS
import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.site.UserLookup
import io.github.strawwaffle.glorp.site.ageLabel
import io.github.strawwaffle.glorp.site.genderLabel

/** What the name sheet can do beyond the input's own lists. */
class UserActions(
    val lookUp: suspend (String) -> UserLookup,
    /** What's already known about someone (looked up lately), to open their sheet with. */
    val known: (String) -> UserLookup? = { null },
    /** Their membership card from their profile page (photo, team, signature…), and what's kept of it. */
    val profile: suspend (String) -> io.github.strawwaffle.glorp.site.ProfileCard? = { null },
    val knownProfile: (String) -> io.github.strawwaffle.glorp.site.ProfileCard? = { null },
    /** Opens the chat log search for a name. */
    val recent: (String) -> Unit,
    val ignore: (String) -> Unit,
    val unignore: (String) -> Unit,
    val highlight: (String, Boolean) -> Unit,
    /** Runs a typed command, as if sent from the input (moderators' mute and ban). */
    val command: (String) -> Unit,
    val watch: (Embed) -> Unit,
)

/**
 * A person, as the site's user menu shows them: name, flairs, tag, when they
 * joined and what they're watching; then what can be done about them. Opened
 * by tapping a name or long-pressing a message (which adds the message's own
 * actions).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSheet(
    nick: String,
    line: ChatLine?,
    flairs: Flairs,
    account: Account?,
    input: ChatInputState,
    actions: UserActions,
    onDismiss: () -> Unit,
) {
    // Opened at its full height: the details filling in then don't push it up.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val lookup by produceState(actions.known(nick), nick) { value = actions.lookUp(nick) }
    val found = (lookup as? UserLookup.Found)?.info
    // Their card, read from their profile page once the name is known to exist.
    val card by produceState(actions.knownProfile(nick), nick, lookup is UserLookup.Found) {
        if (lookup is UserLookup.Found && value == null) value = actions.profile(nick)
    }
    val key = nick.lowercase()
    val me = account?.nick?.lowercase()
    val self = key == me
    val tag = input.tags.colors[key]
    val note = input.tags.notes[key].orEmpty()
    val features = found?.features ?: line?.features.orEmpty()
    val clipboard = LocalClipboardManager.current

    // Aerith's card: the chat's own colour under it, dashed lines between parts, buttons on tiles.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = if (Palette.Tile != null) Palette.Black else Palette.Navy) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 8.dp)) {
            // Who.
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (tag != null) Box(Modifier.padding(end = 8.dp).size(12.dp).background(tagColor(tag), CircleShape))
                for (badge in flairs.badges(features)) {
                    AsyncImage(badge.url, badge.label, Modifier.padding(end = 4.dp).size(badge.width.dp, badge.height.dp))
                }
                Text(
                    found?.nick ?: nick,
                    color = flairs.nameColor(features)?.let { Color(it) } ?: Palette.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }
            // The details grow into place rather than pushing everything down at once:
            // lines held for them while they load, and any change in size eased.
            Column(Modifier.animateContentSize().padding(horizontal = 20.dp, vertical = 6.dp)) {
                when (lookup) {
                    null -> repeat(3) { i ->
                        Box(
                            Modifier.padding(vertical = 5.dp).fillMaxWidth(listOf(0.55f, 0.4f, 0.3f)[i]).height(12.dp)
                                .background(Palette.NavyLight, RoundedCornerShape(6.dp)),
                        )
                    }
                    UserLookup.NotFound -> Detail("No such user on the site.")
                    UserLookup.Failed -> Detail("Couldn't reach the site for their details.")
                    is UserLookup.Found -> Unit
                }
                found?.watching?.let { embed ->
                    Detail("Watching ${embed.id} on ${PlatformIcons.name(embed.platform)}", Palette.Blue) { actions.watch(embed); onDismiss() }
                }
                val shownCard = card
                if (shownCard != null) MembershipCard(shownCard, Modifier.padding(vertical = 6.dp))
                else {
                    found?.createdDate?.let(::joined)?.let { Detail("Joined $it") }
                    found?.age?.let { Detail("Age: ${ageLabel(it)}") }
                    found?.gender?.let { Detail("Gender: ${genderLabel(it)}") }
                    found?.bio?.let { Detail("Bio: $it") }
                }
                if (note.isNotEmpty()) Detail("Tag: $note")
                val badges = flairs.badges(features)
                if (badges.isNotEmpty()) Detail(badges.joinToString(", ") { it.label })
            }

            // The message it was opened from.
            if (line != null) {
                Divider()
                Text(
                    line.text,
                    color = Palette.TextDim,
                    fontSize = 14.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .background(Palette.Tile ?: Palette.Black.copy(alpha = 0.4f), RoundedCornerShape(if (Palette.Tile != null) 10.dp else 8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Action("Copy message") { clipboard.setText(AnnotatedString(line.text)); onDismiss() }
            }

            Divider()
            if (account != null && !self) {
                Action("Mention") { input.mention(nick); onDismiss() }
                Action("Whisper") { input.whisper(nick); onDismiss() }
            }
            Action("Recent messages") { onDismiss(); actions.recent(nick) }
            if (account != null && !self) {
                val highlighted = nick in input.highlighted
                Action(if (highlighted) "Stop highlighting" else "Highlight their messages") {
                    actions.highlight(nick, !highlighted); onDismiss()
                }
                TagRow(tag) { color ->
                    if (color == null) input.tags.untag(key) else input.tags.tag(key, color, note)
                }
                IconRow(input.options.nameIcons[key]) { input.options.setIcon(key, it) }
                val ignored = nick in input.ignored
                Action(if (ignored) "Stop ignoring" else "Ignore", if (ignored) Palette.White else Palette.Orange) {
                    if (ignored) actions.unignore(nick) else actions.ignore(nick)
                    onDismiss()
                }
            }

            if (account?.modPowers == true && !self) {
                Divider()
                Durations("Mute", listOf("1m", "10m", "1h", "1d"), confirm = false) {
                    actions.command("/mute $nick $it"); onDismiss()
                }
                // As the site's menu bans: by IP, with its reason.
                Durations("Ban", listOf("1d", "7d", "30d", "Perm"), confirm = true) {
                    actions.command("/ipban $nick $it $nick banned by ${account.nick}."); onDismiss()
                }
            }
        }
    }
}

@Composable
private fun Detail(text: String, color: Color = Palette.TextDim, onClick: (() -> Unit)? = null) = Text(
    text,
    color = color,
    fontSize = 13.sp,
    modifier = Modifier.padding(vertical = 2.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
)

@Composable
private fun Divider() {
    if (Palette.Tile == null) return HorizontalDivider(Modifier.padding(vertical = 4.dp), color = Palette.NavyLight)
    val color = Palette.NavyLight
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).height(1.dp)) {
        drawLine(
            color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = size.height,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
        )
    }
}

/** A full-width row, at least 48 dp tall. */
@Composable
private fun Action(label: String, color: Color = Palette.White, onClick: () -> Unit) = Text(
    label,
    color = color,
    fontSize = 15.sp,
    modifier = Modifier.fillMaxWidth()
        .then(Palette.Tile?.let { Modifier.padding(horizontal = 16.dp, vertical = 3.dp).background(it, RoundedCornerShape(10.dp)) } ?: Modifier)
        .heightIn(min = 48.dp).clickable(onClick = onClick)
        .padding(horizontal = if (Palette.Tile != null) 14.dp else 20.dp, vertical = 13.dp),
)

/** A moderator's action with its lengths; a ban asks for a second tap. */
@Composable
private fun Durations(label: String, durations: List<String>, confirm: Boolean, onPick: (String) -> Unit) {
    var armed by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            armed?.let { "Tap $it again to ${label.lowercase()}" } ?: label,
            color = if (armed != null) Palette.Orange else Palette.White,
            fontSize = 15.sp,
        )
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (d in durations) {
                Text(
                    d,
                    color = if (d == armed) Palette.Black else Palette.White,
                    fontSize = 14.sp,
                    modifier = Modifier.heightIn(min = 40.dp)
                        .background(if (d == armed) Palette.Orange else Palette.Tile ?: Palette.NavyLight, RoundedCornerShape(if (Palette.Tile != null) 10.dp else 8.dp))
                        .clickable { if (!confirm || armed == d) onPick(d) else armed = d }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private val months = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/** `2013-03-08T…` as "8 March 2013". */
internal fun joined(date: String): String? {
    val parts = date.take(10).split('-').mapNotNull { it.toIntOrNull() }
    if (parts.size != 3 || parts[1] !in 1..12) return null
    return "${parts[2]} ${months[parts[1] - 1]} ${parts[0]}"
}
