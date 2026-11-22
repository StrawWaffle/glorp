package io.github.strawwaffle.glorp.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustleApiTest {
    @Test
    fun readsASearchPage() {
        val body = """{"type":"Success","error":null,"data":{"count":0,"countLimit":true,"messages":[
            {"channel":"Destinygg","text":"am i going to that","ts":"2026-11-22T07:09:13.361Z","username":"destiny","searchAfter":1795331353361},
            {"channel":"Destinygg","text":"wtf is that","ts":"2026-11-22T07:09:11.729Z","username":"destiny","searchAfter":1795331351729}]}}"""
        val messages = parseSearch(body)
        assertEquals(2, messages.size)
        assertEquals("am i going to that", messages[0].text)
        assertEquals("2026-11-22T07:09:13.361Z", messages[0].ts)
        assertEquals(1795331351729L, messages.last().searchAfter)
    }

    @Test
    fun readsAnEmptyPage() {
        assertTrue(parseSearch("""{"type":"Success","error":null,"data":{"count":0,"countLimit":false,"messages":[]}}""").isEmpty())
    }

    @Test
    fun readsAnError() {
        assertEquals(
            "\"Start Date\" must be in iso format",
            errorOf("""{"type":"BadSchema","error":"\"Start Date\" must be in iso format","data":null}"""),
        )
        assertNull(errorOf("<html>bad gateway</html>"))
    }

    @Test
    fun readsChannels() {
        assertEquals(
            listOf("Destinygg", "Vaushgg"),
            parseChannels("""{"type":"Success","error":null,"data":{"channels":["Destinygg","Vaushgg"]}}"""),
        )
    }

    @Test
    fun readsSurroundingLines() {
        val body = """{"type":"Success","error":null,"data":{"lines":[
            "[2026-11-22 07:07:11.591 UTC] triumph: for once Radiant hesRight",
            "[2026-11-22 07:09:11.729 UTC] destiny: wtf is that",
            "[2026-11-22 07:10:15.350 UTC] kartaoj: time: 12:30"],
            "matched":"[2026-11-22 07:09:11.729 UTC] destiny: wtf is that","username":"destiny"}}"""
        val surrounds = parseSurrounds(body)
        assertEquals(3, surrounds.lines.size)
        assertEquals(1, surrounds.matched)
        assertEquals(LogLine(1795331351729L, "destiny", "wtf is that"), surrounds.lines[1])
        // Only the first colon ends the name.
        assertEquals("kartaoj", surrounds.lines[2].nick)
        assertEquals("time: 12:30", surrounds.lines[2].text)
    }

    @Test
    fun readsTheQuota() {
        assertEquals(Quota(200, 190, 38705), parseQuota("limit=200, remaining=190, reset=38705"))
        assertNull(parseQuota(null))
        assertNull(parseQuota("garbage"))
    }
}
