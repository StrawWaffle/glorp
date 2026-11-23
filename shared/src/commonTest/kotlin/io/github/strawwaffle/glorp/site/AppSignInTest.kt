package io.github.strawwaffle.glorp.site

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.chat.MemoryChatPrefs
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppSignInTest {
    private val site = Site.guess("https://www.destiny.gg")

    @Test
    fun challengeIsTheSitesFormula() {
        // PHP: base64_encode(hash('sha256', $verifier . $secret)), the hex digest's text.
        assertEquals(
            "Y2NmZTg1NTUwMjA0ODc2ZTI5ZGFmZDNiOTg0NTlhNGI0ZTlmMjg0NWM1MWQ0MDMzMjBkYjdkYmUzZGVkNjY1ZA==",
            codeChallenge("verifier123", "secret-xyz"),
        )
        assertTrue(codeChallenge("x".repeat(64), "s".repeat(64)).length <= 128)
    }

    @Test
    fun signsInWithTheCodeTheBrowserBringsBack() = runTest {
        val access = "A".repeat(64)
        val refresh = "R".repeat(64)
        var asked: Url? = null
        val client = HttpClient(MockEngine { request ->
            asked = request.url
            respond("""{"access_token":"$access","refresh_token":"$refresh","expires_in":3600,"scope":"identify","token_type":"bearer"}""")
        })
        val prefs = MemoryChatPrefs()
        val flow = AppSignIn(client, site, AppClient("client1", "secret"), prefs)
        val start = Url(flow.authorizeUrl())
        assertEquals("/oauth/authorize", start.encodedPath)
        assertEquals(AppClient.REDIRECT, start.parameters["redirect_uri"])
        val state = start.parameters["state"]!!

        // Another state: not this app's sign-in.
        assertIs<AppSignIn.Result.Failed>(flow.complete("${AppClient.REDIRECT}?code=abc&state=other"))
        val done = flow.complete("${AppClient.REDIRECT}?code=abc&state=$state")
        val key = assertIs<AppSignIn.Result.Signed>(done).session
        assertEquals(access, key.key)
        assertEquals(refresh, key.refresh)
        assertEquals("authorization_code", asked!!.parameters["grant_type"])
        assertEquals("abc", asked!!.parameters["code"])
        // Used once.
        assertIs<AppSignIn.Result.Failed>(flow.complete("${AppClient.REDIRECT}?code=abc&state=$state"))
    }

    @Test
    fun saysWhatTheSiteRefused() = runTest {
        val client = HttpClient(MockEngine { respond("""{"error":"invalid_grant","message":"Invalid code_verifier","code":500}""") })
        val flow = AppSignIn(client, site, AppClient("c", "s"), MemoryChatPrefs())
        val state = Url(flow.authorizeUrl()).parameters["state"]!!
        val failed = assertIs<AppSignIn.Result.Failed>(flow.complete("${AppClient.REDIRECT}?code=x&state=$state"))
        assertTrue("Invalid code_verifier" in failed.why, failed.why)
    }

    @Test
    fun renewsShortlyBeforeItRunsOut() {
        val key = Session.LoginKey("k".repeat(64), "r".repeat(64), expiresAt = 1_000_000)
        assertFalse(needsRenewal(key, now = 1_000_000 - 10 * 60_000))
        assertTrue(needsRenewal(key, now = 1_000_000 - 60_000))
        assertFalse(needsRenewal(Session.LoginKey("k".repeat(64)), now = Long.MAX_VALUE))
    }

    @Test
    fun aRenewableKeyIsStoredWithItsRefresh() {
        val key = Session.LoginKey("k".repeat(64), "r".repeat(64), 123)
        assertEquals(key, Session.decode(Session.encode(key)))
        val plain = Session.LoginKey("k".repeat(64))
        assertEquals(plain, Session.decode(Session.encode(plain)))
        assertFalse(key.toString().contains("kkk"))
    }

    @Test
    fun readsTheComputerPagesCode() {
        val cookies = readSignInCode("glorp-signin:1:destiny.gg:abc123:def456", site)!!
        assertEquals("abc123", cookies.sid)
        assertEquals("def456", cookies.rememberMe)
        assertEquals(null, readSignInCode("glorp-signin:1:destiny.gg:abc123:", site)!!.rememberMe)
        // A code for the test site doesn't sign in here.
        assertNull(readSignInCode("glorp-signin:1:omniliberal.dev:abc123:def456", site))
        assertNull(readSignInCode("hello", site))
    }
}
