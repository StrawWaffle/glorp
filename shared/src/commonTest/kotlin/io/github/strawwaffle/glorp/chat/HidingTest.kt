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
}
