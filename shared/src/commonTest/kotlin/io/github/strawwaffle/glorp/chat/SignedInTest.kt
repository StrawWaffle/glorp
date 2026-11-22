package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.auth.Session
import io.github.strawwaffle.glorp.ui.mentions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignedInTest {
    private val now = { 5L }

    @Test
    fun readsWhoTheServerThinksWeAre() {
        val me = assertIs<ChatEvent.Me>(parseFrame("""ME {"nick":"glorp","features":["flair3"],"subscription":{"tier":2}}""", now)).account
        assertEquals("glorp", me?.nick)
        assertEquals(2, me?.subTier)
        assertNull(assertIs<ChatEvent.Me>(parseFrame("ME null", now)).account)
    }

    @Test
    fun readsErrorsAndKnowsWhichEndTheConnection() {
        val error = assertIs<ChatEvent.Error>(parseFrame("""ERR {"description":"throttled"}""", now))
        assertEquals("throttled", error.code)
        assertTrue(errorText("throttled").startsWith("Throttled"))
        assertTrue(isFatal("banned"))
        assertFalse(isFatal("throttled"))
    }

    @Test
    fun readsWhispers() {
        val line = assertIs<ChatEvent.Message>(
            parseFrame("""PRIVMSG {"nick":"friend","data":"hi","timestamp":9,"messageid":123}""", now),
        ).line
        assertEquals(ChatLine.Kind.Whisper, line.kind)
        assertEquals("friend", line.nick)
        assertEquals("whisper\u0000123", line.id)
    }

    @Test
    fun readsLoginKeysAndCookies() {
        val key = "a".repeat(64)
        assertEquals("authtoken=$key", Session.loginKey(" $key\n")?.cookieHeader)
        assertNull(Session.loginKey("not a key"))
        val cookies = Session.cookies(" sid=abc ", "def")
        assertEquals("sid=abc; rememberme=def", cookies?.cookieHeader)
        assertNull(Session.cookies("ab c", ""))
        // Stored and read back the same.
        assertEquals(Session.LoginKey(key), Session.decode(Session.encode(Session.LoginKey(key))))
        assertEquals(cookies, Session.decode(Session.encode(cookies!!)))
        assertEquals(Session.Cookies("abc", null), Session.decode("c\nabc\n"))
        // Printing a session never shows the values.
        assertFalse("abc" in cookies.toString())
        assertFalse(key in Session.LoginKey(key).toString())
    }

    @Test
    fun mentionsAreWholeNames() {
        assertTrue(mentions("hey Glorp, look", "glorp"))
        assertTrue(mentions("glorp", "Glorp"))
        assertFalse(mentions("glorpy things", "glorp"))
    }
}
