package io.github.strawwaffle.glorp.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagsTest {
    @Test
    fun tagsAreKeptAsChatGuiKeepsThem() {
        val settings = ChatSettings(MemoryChatPrefs())
        val tags = Tags(settings)
        tags.tag("Bob", "red", "a note")
        tags.tag("alice", "sky", "")
        assertEquals(mapOf("bob" to "red", "alice" to "sky"), tags.colors)
        assertEquals("a note", tags.notes["bob"])
        assertEquals("""[["bob","red"],["alice","sky"]]""", settings["taggednicks"].toString())
        tags.untag("BOB")
        assertEquals(mapOf("alice" to "sky"), tags.colors)
        assertEquals(setOf("alice"), tags.notes.keys)
        tags.clear()
        assertTrue(tags.colors.isEmpty())
    }

    @Test
    fun tagArgumentsAreReadAsChatGuiReadsThem() {
        val random = { "green" }
        assertEquals("green" to "old", readTag(emptyList(), "old", random))
        assertEquals("red" to "old", readTag(listOf("RED"), "old", random))
        assertEquals("red" to "likes cats", readTag(listOf("red", "likes", "cats"), "old", random))
        // Not a colour: a random one, and every word is the note.
        assertEquals("green" to "likes cats", readTag(listOf("likes", "cats"), "", random))
        assertEquals(100, readTag(listOf("x".repeat(150)), "", random).second.length)
    }

    @Test
    fun theListReadsLikeChatGuis() {
        assertEquals("No tagged nicks.", tagList(emptyMap(), emptyMap()))
        assertEquals(
            "Tagged nicks\n\n    bob (red) a note\n    alice (sky)",
            tagList(mapOf("bob" to "red", "alice" to "sky"), mapOf("bob" to "a note")),
        )
    }

    @Test
    fun tagCommands() {
        assertEquals(Command.Tag(null, emptyList()), parseCommand("/tag", false))
        assertEquals(Command.Tag("bob", listOf("red", "hi")), parseCommand("/tag bob red hi", false))
        assertTrue(parseCommand("/tag b!", false) is Command.Problem)
        assertEquals(Command.Untag("bob"), parseCommand("/untag bob", false))
        assertEquals(Command.UntagAll, parseCommand("/untagall y", false))
        assertTrue(parseCommand("/untagall", false) is Command.Problem)
    }

    @Test
    fun highlights() {
        val h = Highlighter("Me", self = true, nicks = listOf("Bob"), words = listOf("cats", "dog+s"))
        assertTrue(h.highlights("bob", "anything"))
        assertTrue(h.highlights("carol", "hey me"))
        assertFalse(h.highlights("carol", "https://example.com/me"))
        assertTrue(h.highlights("carol", "I like CATS"))
        assertFalse(h.highlights("carol", "concatsenate"))
        // A word is a pattern, as on the site.
        assertTrue(h.highlights("carol", "doggggs"))
        // The sender counts for words.
        assertTrue(h.highlights("cats", "hello"))
        // Your own messages never are.
        assertFalse(h.highlights("me", "cats"))
        assertFalse(Highlighter("me", self = false, emptyList(), emptyList()).highlights("x", "me"))
    }

    @Test
    fun aBrokenPatternIsPlainText() {
        val h = Highlighter(null, self = false, emptyList(), listOf("a(b"))
        assertTrue(h.highlights("x", "say a(b now"))
    }

    @Test
    fun wordsAreSplitAtCommas() {
        assertEquals(listOf("cats", "big dogs"), highlightWords(" cats, big dogs,, cats ,"))
    }
}
