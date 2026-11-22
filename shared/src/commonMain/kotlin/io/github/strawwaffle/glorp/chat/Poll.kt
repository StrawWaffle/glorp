package io.github.strawwaffle.glorp.chat

import kotlin.math.roundToInt

/**
 * The poll running in chat, held as chat-gui's `ChatPoll` holds it. Votes
 * come in as counts per option; a hidden poll's come without the option, so
 * only the total moves until it ends.
 */
data class Poll(
    val question: String,
    val options: List<String>,
    val totals: List<Long>,
    val votes: Long,
    val by: String,
    val weighted: Boolean,
    val hidden: Boolean,
    /** When it started and how long it runs, on this device's clock. */
    val startedAt: Long,
    val durationMs: Long,
    /** The option this account voted for, from 1; 0 for none. */
    val myVote: Int,
    val canVote: Boolean,
    val ended: Boolean = false,
    /** Hidden by the reader; `/showpoll` brings it back. */
    val dismissed: Boolean = false,
) {
    val endsAt: Long get() = startedAt + durationMs

    fun percent(option: Int): Double = if (votes > 0) totals[option] * 100.0 / votes else 0.0

    /** The winning option, from 0, once the results are known. Ties go to the first, as in chat-gui. */
    val winner: Int?
        get() = if (!ended || hidden || totals.isEmpty()) null
        else totals.indices.fold(0) { best, i -> if (totals[i] > totals[best]) i else best }

    /** chat-gui's heading: "Sub-weighted poll started by nick for 30 seconds." */
    val heading: String
        get() {
            val kind = if (weighted) "sub-weighted poll" else "poll"
            val name = if (hidden) "Hidden $kind" else kind.replaceFirstChar { it.uppercase() }
            return "$name started by $by for ${durationMs / 1000} seconds."
        }

    fun isVote(text: String): Boolean {
        if (text.isEmpty() || !text.all { it.isDigit() } || text.length > 4) return false
        return text.toInt() in 1..options.size
    }

    /** One vote of [quantity] (a sub-weighted vote counts more) for [option], from 1. */
    fun cast(option: Int, quantity: Long): Poll {
        val counted = if (hidden || option !in 1..totals.size) totals
        else totals.toMutableList().also { it[option - 1] += quantity }
        return copy(totals = counted, votes = votes + quantity)
    }

    fun startText(): String {
        val numbers = options.indices.joinToString(" or ") { "${it + 1}" }
        val start = if (weighted) "A sub-weighted poll has been started. The value of your vote depends on your subscription tier."
        else "A poll has been started."
        return "$start Type $numbers in chat to participate." + if (hidden) " Results are hidden until the poll ends." else ""
    }

    fun endText(): String {
        val win = winner ?: return "The poll has ended."
        val share = percent(win)
        return if (share > 0) "The poll has ended. Option ${win + 1} won with ${share.roundToInt()}% of the vote."
        else "The poll has ended. Option ${win + 1} won!"
    }

    companion object {
        /** How long an ended poll stays up, as in chat-gui. */
        const val END_SHOWN_MS = 7_000L
    }
}

/** A `/poll` being started: what goes to the server as `STARTPOLL`. */
data class PollRequest(
    val question: String,
    val options: List<String>,
    val timeMs: Long,
    val hidden: Boolean,
    val weighted: Boolean,
)

private val conjunction = Regex("""\bor\b""", RegexOption.IGNORE_CASE)
private val interrogative = Regex("""^(how|why|when|what|where)\b""", RegexOption.IGNORE_CASE)
private val pollTime = Regex("""\b([0-9]+(?:m|s))$""", RegexOption.IGNORE_CASE)
private const val DEFAULT_TIME = 30_000L
private const val MIN_TIME = 5_000L
private const val MAX_TIME = 10 * 60 * 1000L

/**
 * Reads `/poll [--hidden] [--weighted] question? a or b [30s]` as chat-gui
 * does; null when it isn't a poll chat-gui would start.
 */
fun parsePoll(args: List<String>): PollRequest? {
    var hidden = false
    var weighted = false
    var i = 0
    while (i < args.size) {
        when (args[i].lowercase()) {
            "--hidden" -> hidden = true
            "--weighted" -> weighted = true
            else -> break
        }
        i++
    }
    val raw = args.drop(i).joinToString(" ")
    val timeWord = pollTime.find(raw)?.value
    val time = when {
        timeWord == null -> DEFAULT_TIME
        timeWord.endsWith("s", ignoreCase = true) -> timeWord.dropLast(1).toLongOrNull()?.times(1000)
        else -> timeWord.dropLast(1).toLongOrNull()?.times(60_000)
    } ?: DEFAULT_TIME
    val text = raw.replace(pollTime, "").trim()
    if ('?' !in text) return null
    val parts = text.split('?')
    val question = parts[0] + "?"
    val after = parts[1]
    val options = if (after.isBlank()) listOf("Yes", "No") else {
        val split = after.split(conjunction).map { it.trim() }
        if (split.size < 2 && interrogative.containsMatchIn(question)) return null
        split
    }
    return PollRequest(question, options, time.coerceIn(MIN_TIME, MAX_TIME), hidden, weighted)
}

/** Who may start and stop polls, as chat-gui's `hasPermission`. */
fun mayRunPolls(roles: List<String>): Boolean =
    roles.any { it.equals("admin", true) || it.equals("moderator", true) || it.equals("polls", true) }
