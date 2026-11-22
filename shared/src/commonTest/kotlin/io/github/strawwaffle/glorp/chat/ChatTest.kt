package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.Flairs
import io.github.strawwaffle.glorp.site.Site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ChatTest {
    private val now = { 1_000L }

    @Test
    fun readsAMessage() {
        val event = parseFrame("""MSG {"nick":"glorp","features":["subscriber"],"timestamp":42,"data":">be me"}""", now)
        val line = assertIs<ChatEvent.Message>(event).line
        assertEquals("glorp", line.nick)
        assertEquals(42L, line.at)
        assertTrue(line.green)
    }

    @Test
    fun readsMeAsAnAction() {
        val line = assertIs<ChatEvent.Message>(parseFrame("""MSG {"nick":"a","data":"/me waves"}""", now)).line
        assertTrue(line.action)
        assertEquals("waves", line.text)
        assertEquals(1_000L, line.at)
    }

    @Test
    fun unpacksHistory() {
        val history = """HISTORY ["MSG {\"nick\":\"a\",\"data\":\"one\",\"timestamp\":1}","MSG {\"nick\":\"b\",\"data\":\"two\",\"timestamp\":2}"]"""
        val lines = assertIs<ChatEvent.History>(parseFrame(history, now)).lines
        assertEquals(listOf("one", "two"), lines.map { it.text })
    }

    @Test
    fun skipsWhatItDoesNotUse() {
        assertNull(parseFrame("""JOIN {"nick":"someone"}""", now))
        assertNull(parseFrame("MSG not json", now))
        assertNull(parseFrame("", now))
    }

    @Test
    fun logKeepsItsCapAndDropsRepeats() {
        val log = ChatLog(capacity = 3)
        val lines = (1..5).map { ChatLine.user("n", emptyList(), "m$it", it.toLong()) }
        lines.forEach(log::add)
        assertEquals(listOf("m3", "m4", "m5"), log.snapshot().map { it.text })
        // The backlog on reconnect repeats lines already shown.
        assertEquals(false, log.add(lines[4]))
        // A line that fell off the end can come back.
        assertEquals(true, log.add(lines[0]))
    }

    @Test
    fun removalTakesEveryLineByThatName() {
        val log = ChatLog()
        log.add(ChatLine.user("Spammer", emptyList(), "a", 1))
        log.add(ChatLine.user("other", emptyList(), "b", 2))
        log.add(ChatLine.user("spammer", emptyList(), "c", 3))
        assertTrue(log.removeNick("SPAMMER"))
        assertEquals(listOf("b"), log.snapshot().map { it.text })
    }

    @Test
    fun backoffStaysInsideItsWindow() {
        assertEquals(500.milliseconds, retryDelay(1) { 0.0 })
        assertEquals(6.seconds, retryDelay(1) { 1.0 })
        assertEquals(60.seconds, retryDelay(10) { 1.0 })
    }

    @Test
    fun firstListedFlairColoursTheName() {
        val flairs = Flairs.parse("""[{"name":"flair17","color":"#FCE205"},{"name":"admin","color":"#EE1F1F"},{"name":"flair9","color":null}]""")
        assertEquals(0xFFFCE205, flairs.nameColor(listOf("admin", "flair17")))
        assertEquals(0xFFEE1F1F, flairs.nameColor(listOf("admin", "flair9")))
        assertNull(flairs.nameColor(listOf("flair9")))
    }

    @Test
    fun siteComesFromTheChatPage() {
        val html = """<html data-ws-url="wss://chat.omniliberal.dev/ws" data-cdn="https://stagingr2cdn.destiny.gg">"""
        val site = Site.fromChatPage("https://www.omniliberal.dev", html)
        assertEquals("wss://chat.omniliberal.dev/ws", site?.chatSocketUrl)
        assertEquals("https://stagingr2cdn.destiny.gg", site?.cdn)
        assertEquals("wss://chat.destiny.gg/ws", Site.guess("https://www.destiny.gg").chatSocketUrl)
    }
}
