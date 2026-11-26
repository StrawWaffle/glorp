package io.github.strawwaffle.glorp.notify

import io.github.strawwaffle.glorp.chat.ChatPrefs
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.github.strawwaffle.glorp.community.DINKDONK
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/*
 * Movie nights on dinkdonk.mov (by Mitch). Every poll and every rating opens
 * with a chat broadcast that is just its link, and closes after about three
 * minutes (five for a rating): far too soon for the background check. So:
 *
 * - with chat connected, the broadcast itself notifies;
 * - during a movie night, a quiet socket to Glorp's server (server/votes.go)
 *   carries only the votes, held by a foreground service on Android;
 * - the background check asks the server whether a night is on, to start
 *   (or offer) that listener, and the schedule gives a reminder before a
 *   booked session.
 */

/** A dinkdonk poll or rating that just opened. [kind] is "poll" or "rate". */
data class MovieVote(val kind: String, val id: String, val title: String?, val at: Long) {
    val url: String get() = "$DINKDONK/$kind/$id"
}

/** The movie-night notification settings, kept on this phone. */
class MovieNightOptions(private val prefs: ChatPrefs) {
    val votes: Boolean get() = prefs.get(VOTES) == "true"
    fun setVotes(on: Boolean) = prefs.put(VOTES, on.toString())

    val reminders: Boolean get() = prefs.get(REMIND) == "true"
    fun setReminders(on: Boolean) = prefs.put(REMIND, on.toString())

    val any: Boolean get() = votes || reminders

    /** Until when (ms) the server last said a movie night is on; 0 when it isn't. */
    var nightUntil: Long
        get() = prefs.get(NIGHT_UNTIL)?.toLongOrNull() ?: 0
        set(value) = prefs.put(NIGHT_UNTIL, value.toString())

    /**
     * Whether [vote] hasn't been told yet, noting that it has. The chat, the
     * listener and the background check can all hear the same vote.
     */
    fun firstTime(vote: MovieVote): Boolean {
        val key = "${vote.kind}/${vote.id}"
        val told = prefs.get(TOLD)?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        if (key in told) return false
        prefs.put(TOLD, (told + key).takeLast(40).joinToString(" "))
        return true
    }

    companion object {
        private const val VOTES = "movienight.votes"
        private const val REMIND = "movienight.remind"
        private const val NIGHT_UNTIL = "movienight.until"
        private const val TOLD = "movienight.told"

        /** Glorp's server, where the votes come from (left by the running app). */
        const val SERVER = "movienight.server"
    }
}

private val voteLink = Regex("""https://(?:www\.)?dinkdonk\.mov/(poll|rate)/([A-Za-z0-9]{3,12})\b""")

/** A chat broadcast announcing a dinkdonk vote, or null. */
fun voteFromBroadcast(text: String, at: Long): MovieVote? =
    voteLink.find(text)?.let { MovieVote(it.groupValues[1], it.groupValues[2], null, at) }

/** The notification for a vote: tapping it opens the vote on dinkdonk.mov. */
fun voteAlert(vote: MovieVote): Alert {
    val poll = vote.kind == "poll"
    val title = vote.title?.takeIf { it.isNotBlank() }
    return Alert(
        Alert.Kind.Vote,
        if (poll) "Movie night poll: vote now" else "Rate it" + (title?.let { ": $it" } ?: ""),
        listOfNotNull(if (poll) title else null, if (poll) "Open for about 3 minutes" else "Open for about 5 minutes")
            .joinToString(" · "),
        vote.url,
    )
}

/** What the server says: the votes open now, and until when the night is on (0: it isn't). */
data class MovieNightState(val open: List<MovieVote>, val nightUntil: Long)

internal fun parseVote(o: JsonObject): MovieVote? {
    val kind = (o["kind"] as? JsonPrimitive)?.contentOrNull?.takeIf { it == "poll" || it == "rate" } ?: return null
    val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    return MovieVote(kind, id, (o["title"] as? JsonPrimitive)?.contentOrNull, (o["at"] as? JsonPrimitive)?.longOrNull ?: 0)
}

/** `{"open":[…],"nightUntil":ms}` from `/votes`, or the live socket's `state` message. */
fun parseMovieNightState(body: String): MovieNightState? = runCatching {
    val o = chatJson.parseToJsonElement(body).jsonObject
    MovieNightState(
        (o["open"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseVote) },
        (o["nightUntil"] as? JsonPrimitive)?.longOrNull ?: 0,
    )
}.getOrNull()

/** One message on the live socket: a new vote, a fresh state, or nothing this app reads. */
sealed interface VoteMessage {
    data class Opened(val vote: MovieVote) : VoteMessage
    data class State(val state: MovieNightState) : VoteMessage
}

fun parseVoteMessage(text: String): VoteMessage? = runCatching {
    val o = chatJson.parseToJsonElement(text).jsonObject
    when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
        "vote" -> (o["vote"] as? JsonObject)?.let(::parseVote)?.let(VoteMessage::Opened)
        "state" -> parseMovieNightState(text)?.let(VoteMessage::State)
        else -> null
    }
}.getOrNull()

/** Asks Glorp's server whether a movie night is on (a few hundred bytes); null if it couldn't. */
suspend fun askMovieNight(client: HttpClient, server: String): MovieNightState? = try {
    val response = client.get(server.trimEnd('/') + "/votes") { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
    if (response.status.isSuccess()) parseMovieNightState(response.bodyAsText()) else null
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** The server's live socket address for [server] (https → wss). */
fun votesSocketUrl(server: String) = server.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/votes/live"

/** A reminder for a booked session: when it starts (ms). */
fun movieNightReminder(start: Long, now: Long): Alert {
    val minutes = ((start - now) / 60_000).coerceAtLeast(0)
    return Alert(
        Alert.Kind.MovieNight,
        if (minutes <= 1) "Movie night is starting" else "Movie night in $minutes minutes",
        "On dinkdonk.mov's schedule. Glorp can listen for the polls (they close after about 3 minutes).",
        "$DINKDONK/schedule",
        listOf(AlertAction(LISTEN_LABEL, LISTEN_COMMAND)),
    )
}

/** A movie night going on without the listener: offered, since Android won't let it start itself. */
fun movieNightOn(): Alert = Alert(
    Alert.Kind.MovieNight,
    "Movie night is on",
    "Polls and ratings are opening in chat. Listen for them, so each one notifies while it's open.",
    "$DINKDONK/schedule",
    listOf(AlertAction(LISTEN_LABEL, LISTEN_COMMAND)),
)

const val LISTEN_LABEL = "Listen for votes"
const val LISTEN_COMMAND = "movienight.listen"
