package io.github.strawwaffle.glorp.community

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RustleSearchTest {
    /** Pages of [PAGE] messages counting down from [newest], [total] in all. */
    private class FakeSource(private val newest: Long = 1_000_000, private val total: Int = 1000) : RustleSource {
        val asked = ArrayList<Long?>()
        var reply: RustleReply<List<LogMessage>>? = null

        override suspend fun search(query: RustleQuery, searchAfter: Long?): RustleReply<List<LogMessage>> {
            asked += searchAfter
            reply?.let { return it }
            val first = searchAfter?.let { it - 1 } ?: newest
            val left = (first - (newest - total)).toInt().coerceAtLeast(0)
            val page = (0 until minOf(RustleApi.PAGE, left)).map { i ->
                val at = first - i
                LogMessage("Destinygg", "message $at", "ts", "someone", at)
            }
            return RustleReply.Ok(page, Quota(200, 150, 3600))
        }

        override suspend fun surrounds(message: LogMessage) = RustleReply.Ok(Surrounds(emptyList(), null), null)
        override suspend fun channels() = RustleReply.Ok(listOf("Destinygg"), null)
    }

    private val query = RustleQuery(username = "someone")

    @Test
    fun pagesOnUntilTheCapThenReplaces() {
        val scope = TestScope()
        val source = FakeSource()
        val state = SearchState(source, scope)
        state.search(query)
        scope.runCurrent()
        assertEquals(RustleApi.PAGE, state.results.size)
        assertTrue(state.hasMore)
        repeat(4) { state.more(); scope.runCurrent() }
        assertEquals(MAX_KEPT, state.results.size)
        assertTrue(state.capped)
        // Each page continues from the last message's searchAfter.
        assertEquals(listOf(null, 999_901L, 999_801L, 999_701L, 999_601L), source.asked)
        val generation = state.generation
        state.more()
        scope.runCurrent()
        assertEquals(RustleApi.PAGE, state.results.size)
        assertEquals(999_500L, state.results.first().searchAfter)
        assertEquals(generation + 1, state.generation)
    }

    @Test
    fun aShortPageIsTheLast() {
        val scope = TestScope()
        val source = FakeSource(total = 150)
        val state = SearchState(source, scope)
        state.search(query)
        scope.runCurrent()
        state.more()
        scope.runCurrent()
        assertEquals(150, state.results.size)
        assertFalse(state.hasMore)
        state.more()
        scope.runCurrent()
        assertEquals(2, source.asked.size)
    }

    @Test
    fun anEmptySearchAsksNothing() {
        val scope = TestScope()
        val source = FakeSource()
        val state = SearchState(source, scope)
        state.search(RustleQuery())
        scope.runCurrent()
        assertTrue(source.asked.isEmpty())
        assertNotNull(state.error)
    }

    @Test
    fun waitsOutTheLimit() {
        val scope = TestScope()
        val source = FakeSource()
        var now = 0L
        val state = SearchState(source, scope, now = { now })
        source.reply = RustleReply.Limited(Quota(200, 0, 7200))
        state.search(query)
        scope.runCurrent()
        assertEquals(7_200_000L, state.limitedUntil)
        source.reply = null
        state.search(query)
        scope.runCurrent()
        assertEquals(1, source.asked.size)
        now = 7_200_000L
        state.search(query)
        scope.runCurrent()
        assertEquals(2, source.asked.size)
        assertEquals(RustleApi.PAGE, state.results.size)
    }
}
