package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InboxTest {
    @Test
    fun countsUnreadUntilOpened() {
        var inbox = Inbox().received("Glorp", "hi", 1_000, "1").received("glorp", "there", 2_000, "2")
        assertEquals(2, inbox.unread)
        assertEquals("there", inbox["GLORP"]!!.preview)
        inbox = inbox.opened("glorp")
        assertEquals(0, inbox.unread)
        // While open, new whispers there aren't unread.
        assertEquals(0, inbox.received("glorp", "again", 3_000, "3").unread)
        assertEquals(1, inbox.closed().received("glorp", "again", 3_000, "3").unread)
    }

    @Test
    fun mergesHistoryWithoutDoubles() {
        val inbox = Inbox()
            .received("a", "live one", 5_000, "9")
            .sent("a", "me", "my reply", 6_000)
            .history(
                "a",
                listOf(
                    Whisper("8", "a", "older", 1_000, false),
                    Whisper("9", "a", "live one", 5_000, false),
                    Whisper("10", "me", "my reply", 6_200, true),
                ),
            )
        assertEquals(listOf("older", "live one", "my reply"), inbox["a"]!!.messages.map { it.text })
        assertTrue(inbox["a"]!!.loaded)
    }

    @Test
    fun keepsWhatTheSessionKnewOverAnOlderList() {
        val inbox = Inbox().received("a", "new", 9_000, "1")
            .listed(listOf(Conversation("a", lastAt = 5_000, preview = "old"), Conversation("b", lastAt = 7_000, preview = "b's", unread = 2)))
        assertEquals("new", inbox["a"]!!.preview)
        assertEquals(listOf("a", "b"), inbox.sorted.map { it.nick })
        assertEquals(3, inbox.unread)
    }

    @Test
    fun readsTheSitesDates() {
        assertEquals(1_763_779_966_000L, siteMillis(JsonPrimitive("2025-11-22T02:52:46+0000")))
        assertEquals(1_763_779_966_000L, siteMillis(JsonPrimitive("2025-11-22T02:52:46Z")))
        assertEquals(42L, siteMillis(JsonPrimitive(42)))
    }

    @Test
    fun readsWhisperSentAndOpen() {
        assertIs<ChatEvent.WhisperSent>(parseFrame("PRIVMSGSENT {}", { 0L }))
        assertEquals(Command.Open("glorp"), parseCommand("/open glorp", mod = false))
    }
}
