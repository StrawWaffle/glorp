package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.chat.MemoryChatPrefs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovieNightTest {
    @Test
    fun aBroadcastLinkIsAVote() {
        assertEquals(MovieVote("poll", "90Ypt", null, 5), voteFromBroadcast("https://dinkdonk.mov/poll/90Ypt", 5))
        assertEquals("https://dinkdonk.mov/rate/wSQyO", voteFromBroadcast("https://dinkdonk.mov/rate/wSQyO", 1)?.url)
        assertNull(voteFromBroadcast("Check out the movie schedule https://dinkdonk.mov/schedule", 1))
    }

    @Test
    fun eachVoteIsToldOnce() {
        val options = MovieNightOptions(MemoryChatPrefs())
        val vote = MovieVote("poll", "a", null, 1)
        assertTrue(options.firstTime(vote))
        assertFalse(options.firstTime(vote))
        assertTrue(options.firstTime(vote.copy(kind = "rate")))
    }

    @Test
    fun theServersMessages() {
        val state = parseMovieNightState("""{"open":[{"kind":"poll","id":"v928d","url":"x","title":"Continue tomorrow?","at":9}],"nightUntil":123}""")!!
        assertEquals(123, state.nightUntil)
        assertEquals("Continue tomorrow?", state.open.single().title)
        val opened = parseVoteMessage("""{"type":"vote","vote":{"kind":"rate","id":"wSQyO","title":"Community - S1E1 • Pilot","at":7}}""")
        assertEquals(VoteMessage.Opened(MovieVote("rate", "wSQyO", "Community - S1E1 • Pilot", 7)), opened)
        assertEquals(0, (parseVoteMessage("""{"type":"state","open":[],"nightUntil":0}""") as VoteMessage.State).state.nightUntil)
        assertNull(parseVoteMessage("""{"type":"other"}"""))
        assertEquals("wss://glorp-api.strawwaffle.dev/votes/live", votesSocketUrl("https://glorp-api.strawwaffle.dev/"))
    }

    @Test
    fun whatTheNotificationsSay() {
        val poll = voteAlert(MovieVote("poll", "a", "SEEN MOVIES ALLOWED", 0))
        assertEquals("Movie night poll: vote now", poll.title)
        assertEquals("SEEN MOVIES ALLOWED · Open for about 3 minutes", poll.text)
        assertEquals("https://dinkdonk.mov/poll/a", poll.open)
        assertEquals("Rate it: Pilot", voteAlert(MovieVote("rate", "b", "Pilot", 0)).title)
        assertEquals("Movie night in 15 minutes", movieNightReminder(15 * 60_000L, 0).title)
    }
}
