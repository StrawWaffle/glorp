package io.github.strawwaffle.glorp.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputTest {
    @Test
    fun findsTheWordAtTheCaret() {
        assertEquals(Word(6, 9, "PE", false), wordAt("hello PEP there", 8))
        assertEquals(Word(1, 4, "ab", true), wordAt("@abc", 3))
        assertEquals(Word(1, 3, "gr", true), wordAt(">gr", 3))
        assertNull(wordAt("hello ", 6))
        assertNull(wordAt("", 0))
    }

    @Test
    fun completesAndInserts() {
        val word = wordAt("hi PEP", 6)!!
        assertEquals("hi PEPE " to 8, complete("hi PEP", word, "PEPE"))
        // A space already after the word is reused.
        val middle = wordAt("a Pe b", 4)!!
        assertEquals("a PEPE b" to 7, complete("a Pe b", middle, "PEPE"))
        assertEquals("hi PEPE " to 8, insertWord("hi", 2, "PEPE"))
        assertEquals("PEPE " to 5, insertWord("", 0, "PEPE"))
        assertEquals("a PEPE b" to 7, insertWord("a b", 2, "PEPE"))
    }

    @Test
    fun suggestsEmotesFirstThenRecentNames() {
        val c = Completer()
        c.setEmotes(listOf("PEPE", "Pog", "OMEGALUL", "peepoHey"))
        c.setRoster(listOf("Pete", "pancake", "Zed"))
        c.heard("Paul", 10)
        c.heard("Pam", 20)
        val got = c.suggest(wordAt("p", 1)!!, atStart = true).map { it.text }
        assertEquals(listOf("peepoHey", "PEPE", "Pog", "Pam", "Paul", "pancake", "Pete"), got)
        // `@` asks for names only; skipped names (yourself, the ignored) don't show.
        val names = c.suggest(wordAt("@pa", 3)!!, atStart = true, skip = { it == "pam" }).map { it.text }
        assertEquals(listOf("Paul", "pancake"), names)
    }

    @Test
    fun putsWhatWasPickedFirst() {
        val c = Completer()
        c.setEmotes(listOf("PEPE", "Pog", "peepoHey"))
        c.picked("Pog", 100)
        assertEquals("Pog", c.suggest(wordAt("p", 1)!!, atStart = true).first().text)
        assertEquals(listOf("Pog"), c.recentEmotes(5))
    }

    @Test
    fun offersCommandsOnlyAtTheStart() {
        val c = Completer()
        c.setCommands(commandWords(mod = false))
        val got = c.suggest(wordAt("/wh", 3)!!, atStart = true).map { it.text }
        assertEquals(listOf("/whisper"), got)
        assertTrue(c.suggest(wordAt("x /wh", 5)!!, atStart = false).isEmpty())
        assertTrue("/ban" !in commandWords(mod = false))
        assertTrue("/ban" in commandWords(mod = true))
    }

    @Test
    fun keepsOnlyTheMostRecentSpeakers() {
        val c = Completer(recentCap = 2)
        c.heard("aaa", 1); c.heard("abb", 2); c.heard("acc", 3)
        assertEquals(listOf("acc", "abb"), c.suggest(wordAt("a", 1)!!, atStart = true).map { it.text })
    }

    @Test
    fun readsNamesFromTheRoster() {
        val payload = """{"connectioncount":3,"users":[{"nick":"one","features":["subscriber"]},{"nick":"Two","features":[]}]}"""
        assertEquals(listOf("one", "Two"), rosterNicks(payload))
        val names = assertIs<ChatEvent.Names>(parseFrame("NAMES $payload") { 0 })
        assertEquals(payload, names.payload)
    }

    @Test
    fun stepsThroughHistoryLikeTheArrowKeys() {
        val prefs = MemoryChatPrefs()
        val h = InputHistory(prefs, max = 3)
        listOf("one", "two", "two", "three", "four").forEach(h::add)
        assertEquals(listOf("four", "three", "two"), h.recent)
        assertEquals("four", h.older("draft"))
        assertEquals("three", h.older("ignored"))
        assertEquals("two", h.older(""))
        assertNull(h.older(""))
        assertEquals("three", h.newer())
        assertEquals("four", h.newer())
        assertEquals("draft", h.newer())
        assertNull(h.newer())
        // Kept between launches.
        assertEquals(listOf("four", "three", "two"), InputHistory(prefs, max = 3).recent)
    }

    @Test
    fun keepsIgnoredNamesWithoutCase() {
        val prefs = MemoryChatPrefs()
        val ignored = NickSet(ChatSettings(prefs), "ignorenicks")
        assertEquals(listOf("bob"), ignored.add(listOf("Bob", "bob")))
        assertTrue("BOB" in ignored)
        assertTrue(NickSet(ChatSettings(prefs), "ignorenicks").contains("bob"))
        assertEquals(listOf("bob"), ignored.remove(listOf("BoB", "nobody")))
        assertTrue(NickSet(ChatSettings(prefs), "ignorenicks").nicks.isEmpty())
    }

    @Test
    fun readsMessagesAndWhispers() {
        assertEquals(Command.Say("hello"), parseCommand("hello", false))
        assertEquals(Command.Say("/me waves"), parseCommand("/me waves", false))
        assertEquals(Command.Say("/ not a command"), parseCommand("/ not a command", false))
        assertEquals(Command.Whisper("friend", "hi  there"), parseCommand("/w friend hi  there", false))
        assertEquals(Command.Whisper("friend", "hi"), parseCommand("/TELL friend hi", false))
        assertIs<Command.Problem>(parseCommand("/w friend", false))
        assertIs<Command.Problem>(parseCommand("/w x! hi", false))
        assertEquals(Command.Reply, parseCommand("/r", false))
    }

    @Test
    fun readsListCommands() {
        assertEquals(Command.Ignore(listOf("aaa", "bbb")), parseCommand("/ignore aaa bbb", false))
        assertEquals(Command.Ignore(emptyList()), parseCommand("/ignore", false))
        assertIs<Command.Problem>(parseCommand("/ignore ab", false))
        assertEquals(Command.Unignore(listOf("aaa")), parseCommand("/unignore aaa", false))
        assertIs<Command.Problem>(parseCommand("/unignoreall", false))
        assertEquals(Command.UnignoreAll, parseCommand("/unignoreall yes", false))
        assertEquals(Command.Highlight("aaa", true), parseCommand("/highlight aaa", false))
        assertEquals(Command.Highlight(null, false), parseCommand("/unhighlight", false))
        assertEquals(Command.Help, parseCommand("/help", false))
    }

    @Test
    fun sendsModeratorCommandsAsChatGuiDoes() {
        val mute = assertIs<Command.Send>(parseCommand("/mute troll 10m", true))
        assertEquals("MUTE", mute.event)
        assertEquals("troll", mute.payload["data"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(600_000_000_000L), mute.payload["duration"])
        assertNull(assertIs<Command.Send>(parseCommand("/mute troll", true)).payload["duration"])

        val ban = assertIs<Command.Send>(parseCommand("/ban troll perm being rude", true))
        assertEquals("BAN", ban.event)
        assertEquals(JsonPrimitive(true), ban.payload["ispermanent"])
        assertEquals(JsonPrimitive("being rude"), ban.payload["reason"])
        assertEquals(JsonPrimitive(false), ban.payload["banip"])
        val ipban = assertIs<Command.Send>(parseCommand("/ipban troll 1d spam", true))
        assertEquals(JsonPrimitive(86_400_000_000_000L), ipban.payload["duration"])
        assertEquals(JsonPrimitive(true), ipban.payload["banip"])
        assertIs<Command.Problem>(parseCommand("/ban troll 1d", true))

        val sub = assertIs<Command.Send>(parseCommand("/subonly 3", true))
        assertEquals(JsonPrimitive(3), sub.payload["tier"])
        assertEquals("""PIN {"data":""}""".substringAfter(' '), assertIs<Command.Send>(parseCommand("/unpin", true)).payload.toString())
        assertEquals("DIE", assertIs<Command.Send>(parseCommand("/die", false)).event)
        assertIs<Command.Problem>(parseCommand("/addphrase word", false))
        assertEquals("ADDPHRASE", assertIs<Command.Send>(parseCommand("/addban word", true)).event)
    }

    @Test
    fun saysWhatItDoesNotDo() {
        assertTrue(assertIs<Command.Problem>(parseCommand("/stalk someone", false)).text.startsWith("/stalk isn't in Glorp"))
        assertEquals(Command.Problem("Unknown command. Try /help"), parseCommand("/frobnicate", false))
    }

    @Test
    fun readsDurationsLikeChatGui() {
        assertEquals(30_000_000_000L, durationNanos("30"))
        assertEquals(7_200_000_000_000L, durationNanos("2h"))
        assertEquals(0L, durationNanos("soon"))
    }
}
