package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.ChatPrefs
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.diag.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okio.ByteString.Companion.encodeUtf8
import kotlin.random.Random
import kotlin.time.Clock

/** Glorp's app on destiny.gg (profile → developer), for "Sign in with destiny.gg". */
class AppClient(val id: String, val secret: String) {
    companion object {
        /** Registered with the app on the site; the site checks the start of it. */
        const val REDIRECT = "io.github.strawwaffle.glorp://oauth"
    }
}

/**
 * "Sign in with destiny.gg": the site's OAuth flow, in the phone's browser
 * (where people are usually signed in already, and where Twitch's and Google's
 * logins work). The site sends the browser back to [AppClient.REDIRECT] with a
 * code, which is exchanged for a token that chat takes as a login key. It
 * lasts an hour and is renewed before chat connects.
 *
 * The site's check (OAuthController::tokenExchange) is
 * `base64(sha256hex(code_verifier + client_secret))`, so the app holds the
 * secret; the redirect address, which the site insists on, is what ties the
 * code to this app.
 */
class AppSignIn(
    private val client: HttpClient,
    private val site: Site,
    private val app: AppClient,
    private val prefs: ChatPrefs,
) {
    sealed interface Result {
        data class Signed(val session: Session.LoginKey) : Result
        data class Failed(val why: String) : Result
    }

    /** The page to open in the browser; what it needs to finish is kept until then. */
    fun authorizeUrl(): String {
        val verifier = randomToken(64)
        val state = randomToken(32)
        prefs.put(VERIFIER, verifier)
        prefs.put(STATE, state)
        val query = listOf(
            "response_type" to "code",
            "client_id" to app.id,
            "redirect_uri" to AppClient.REDIRECT,
            "state" to state,
            "code_challenge" to codeChallenge(verifier, app.secret),
            "code_challenge_method" to "S256",
        ).joinToString("&") { (k, v) -> "$k=${v.encodeURLParameter()}" }
        return "${site.origin}/oauth/authorize?$query"
    }

    /** Finishes with the address the browser came back to. */
    suspend fun complete(redirect: String): Result {
        val url = runCatching { Url(redirect) }.getOrNull() ?: return Result.Failed("The site sent back an address Glorp can't read.")
        val code = url.parameters["code"] ?: return Result.Failed("The site didn't sign you in. Try again.")
        val verifier = prefs.get(VERIFIER)
        if (verifier == null || url.parameters["state"] != prefs.get(STATE)) {
            return Result.Failed("That sign-in wasn't started here, or was already used. Try again.")
        }
        prefs.put(VERIFIER, null)
        prefs.put(STATE, null)
        val body = token(
            "grant_type" to "authorization_code",
            "code" to code,
            "client_id" to app.id,
            "redirect_uri" to AppClient.REDIRECT,
            "code_verifier" to verifier,
        ) ?: return Result.Failed("Couldn't reach ${site.host}. Try again.")
        return readToken(body)?.let { Result.Signed(it) }
            ?: Result.Failed("${site.host} said: ${tokenError(body) ?: "no"}")
    }

    /** A new token for one that has run out, or is about to; null when the site won't renew it (signed out there). */
    suspend fun renew(key: Session.LoginKey): Session.LoginKey? {
        val refresh = key.refresh ?: return null
        val body = token("grant_type" to "refresh_token", "client_id" to app.id, "refresh_token" to refresh) ?: return null
        return readToken(body)
    }

    private suspend fun token(vararg params: Pair<String, String>): String? = try {
        client.get("${site.origin}/oauth/token") {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            params.forEach { (k, v) -> parameter(k, v) }
        }.bodyAsText()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.note("signin", "token request failed", e)
        null
    }

    private companion object {
        const val VERIFIER = "signin.verifier"
        const val STATE = "signin.state"
    }
}

/** Whether [key] should be renewed before chat uses it: within five minutes of running out. */
fun needsRenewal(key: Session.LoginKey, now: Long = Clock.System.now().toEpochMilliseconds()): Boolean =
    key.renewable && (key.expiresAt ?: 0) - now < 5 * 60_000

/** The site's challenge: base64 of the hex SHA-256 of the verifier and the secret. */
internal fun codeChallenge(verifier: String, secret: String): String =
    (verifier + secret).encodeUtf8().sha256().hex().encodeUtf8().base64()

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

internal fun randomToken(length: Int, random: Random = secureRandom): String =
    String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })

/** `{access_token, refresh_token, expires_in}` as a renewable login key. */
internal fun readToken(body: String, now: Long = Clock.System.now().toEpochMilliseconds()): Session.LoginKey? = runCatching {
    val o = chatJson.parseToJsonElement(body).jsonObject
    val access = (o["access_token"] as? JsonPrimitive)?.contentOrNull ?: return null
    val refresh = (o["refresh_token"] as? JsonPrimitive)?.contentOrNull ?: return null
    val seconds = (o["expires_in"] as? JsonPrimitive)?.longOrNull ?: 3600
    val key = Session.loginKey(access) ?: return null
    Session.LoginKey(key.key, refresh, now + seconds * 1000)
}.getOrNull()

internal fun tokenError(body: String): String? = runCatching {
    (chatJson.parseToJsonElement(body).jsonObject["message"] as? JsonPrimitive)?.contentOrNull
}.getOrNull()

/**
 * The sign-in code the desktop page (glorp's GitHub Pages, signin/) shows as a
 * QR code: `glorp-signin:1:<site host>:<sid>:<rememberme>`. The host keeps a
 * code for one site from signing in to another.
 */
fun readSignInCode(text: String, site: Site): Session.Cookies? {
    val parts = text.trim().split(':')
    if (parts.size != 5 || parts[0] != "glorp-signin" || parts[1] != "1") return null
    if (!parts[2].equals(site.host, ignoreCase = true)) return null
    return Session.cookies(parts[3], parts[4])
}

/** The platform's cryptographically secure random numbers. */
internal expect val secureRandom: Random
