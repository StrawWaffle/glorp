package io.github.strawwaffle.glorp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebSignInTest {
    private val origin = "https://www.destiny.gg"

    @Test
    fun startsOnTheLoginPageFollowingToTheProfile() {
        assertEquals("https://www.destiny.gg/login?follow=/profile", WebSignIn.startUrl(origin))
        assertEquals("https://www.omniliberal.dev/login?follow=/profile", WebSignIn.startUrl("https://www.omniliberal.dev/"))
    }

    @Test
    fun readsCookieManagerStrings() {
        val cookies = WebSignIn.parseCookies("_ga=GA1.1.2; sid=abc123; rememberme=%7B%22x%22%3A1%7D; empty=")
        assertEquals("abc123", cookies["sid"])
        assertEquals("%7B%22x%22%3A1%7D", cookies["rememberme"])
        assertEquals("", cookies["empty"])
        assertEquals(emptyMap(), WebSignIn.parseCookies(null))
        assertEquals(emptyMap(), WebSignIn.parseCookies("  "))
        // The first of a name is the most specific path.
        assertEquals("first", WebSignIn.parseCookies("sid=first; sid=second")["sid"])
        // Values may hold '='.
        assertEquals("a=b==", WebSignIn.parseCookies("rememberme=a=b==")["rememberme"])
    }

    @Test
    fun turnsCookiesIntoASession() {
        val both = WebSignIn.session("theme=dark; sid=abc; rememberme=def")
        assertEquals(Session.Cookies("abc", "def"), both)
        assertEquals(Session.Cookies("abc", null), WebSignIn.session("sid=abc"))
        assertNull(WebSignIn.session("rememberme=def"))
        assertNull(WebSignIn.session("sid=; rememberme=def"))
        assertNull(WebSignIn.session(null))
    }

    @Test
    fun knowsWhenTheLoginLanded() {
        assertTrue(WebSignIn.landedSignedIn("https://www.destiny.gg/profile", origin))
        assertTrue(WebSignIn.landedSignedIn("https://www.destiny.gg/profile/", origin))
        assertTrue(WebSignIn.landedSignedIn("https://destiny.gg/profile/authentication", origin))
        assertTrue(WebSignIn.landedSignedIn("https://www.destiny.gg/profile?x=1#top", origin))
        assertTrue(WebSignIn.landedSignedIn("https://www.omniliberal.dev/profile", "https://www.omniliberal.dev"))

        assertFalse(WebSignIn.landedSignedIn("https://www.destiny.gg/login?follow=/profile", origin))
        assertFalse(WebSignIn.landedSignedIn("https://www.destiny.gg/", origin))
        assertFalse(WebSignIn.landedSignedIn("https://www.destiny.gg/profiles", origin))
        assertFalse(WebSignIn.landedSignedIn("https://www.destiny.gg/auth/twitch?code=x", origin))
        assertFalse(WebSignIn.landedSignedIn("https://www.twitch.tv/profile", origin))
        assertFalse(WebSignIn.landedSignedIn("https://evil.example/www.destiny.gg/profile", origin))
        assertFalse(WebSignIn.landedSignedIn("https://www.destiny.gg.evil.example/profile", origin))
        assertFalse(WebSignIn.landedSignedIn("http://www.destiny.gg/profile", origin))
        assertFalse(WebSignIn.landedSignedIn("https://www.destiny.gg/profile", "https://www.omniliberal.dev"))
        assertFalse(WebSignIn.landedSignedIn("about:blank", origin))
        assertFalse(WebSignIn.landedSignedIn(null, origin))
    }

    @Test
    fun findsTheLoginPage() {
        assertTrue(WebSignIn.isLoginPage("https://www.destiny.gg/login?follow=/profile", origin))
        assertTrue(WebSignIn.isLoginPage("https://destiny.gg/login", origin))
        assertFalse(WebSignIn.isLoginPage("https://id.twitch.tv/login", origin))
        assertFalse(WebSignIn.isLoginPage("https://www.destiny.gg/profile", origin))
    }
}
