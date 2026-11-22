package io.github.strawwaffle.glorp.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollApiTest {
    // Trimmed from api.mitchdev.net/dgg/polls on 2026-11-22.
    private val page = """{"nextCursor":{"before":"1795064721"},"totalCount":18040,"pageSize":50,"data":[
      {"id":1795318698,"question":"When lawsuit end?","options":["2026","2027","2028","Neva eva temmieDank"],"votes":[10,46,29,88],"total_votes":173,"weighted":0,"started_by":"Bing","duration":20000,"start_time":"2026-11-22T03:37:58.000Z","end_time":"2026-11-22T03:38:18.000Z","created_at":"2026-11-22T16:39:25.000Z"},
      {"id":1544472830,"question":"Is this a question?","options":["Yes","No"],"votes":[0,0],"total_votes":0,"weighted":1,"started_by":"MrMouton","duration":30000,"start_time":"2018-12-10T20:13:50.000Z","end_time":null,"created_at":null,"extra":true}
    ]}"""

    @Test
    fun readsAPage() {
        val parsed = PollApi.parsePage(page)
        assertEquals(PollCursor("before", "1795064721"), parsed.next)
        assertEquals(18040, parsed.totalCount)
        assertEquals(2, parsed.polls.size)
        val first = parsed.polls[0]
        assertEquals("When lawsuit end?", first.question)
        assertEquals(listOf(10, 46, 29, 88), first.votes)
        assertEquals(173, first.sum)
        assertEquals("Bing", first.startedBy)
        assertEquals(setOf(3), first.winners)
        assertEquals(1795318678000L, first.startedAt)
        val second = parsed.polls[1]
        assertTrue(second.isWeighted)
        assertEquals(emptySet(), second.winners)
    }

    @Test
    fun readsTheLastPageAndOldestFirst() {
        assertNull(PollApi.parsePage("""{"nextCursor":null,"totalCount":0,"pageSize":50,"data":[]}""").next)
        assertEquals(
            PollCursor("after", "1551061637"),
            PollApi.parsePage("""{"nextCursor":{"after":"1551061637"},"totalCount":1,"data":[]}""").next,
        )
    }

    @Test
    fun tiesShareTheWin() {
        val poll = Poll(id = 1, options = listOf("a", "b", "c"), votes = listOf(5, 2, 5))
        assertEquals(setOf(0, 2), poll.winners)
        assertEquals(12, poll.sum)
    }

    @Test
    fun readsOnePollMakersAndYeePepe() {
        val poll = PollApi.parsePoll("""{"data":{"id":7,"question":"q","options":["x"],"votes":[3],"total_votes":3,"weighted":0,"started_by":"Titan"}}""")
        assertEquals(7, poll.id)
        assertEquals("Titan", poll.startedBy)
        assertEquals(
            listOf(PollMaker("RiN_LuX", 7965), PollMaker("Bot", 2327)),
            PollApi.parseMakers("""[{"name":"RiN_LuX","count":7965},{"name":"Bot","count":2327}]"""),
        )
        assertEquals(
            YeePepe(61149, 55717),
            PollApi.parseYeePepe("""{"totalYee":61149,"totalPepe":55717,"points":[{"time":1552445570,"yeeVotes":46,"pepeVotes":37,"question":"Pepe vs YEE?","startedBy":"MrMouton"}]}"""),
        )
    }

    @Test
    fun writesNumbersAndDates() {
        assertEquals("18,040", grouped(18040))
        assertEquals("173", grouped(173))
        assertEquals("1,000,000", grouped(1_000_000))
        assertEquals("22 Nov 2026, 03:37 UTC", utcDate(1795318678000L, withTime = true))
        assertEquals("10 Dec 2018", utcDate(1544472830000L))
        assertEquals("29 Feb 2024", utcDate(1709208000000L))
        val now = 1795318678000L
        assertEquals("just now", whenText(now - 5_000, now))
        assertEquals("6 h ago", whenText(now - 6 * 3_600_000L - 60_000, now))
        assertEquals("3 days ago", whenText(now - 3 * 86_400_000L, now))
        assertEquals("10 Dec 2018", whenText(1544472830000L, now))
    }
}
