package io.github.strawwaffle.glorp.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PollTest {
    private val now = { 100_000L }

    private val start = """POLLSTART {"canvote":true,"myvote":0,"weighted":false,"hidden":false,""" +
        """"start":"2026-11-22T12:00:00Z","now":"2026-11-22T12:00:10Z","time":30000,""" +
        """"question":"Is it good?","options":["Yes","No"],"totals":[3,1],"nick":"mod","totalvotes":4}"""

    @Test
    fun readsAPollOnTheDevicesClock() {
        val poll = assertIs<ChatEvent.PollStart>(parseFrame(start, now)).poll
        assertEquals(listOf("Yes", "No"), poll.options)
        assertEquals(4L, poll.votes)
        // Ten seconds in by the server's clock, whatever this device's says.
        assertEquals(90_000L, poll.startedAt)
        assertEquals(120_000L, poll.endsAt)
        assertTrue(poll.canVote)
        assertEquals("Poll started by mod for 30 seconds.", poll.heading)
    }

    @Test
    fun takesTimesInMilliseconds() {
        val frame = """POLLSTART {"start":5000,"now":6000,"time":10000,"question":"Q?","options":["a","b"],"nick":"x"}"""
        val poll = assertIs<ChatEvent.PollStart>(parseFrame(frame, now)).poll
        assertEquals(99_000L, poll.startedAt)
        assertEquals(listOf(0L, 0L), poll.totals)
    }

    @Test
    fun countsVotesButNotHiddenOnes() {
        val poll = assertIs<ChatEvent.PollStart>(parseFrame(start, now)).poll
        val vote = assertIs<ChatEvent.VoteCast>(parseFrame("""VOTECAST {"vote":"2","quantity":3}""", now))
        val counted = poll.cast(vote.option, vote.quantity)
        assertEquals(listOf(3L, 4L), counted.totals)
        assertEquals(7L, counted.votes)
        val hidden = poll.copy(hidden = true).cast(0, 1)
        assertEquals(listOf(3L, 1L), hidden.totals)
        assertEquals(5L, hidden.votes)
    }

    @Test
    fun namesTheWinnerOnceItEnds() {
        val poll = assertIs<ChatEvent.PollStart>(parseFrame(start, now)).poll
        assertNull(poll.winner)
        val ended = poll.copy(ended = true)
        assertEquals(0, ended.winner)
        assertEquals("The poll has ended. Option 1 won with 75% of the vote.", ended.endText())
        assertEquals("The poll has ended.", ended.copy(hidden = true).endText())
    }

    @Test
    fun readsTheEndAndHiddenResults() {
        val stop = assertIs<ChatEvent.PollStop>(parseFrame("""POLLSTOP {"totals":[1,5],"totalvotes":6}""", now))
        assertEquals(listOf(1L, 5L), stop.totals)
        assertNull(assertIs<ChatEvent.PollStop>(parseFrame("POLLSTOP {}", now)).totals)
        assertEquals(2, assertIs<ChatEvent.VoteCounted>(parseFrame("""VOTECOUNTED {"vote":2}""", now)).option)
    }

    @Test
    fun knowsAVoteFromAMessage() {
        val poll = assertIs<ChatEvent.PollStart>(parseFrame(start, now)).poll
        assertTrue(poll.isVote("2"))
        assertFalse(poll.isVote("3"))
        assertFalse(poll.isVote("0"))
        assertFalse(poll.isVote("2 please"))
    }

    @Test
    fun readsPollCommandsAsChatGuiDoes() {
        val yesNo = parsePoll("Is Glorp good?".split(" "))!!
        assertEquals(listOf("Yes", "No"), yesNo.options)
        assertEquals(30_000L, yesNo.timeMs)

        val timed = parsePoll("--hidden --weighted Best emote? PEPE or YEE or glorp 2m".split(" "))!!
        assertEquals("Best emote?", timed.question)
        assertEquals(listOf("PEPE", "YEE", "glorp"), timed.options)
        assertEquals(120_000L, timed.timeMs)
        assertTrue(timed.hidden && timed.weighted)

        assertEquals(5_000L, parsePoll("Quick? a or b 1s".split(" "))!!.timeMs)
        assertEquals(600_000L, parsePoll("Long? a or b 60m".split(" "))!!.timeMs)
        assertNull(parsePoll("no question mark".split(" ")))
        assertNull(parsePoll("What now? nothing".split(" ")))
    }

    @Test
    fun pollCommandsNeedThePollsRole() {
        assertTrue(mayRunPolls(listOf("POLLS")))
        assertTrue(mayRunPolls(listOf("MODERATOR")))
        assertFalse(mayRunPolls(listOf("subscriber", "flair3")))
        assertIs<Command.StartPoll>(parseCommand("/poll Ok? a or b", mod = false))
        assertIs<Command.StopPoll>(parseCommand("/vs", mod = false))
    }

    @Test
    fun readsThePinnedMessage() {
        val pin = assertIs<ChatEvent.Pin>(
            parseFrame("""PIN {"uuid":"u1","nick":"mod","features":[],"data":"Read the rules","timestamp":7}""", now),
        )
        assertEquals("u1", pin.uuid)
        assertEquals("Read the rules", pin.line?.text)
        assertNull(assertIs<ChatEvent.Pin>(parseFrame("""PIN {"uuid":"u2","nick":"mod","data":""}""", now)).line)
    }

    @Test
    fun readsRolesOffMe() {
        val me = assertIs<ChatEvent.Me>(parseFrame("""ME {"nick":"m","features":[],"roles":["MODERATOR"]}""", now))
        assertTrue(me.account!!.modPowers)
    }
}
