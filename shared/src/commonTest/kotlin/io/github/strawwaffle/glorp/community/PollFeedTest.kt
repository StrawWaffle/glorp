package io.github.strawwaffle.glorp.community

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollFeedTest {
    /** A fake archive of [count] polls, newest (highest id) first, [size] a page. */
    private fun archive(count: Int, size: Int, asked: MutableList<Pair<PollCursor?, PollQuery>>): suspend (PollCursor?, PollQuery) -> PollPage =
        { cursor, query ->
            asked += cursor to query
            val before = cursor?.value?.toLong() ?: (count + 1L)
            val ids = (before - 1 downTo 1).take(size)
            val next = if (ids.isNotEmpty() && ids.last() > 1) PollCursor("before", ids.last().toString()) else null
            PollPage(ids.map { Poll(id = it) }, next, count)
        }

    @Test
    fun pagesByCursorUntilTheEnd() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<Pair<PollCursor?, PollQuery>>()
        val feed = PollFeed(TestScope(testScheduler)) { c, q -> archive(120, 50, asked)(c, q) }
        feed.show(PollQuery())
        testScheduler.advanceUntilIdle()
        assertEquals(50, feed.polls.size)
        assertEquals(120, feed.total)
        feed.loadMore(); testScheduler.advanceUntilIdle()
        feed.loadMore(); testScheduler.advanceUntilIdle()
        assertEquals(120, feed.polls.size)
        assertTrue(feed.done)
        feed.loadMore(); testScheduler.advanceUntilIdle()
        assertEquals(3, asked.size)
        assertEquals(listOf(null, PollCursor("before", "71"), PollCursor("before", "21")), asked.map { it.first })
    }

    @Test
    fun keepsAtMostMaxPages() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<Pair<PollCursor?, PollQuery>>()
        val feed = PollFeed(TestScope(testScheduler)) { c, q -> archive(10_000, 50, asked)(c, q) }
        feed.show(PollQuery())
        testScheduler.advanceUntilIdle()
        repeat(PollFeed.MAX_PAGES + 2) { feed.loadMore(); testScheduler.advanceUntilIdle() }
        assertEquals(PollFeed.MAX_PAGES * 50, feed.polls.size)
        assertTrue(feed.trimmed)
        assertEquals(10_000L - 3 * 50, feed.polls.first().id)
    }

    @Test
    fun aNewQueryStartsOver() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<Pair<PollCursor?, PollQuery>>()
        val feed = PollFeed(TestScope(testScheduler)) { c, q -> archive(120, 50, asked)(c, q) }
        feed.show(PollQuery())
        testScheduler.advanceUntilIdle()
        feed.loadMore(); testScheduler.advanceUntilIdle()
        feed.show(PollQuery(startedBy = "Bing"))
        testScheduler.advanceUntilIdle()
        assertEquals(50, feed.polls.size)
        assertFalse(feed.trimmed)
        assertNull(asked.last().first)
        assertEquals("Bing", asked.last().second.startedBy)
        // The same query again doesn't fetch.
        feed.show(PollQuery(startedBy = "Bing"))
        assertEquals(3, asked.size)
    }

    @Test
    fun anErrorCanBeRetried() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val feed = PollFeed(TestScope(testScheduler)) { _, _ ->
            if (fail) error("mitchdev.net answered 503")
            PollPage(listOf(Poll(id = 1)), null, 1)
        }
        feed.show(PollQuery())
        testScheduler.advanceUntilIdle()
        assertEquals("mitchdev.net answered 503", feed.error)
        fail = false
        feed.loadMore(); testScheduler.advanceUntilIdle()
        assertNull(feed.error)
        assertEquals(1, feed.polls.size)
    }
}
