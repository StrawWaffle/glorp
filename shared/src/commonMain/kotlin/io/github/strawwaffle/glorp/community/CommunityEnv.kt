package io.github.strawwaffle.glorp.community

import io.github.strawwaffle.glorp.site.Account
import io.github.strawwaffle.glorp.site.Embed
import io.github.strawwaffle.glorp.site.Emotes
import io.github.strawwaffle.glorp.site.Flairs
import io.ktor.client.HttpClient

/**
 * What a community-site screen gets from the app: the shared HTTP client,
 * the emote and flair lists for drawing chat text, who's signed in, and ways
 * back out. Each screen fetches only while it's open.
 */
class CommunityEnv(
    val http: HttpClient,
    val emotes: Emotes,
    val flairs: Flairs,
    val account: Account?,
    /** Back to the menu. */
    val onClose: () -> Unit,
    /** Opens an embed in the player and returns to chat. */
    val onWatch: (Embed) -> Unit,
    /** Opens another community screen ("rustle", "polls", "dinkdonk") with an optional argument (e.g. a username). */
    val onOpen: (screen: String, argument: String?) -> Unit,
    /** The argument this screen was opened with, if any. */
    val argument: String? = null,
)
