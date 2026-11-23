package io.github.strawwaffle.glorp.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HidingTest {
    private fun line(nick: String, text: String) = ChatLine.user(nick, emptyList(), text, 1L, 0)

    @Test
    fun hidesAsChatGuiAndTheUserscriptDo() {
        val filter = LineFilter("me", listOf("bob"), ignoreMentions = true, hideNsfw = true, hideNsfl = false, phrases = listOf("spoiler", "/sp[o0]+ns/"))
        assertTrue(filter.hides(line("carol", "hey Bob look")))
        assertFalse(filter.hides(line("carol", "hey bobby")))
        assertTrue(filter.hides(line("carol", "nsfw link")))
        assertFalse(filter.hides(line("carol", "nsfl link")))
        assertTrue(filter.hides(line("carol", "big SPOILER ahead")))
        assertTrue(filter.hides(line("carol", "sp00ns")))
        // Your own messages always show.
        assertFalse(filter.hides(line("Me", "nsfw spoiler bob")))
        assertFalse(filter.hides(line("carol", "nothing here")))
    }

    @Test
    fun nothingOnHidesNothing() {
        val filter = LineFilter(null, listOf("bob"), ignoreMentions = false, hideNsfw = false, hideNsfl = false, phrases = emptyList())
        assertTrue(filter.isEmpty)
        assertFalse(filter.hides(line("carol", "bob nsfw")))
    }

    @Test
    fun alertWordsWarnLast() {
        assertEquals(Warning.Kind.Alert, checkDraft("my secret plan", emptyList(), null, emptyList(), listOf("secret"))?.kind)
        assertEquals(Warning.Kind.Nuke, checkDraft("secret glorp", emptyList(), null, listOf(Nuke("glorp", "mute", "1m")), listOf("secret"))?.kind)
    }

    @Test
    fun hiddenOptionsAreKept() {
        val prefs = MemoryChatPrefs()
        LocalOptions(prefs).apply {
            hidePhrases(listOf("a"))
            setFlairHidden("flair14", true)
            alertOn(listOf("b"))
        }
        val again = LocalOptions(prefs)
        assertEquals(listOf("a"), again.hiddenPhrases)
        assertEquals(setOf("flair14"), again.hiddenFlairs)
        assertEquals(listOf("b"), again.alertWords)
    }

    @Test
    fun readsWhatTheSenderWatches() {
        val frame = """MSG {"nick":"Pal","features":[],"watching":{"platform":"Kick","id":"AnythingElse"},"timestamp":5,"data":"hi"}"""
        val line = (parseFrame(frame) { 0L } as ChatEvent.Message).line
        assertEquals("kick/anythingelse", line.watching)
        val none = (parseFrame("""MSG {"nick":"Pal","watching":null,"timestamp":5,"data":"hi"}""") { 0L } as ChatEvent.Message).line
        assertEquals(null, none.watching)
    }

    @Test
    fun otherStreamsAreThoseKnownToWatchAnother() {
        fun from(nick: String, watching: String?) = ChatLine.user(nick, emptyList(), "hi", 1L, 0, watching)
        assertTrue(fromOtherStream(from("pal", "twitch/someone"), "me", "kick/Destiny"))
        assertFalse(fromOtherStream(from("pal", "kick/destiny"), "me", "kick/Destiny"))
        // Watching nothing on bigscreen (on a phone, say), yourself, or nothing playing here.
        assertFalse(fromOtherStream(from("pal", null), "me", "kick/destiny"))
        assertFalse(fromOtherStream(from("Me", "twitch/someone"), "me", "kick/destiny"))
        assertFalse(fromOtherStream(from("pal", "twitch/someone"), "me", null))
    }

    @Test
    fun theOtherStreamsChoiceIsKept() {
        val prefs = MemoryChatPrefs()
        assertEquals(OtherStreams.Show, LocalOptions(prefs).otherStreams)
        LocalOptions(prefs).treatOtherStreams(OtherStreams.Hide)
        assertEquals(OtherStreams.Hide, LocalOptions(prefs).otherStreams)
    }

    @Test
    fun nameIconsAreKeptByLowerCaseNick() {
        val prefs = MemoryChatPrefs()
        LocalOptions(prefs).setIcon("Ivory", "💩")
        LocalOptions(prefs).setIcon("Pal", "👨‍👩‍👧‍👦")
        assertEquals(mapOf("ivory" to "💩", "pal" to "👨‍👩‍👧‍👦"), LocalOptions(prefs).nameIcons)
        LocalOptions(prefs).setIcon("IVORY", " ")
        assertEquals(mapOf("pal" to "👨‍👩‍👧‍👦"), LocalOptions(prefs).nameIcons)
    }
}
