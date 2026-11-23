package io.github.strawwaffle.glorp.diag

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReportTest {
    @Test
    fun addressesAndLoginsLoseTheirSecrets() {
        assertEquals(
            "fetch https://usher.ttvnw.net/api/channel/hls/x.m3u8?… failed",
            redact("fetch https://usher.ttvnw.net/api/channel/hls/x.m3u8?sig=abc&token=%7B%22a%22%7D failed"),
        )
        assertEquals("cookie sid=… rememberme=…", redact("cookie sid=s3cr3t rememberme=abc.def"))
        assertEquals("nothing here", redact("nothing here"))
    }

    @Test
    fun theLogIsNotedRedacted() {
        AppLog.clear()
        AppLog.note("player", "failed at https://example.com/a.m3u8?token=x")
        val line = AppLog.lines().single()
        assertTrue(line.endsWith("player: failed at https://example.com/a.m3u8?…"), line)
        assertFalse("token" in line)
    }

    @Test
    fun theTitleComesFromWhatHappenedOrTheCrash() {
        val crash = crashText(IllegalStateException("boom"), "main", "0.1.23", "2026-11-23T10:00:00Z", emptyList())
        assertEquals("Crash: java.lang.IllegalStateException: boom", report(what = "", crash = crash).title)
        assertEquals("Player stuck on Kick", report(what = "\nPlayer stuck on Kick\nafter a while").title)
        assertEquals("Problem report", report(what = " ").title)
    }

    @Test
    fun theLinkFillsTheFormAndFitsWithTheNewestLog() {
        val log = (1..2_000).map { "10:00:00 chat: line $it with some words" }
        val link = report(what = "Chat stops & doesn't come back", log = log).issueLink()
        assertTrue(link.length <= 7_500, "${link.length}")
        val url = Url(link)
        assertEquals("problem.yml", url.parameters["template"])
        assertEquals("Chat stops & doesn't come back", url.parameters["what"])
        assertEquals("0.1.23", url.parameters["version"])
        val details = url.parameters["details"]!!
        assertTrue("line 2000 with" in details, "keeps the newest")
        assertFalse("line 1 with" in details, "drops the oldest")
        assertTrue("earlier lines left out" in details)
    }

    @Test
    fun aShortReportKeepsEverything() {
        val text = report(what = "x", log = listOf("a", "b")).text()
        assertTrue(text.endsWith("Recent log (UTC):\na\nb"), text)
        assertFalse("left out" in text)
    }

    private fun report(what: String, crash: String? = null, log: List<String> = emptyList()) =
        ProblemReport(what, "0.1.23", "Google Pixel 9, Android 16 (API 36)", crash, log)
}
