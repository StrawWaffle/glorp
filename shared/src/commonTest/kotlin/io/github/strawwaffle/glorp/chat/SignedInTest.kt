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
        assertEquals("glorp", assertIs<ChatEvent.Me>(parseFrame("""ME {"nick":"glorp","features":[]}""", now)).nick)
        assertNull(assertIs<ChatEvent.Me>(parseFrame("ME null", now)).nick)
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
    fun readsSessionsFromCookies() {
        val session = Session.fromCookieHeader("_ga=x; sid=abc; rememberme=def; other=1")
        assertEquals("abc", session?.sid)
        assertEquals("def", session?.rememberMe)
        assertEquals("sid=abc; rememberme=def", session?.cookieHeader)
        assertNull(Session.fromCookieHeader("rememberme=def"))
        assertEquals("abc", Session.fromPasted(" sid=abc ", "")?.sid)
        assertNull(Session.fromPasted("ab c", ""))
        // Printing a session never shows the values.
        assertFalse("abc" in Session("abc", "def").toString())
    }

    @Test
    fun mentionsAreWholeNames() {
        assertTrue(mentions("hey Glorp, look", "glorp"))
        assertTrue(mentions("glorp", "Glorp"))
        assertFalse(mentions("glorpy things", "glorp"))
    }
}
