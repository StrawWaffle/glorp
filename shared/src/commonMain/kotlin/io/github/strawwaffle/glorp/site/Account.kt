package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.chat.hasModPowers
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** Who is signed in. [roles] carry the powers (`MODERATOR`, `POLLS`…); features are flairs. */
data class Account(
    val nick: String,
    val features: List<String>,
    val subTier: Int,
    val roles: List<String> = emptyList(),
    /** The site's user id; known only from the site's pages (signed in with cookies). */
    val userId: String? = null,
) {
    val modPowers: Boolean get() = hasModPowers(features + roles)
}

/**
 * Whether two sign-ins are the same person: the same user id where both are
 * known, and the same name. A different id or name (another provider's
 * account, a renamed one) counts as someone else; so does signing out.
 */
fun sameUser(a: Account?, b: Account?): Boolean {
    if (a == null || b == null) return a == null && b == null
    if (a.userId != null && b.userId != null && a.userId != b.userId) return false
    return a.nick.equals(b.nick, ignoreCase = true)
}

/**
 * Whether what the last account left behind has to go, going from [before]
 * to [after]: someone else signed in, or signed out. With nobody known in
 * memory yet (offline at launch, then a sign-in), [storedNick], the name the
 * app last had, stands in for who was there.
 */
fun accountChanged(before: Account?, after: Account?, storedNick: String?): Boolean = when {
    before != null -> !sameUser(before, after)
    after == null -> false
    else -> storedNick != null && !storedNick.equals(after.nick, ignoreCase = true)
}

sealed interface SessionCheck {
    /** Signed in. [session] may carry a fresh `sid` the site just issued. */
    data class Valid(val account: Account, val session: Session.Cookies) : SessionCheck
    /** The site doesn't know these cookies (signed out, or they ran out). */
    data object Invalid : SessionCheck
    /** Couldn't ask: no network, or the site is down. Keep the login and try later. */
    data object Unreachable : SessionCheck
}

@Serializable
private data class Me(
    val nick: String? = null,
    val userId: kotlinx.serialization.json.JsonPrimitive? = null,
    val features: List<String> = emptyList(),
    val subscription: Sub? = null,
    val roles: List<String> = emptyList(),
) {
    @Serializable
    data class Sub(val tier: Int = 0)
}

/**
 * Asks the site who these cookies belong to, the way chat-gui does on load.
 * (Login keys can't be checked here: the site's pages don't accept them, only
 * chat does.)
 * When `sid` has run out and `rememberme` is good, the site answers with a new
 * `sid`, which comes back in [SessionCheck.Valid.session].
 */
suspend fun checkSession(client: HttpClient, site: Site, session: Session.Cookies): SessionCheck = try {
    val response = client.get("${site.origin}/api/chat/me") {
        header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
        header(HttpHeaders.Cookie, session.cookieHeader)
    }
    val newSid = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .firstNotNullOfOrNull { Regex("""^sid=([^;]+)""").find(it.trim())?.groupValues?.get(1) }
        ?.takeIf { it != "deleted" && it.isNotEmpty() }
    // Only the site's own answer signs anyone out: signed out, it says 403 in
    // JSON. A challenge page (HTML), too many requests or anything else is
    // the site not answering, and the login is kept.
    val json = response.headers[HttpHeaders.ContentType]?.contains("json", ignoreCase = true) == true
    when {
        !json -> SessionCheck.Unreachable
        response.status.value == 401 || response.status.value == 403 -> SessionCheck.Invalid
        !response.status.isSuccess() -> SessionCheck.Unreachable
        else -> {
            val me = chatJson.decodeFromString<Me?>(response.bodyAsText())
            val nick = me?.nick
            if (nick.isNullOrEmpty()) SessionCheck.Invalid
            else SessionCheck.Valid(
                Account(nick, me.features, me.subscription?.tier ?: 0, me.roles, me.userId?.content),
                if (newSid != null) session.withSid(newSid) else session,
            )
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    // Unreadable, or no connection: not the site saying the login is bad.
    SessionCheck.Unreachable
}
