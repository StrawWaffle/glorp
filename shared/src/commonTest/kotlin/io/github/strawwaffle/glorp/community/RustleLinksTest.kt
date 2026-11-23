package io.github.strawwaffle.glorp.community

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RustleLinksTest {
    @Test
    fun aSearchLinkIsItsQuery() {
        val link = rustleLinkOf("https://rustlesearch.dev/?username=mrmouton&text=YeeLaugh%20&start_date=2010-01-01&end_date=2026-11-23&channel=Destinygg")
        assertEquals(RustleLink.Search(RustleQuery("mrmouton", "YeeLaugh", "Destinygg", "2010-01-01", "2026-11-23")), link)
        // Operators and other channels come through as written.
        val q = (rustleLinkOf("https://www.rustlesearch.dev/?text=%22a%20phrase%22%20%7C%20b&channel=Xqcow") as RustleLink.Search).query
        assertEquals("\"a phrase\" | b", q.text)
        assertEquals("Xqcow", q.channel)
        assertNull(q.startDate)
    }

    @Test
    fun aSurroundsLinkIsOneMessage() {
        val link = rustleLinkOf("https://rustlesearch.dev/surrounds?channel=Destinygg&date=2026-11-23T02%3A23%3A32.944Z&username=mrmouton")
        val message = (link as RustleLink.Around).message
        assertEquals("mrmouton", message.username)
        assertEquals("2026-11-23T02:23:32.944Z", message.ts)
        assertEquals(1795400612944, message.at)
        assertEquals("https://rustlesearch.dev/surrounds?channel=Destinygg&date=2026-11-23T02%3A23%3A32.944Z&username=mrmouton", message.siteLink())
    }

    @Test
    fun otherLinksAreNot() {
        assertNull(rustleLinkOf("https://example.com/?username=a"))
        assertNull(rustleLinkOf("https://rustlesearch.dev/removals"))
        assertNull(rustleLinkOf("not a link"))
    }

    @Test
    fun theSiteLinkRoundTrips() {
        val q = RustleQuery("a", "b | c", "Destinygg", "2026-01-01", "2026-02-01")
        assertEquals(RustleLink.Search(q), rustleLinkOf(q.siteLink()))
    }

    @Test
    fun quickRanges() {
        val now = 1795400612944 // 2026-11-23
        assertEquals("2026-11-23" to "2026-11-23", dateRange(DateRange.Today, now))
        assertEquals("2026-10-23" to "2026-11-23", dateRange(DateRange.Month, now))
        assertEquals("2025-11-23" to "2026-11-23", dateRange(DateRange.Year, now))
        // 31 March less a month is the end of February.
        assertEquals("2028-02-29", dateRange(DateRange.Month, 1838073600000).first)
    }
}
