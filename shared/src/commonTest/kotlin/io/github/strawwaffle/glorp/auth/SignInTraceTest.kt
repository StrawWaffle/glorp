package io.github.strawwaffle.glorp.auth

import io.github.strawwaffle.glorp.diag.ProblemReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInTraceTest {
    @Test
    fun pagesLoseTheirQueryAndFragment() {
        assertEquals("id.kick.com/oauth/authorize", SignInTrace.pageOf("https://id.kick.com/oauth/authorize?client_id=x&code=secret#frag"))
        assertEquals("www.destiny.gg/profile", SignInTrace.pageOf("https://www.destiny.gg/profile/"))
        assertEquals("www.destiny.gg", SignInTrace.pageOf("https://www.destiny.gg/?sid=abc"))
        assertNull(SignInTrace.pageOf("about:blank"))
        assertNull(SignInTrace.pageOf("intent://x"))
    }

    @Test
    fun providersComeFromTheFirstProviderHost() {
        assertEquals("twitch", SignInTrace.providerOf("id.twitch.tv"))
        assertEquals("google", SignInTrace.providerOf("accounts.google.com"))
        assertEquals("kick", SignInTrace.providerOf("id.kick.com"))
        assertEquals("x", SignInTrace.providerOf("api.twitter.com"))
        assertEquals("x", SignInTrace.providerOf("x.com"))
        assertEquals("reddit", SignInTrace.providerOf("www.reddit.com"))
        assertEquals("discord", SignInTrace.providerOf("discord.com"))
        assertNull(SignInTrace.providerOf("www.destiny.gg"))
        assertNull(SignInTrace.providerOf("notkick.com"))
    }

    @Test
    fun anAttemptIsToldStepByStepWithoutValues() {
        val lines = ArrayList<String>()
        var t = 1_000L
        val trace = SignInTrace(now = { t }, log = { lines += it })
        trace.visited("https://www.destiny.gg/login?follow=/profile")
        trace.visited("https://id.kick.com/oauth/authorize?state=s3cret")
        trace.visited("https://id.kick.com/oauth/authorize?state=other")
        trace.error(400, "https://id.kick.com/oauth/authorize?code=c0de")
        trace.visited("https://www.destiny.gg/auth/kick?code=c0de")
        trace.landed("https://www.destiny.gg/profile")
        trace.cookies(sid = true, rememberMe = false)
        t += 42_000
        trace.finished(null)
        trace.cancelled()
        assertEquals(
            listOf(
                "at www.destiny.gg/login",
                "provider kick",
                "at id.kick.com/oauth/authorize",
                "error 400 at id.kick.com",
                "at www.destiny.gg/auth/kick",
                "back at www.destiny.gg/profile",
                "cookies sid yes, rememberme no",
                "signed in after 42 s",
            ),
            lines,
        )
        assertFalse(lines.any { "s3cret" in it || "c0de" in it })
    }

    @Test
    fun aClosedAttemptSaysWhere() {
        val lines = ArrayList<String>()
        val trace = SignInTrace(now = { 0L }, log = { lines += it })
        trace.visited("https://discord.com/login")
        trace.cancelled()
        assertEquals("cancelled at discord.com after 0 s", lines.last())
    }

    @Test
    fun aReportKeepsTheSignInLinesOfABusyLog() {
        val log = listOf("00:00:01 signin: provider kick", "00:00:02 signin: cookies sid yes, rememberme yes") +
            (1..200).map { "00:01:00 images: picture$it.png didn't load (IOException), again in 1 s" }
        val details = ProblemReport("x", "1", "d", null, log).details(budget = 2_000)
        assertTrue("signin: provider kick" in details)
        assertTrue("signin: cookies sid yes" in details)
        assertTrue("picture200.png" in details)
        assertFalse("picture1.png" in details)
    }
}
