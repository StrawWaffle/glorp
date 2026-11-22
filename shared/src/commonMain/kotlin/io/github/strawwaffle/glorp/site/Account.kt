package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/** Who is signed in. */
data class Account(val nick: String, val features: List<String>, val subTier: Int)

sealed interface SessionCheck {
    /** Signed in. [session] may carry a fresh `sid` the site just issued. */
    data class Valid(val account: Account, val session: Session) : SessionCheck
    /** The site doesn't know these cookies (signed out, or they ran out). */
    data object Invalid : SessionCheck
    /** Couldn't ask: no network, or the site is down. Keep the login and try later. */
    data object Unreachable : SessionCheck
}

@Serializable
private data class Me(
    val nick: String? = null,
    val features: List<String> = emptyList(),
    val subscription: Sub? = null,
) {
    @Serializable
    data class Sub(val tier: Int = 0)
}

/**
 * Asks the site who these cookies belong to, the way chat-gui does on load.
 * When `sid` has run out and `rememberme` is good, the site answers with a new
 * `sid`, which comes back in [SessionCheck.Valid.session].
 */
suspend fun checkSession(client: HttpClient, site: Site, session: Session): SessionCheck = try {
    val response = client.get("${site.origin}/api/chat/me") {
        header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
        header(HttpHeaders.Cookie, session.cookieHeader)
    }
    val newSid = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        .firstNotNullOfOrNull { Regex("""^sid=([^;]+)""").find(it.trim())?.groupValues?.get(1) }
        ?.takeIf { it != "deleted" && it.isNotEmpty() }
    when {
        response.status.value in 500..599 -> SessionCheck.Unreachable
        !response.status.isSuccess() -> SessionCheck.Invalid
        else -> {
            val me = chatJson.decodeFromString<Me?>(response.bodyAsText())
            val nick = me?.nick
            if (nick.isNullOrEmpty()) SessionCheck.Invalid
            else SessionCheck.Valid(
                Account(nick, me.features, me.subscription?.tier ?: 0),
                if (newSid != null) session.withSid(newSid) else session,
            )
        }
    }
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: IllegalArgumentException) {
    // Not JSON: a login page or an error page rather than an answer.
    SessionCheck.Invalid
} catch (_: Exception) {
    SessionCheck.Unreachable
}
