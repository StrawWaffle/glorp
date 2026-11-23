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
        val log = ChatLog(capacity = ChatLog.MIN_CAPACITY)
        val lines = (1..52).map { ChatLine.user("n", emptyList(), "m$it", it.toLong()) }
        lines.forEach(log::add)
        assertEquals((3..52).map { "m$it" }, log.snapshot().map { it.text })
        // The backlog on reconnect repeats lines already shown.
        assertEquals(false, log.add(lines[51]))
        // A line that fell off the end can come back.
        assertEquals(true, log.add(lines[0]))
    }

    @Test
    fun lowerMaxLinesDropsTheOldestNow() {
        val log = ChatLog(capacity = 100)
        (1..80).forEach { log.add(ChatLine.user("n", emptyList(), "m$it", it.toLong())) }
        log.capacity = 60
        assertEquals((21..80).map { "m$it" }, log.snapshot().map { it.text })
        log.capacity = 5
        assertEquals(ChatLog.MIN_CAPACITY, log.capacity)
        assertEquals(ChatLog.MIN_CAPACITY, log.snapshot().size)
    }

    @Test
    fun keepsLinesWhileReadingBack() {
        val log = ChatLog(capacity = ChatLog.MIN_CAPACITY)
        val line = { n: Int -> ChatLine.user("n", emptyList(), "m$n", n.toLong()) }
        (1..ChatLog.MIN_CAPACITY).forEach { log.add(line(it)) }
        // Scrolled up: the oldest line, the one being read, stays however busy chat gets.
        log.holding = true
        (ChatLog.MIN_CAPACITY + 1..ChatLog.MIN_CAPACITY + 300).forEach { log.add(line(it)) }
        assertEquals("m1", log.snapshot().first().text)
        assertEquals(ChatLog.MIN_CAPACITY + 300, log.snapshot().size)
        // Back at the bottom: the usual number again, the newest kept.
        log.holding = false
        assertEquals(ChatLog.MIN_CAPACITY, log.snapshot().size)
        assertEquals("m${ChatLog.MIN_CAPACITY + 300}", log.snapshot().last().text)
    }

    @Test
    fun readingBackHasACeiling() {
        val log = ChatLog(capacity = ChatLog.MIN_CAPACITY)
        log.holding = true
        (1..ChatLog.HOLD_LIMIT + 10).forEach { log.add(ChatLine.user("n", emptyList(), "m$it", it.toLong())) }
        assertEquals(ChatLog.HOLD_LIMIT, log.snapshot().size)
    }

    @Test
    fun maxLinesComesFromTheSettingWithinLimits() {
        val settings = ChatSettings(MemoryChatPrefs())
        assertEquals(250, settings.maxLines)
        settings.set("maxlines", kotlinx.serialization.json.JsonPrimitive(500))
        assertEquals(500, settings.maxLines)
        settings.set("maxlines", kotlinx.serialization.json.JsonPrimitive(99999))
        assertEquals(ChatLog.MAX_CAPACITY, settings.maxLines)
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
    fun aMuteMarksTheirMessagesSoFar() {
        val log = ChatLog()
        log.add(ChatLine.user("Spammer", emptyList(), "a", 1))
        log.add(ChatLine.user("other", emptyList(), "b", 2))
        assertTrue(log.moderate("SPAMMER"))
        // Already marked: nothing changes.
        assertEquals(false, log.moderate("spammer"))
        log.add(ChatLine.user("spammer", emptyList(), "after", 3))
        assertEquals(listOf(true, false, false), log.snapshot().map { it.moderated })
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
