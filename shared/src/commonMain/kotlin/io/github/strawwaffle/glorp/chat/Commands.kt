package io.github.strawwaffle.glorp.chat

import io.github.strawwaffle.glorp.site.LookBack
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What a line typed into the input asks for, read as chat-gui reads it. */
sealed interface Command {
    /** A chat message, `/me` ones included: chat-gui sends those as they are. */
    data class Say(val text: String) : Command
    data class Whisper(val nick: String, val text: String) : Command
    data object Reply : Command

    /** Empty [nicks] asks for the list. */
    data class Ignore(val nicks: List<String>) : Command
    data class Unignore(val nicks: List<String>) : Command
    data object UnignoreAll : Command

    /** Null [nick] asks for the list. */
    data class Highlight(val nick: String?, val on: Boolean) : Command

    /** Null [nick] asks for the list; [args] are the colour and note. */
    data class Tag(val nick: String?, val args: List<String>) : Command
    data class Untag(val nick: String?) : Command
    data object UntagAll : Command
    data object Help : Command

    /** Null [format] asks for the current one. */
    data class TimestampFormat(val format: String?) : Command
    data object EmoteList : Command

    /** Opens [link] in the player, as `/embed` sets bigscreen's hash. */
    data class Watch(val link: String) : Command

    /** `/postembed [link] [message]`: posts a link or, without one, what is playing. */
    data class PostEmbed(val args: List<String>) : Command

    /** Goes to the chat server as an event of its own, the way chat-gui sends it. */
    data class Send(val event: String, val payload: JsonObject) : Command

    /** `/poll`: null [request] when it couldn't be read, to show the usage. */
    data class StartPoll(val request: PollRequest?, val name: String) : Command
    data object StopPoll : Command
    data object ShowPoll : Command

    /** `/stalk [nick] [limit]`: someone's last messages; null [nick] is you. */
    data class Stalk(val nick: String?, val limit: Int) : Command

    /** `/mentions [nick] [limit]`: the last messages naming someone; null [nick] is you. */
    data class Mentions(val nick: String?, val limit: Int) : Command

    /** `/maxlines [n]`: how many lines chat keeps; null asks. */
    data class MaxLines(val lines: Int?) : Command

    /** `/open nick`: the whisper conversation with them. */
    data class Open(val nick: String) : Command

    /** Nothing to send: tell the user [text] (usage, a bad nick). */
    data class Problem(val text: String) : Command
}

/** One command as `/help` lists it and autocomplete offers it. */
class CommandInfo(val name: String, val aliases: List<String>, val description: String, val mod: Boolean = false)

/** The commands Glorp handles, in chat-gui's words. */
val commandList = listOf(
    CommandInfo("ban", emptyList(), "Stop <nick> from connecting to the chat.", mod = true),
    CommandInfo("broadcast", emptyList(), "Send a system-wide message to all users present in the chat.", mod = true),
    CommandInfo("die", listOf("suicide", "bitly"), "Mute yourself for 10 minutes."),
    CommandInfo("embed", listOf("e"), "Embed a video to bigscreen."),
    CommandInfo("emotes", emptyList(), "Return all emotes in text form."),
    CommandInfo("help", emptyList(), "List all chat commands."),
    CommandInfo("highlight", emptyList(), "Highlight messages from <nick> for easier visibility."),
    CommandInfo("ignore", emptyList(), "Stop showing messages from <nick>."),
    CommandInfo("ipban", emptyList(), "Ban <nick> and their IP address.", mod = true),
    CommandInfo("maxlines", emptyList(), "The maximum number of lines the chat will store."),
    CommandInfo("me", emptyList(), "Send an action message in italics."),
    CommandInfo("mentions", listOf("m"), "Return a list of messages where <nick> is mentioned."),
    CommandInfo("message", listOf("msg", "whisper", "w", "tell", "t", "notify"), "Send a whisper to <nick>."),
    CommandInfo("mute", emptyList(), "Stop <nick> from sending messages.", mod = true),
    CommandInfo("open", listOf("o"), "Open a whisper conversation with <nick>."),
    CommandInfo("pin", listOf("motd"), "Pin a message to chat.", mod = true),
    CommandInfo("poll", listOf("vote", "v"), "Start a poll: <question>? <option 1> or <option 2> [<time>]."),
    CommandInfo("pollstop", listOf("votestop", "vs"), "Stop the poll that is running."),
    CommandInfo("postembed", listOf("pe"), "Post a video embed in chat."),
    CommandInfo("reloadusers", emptyList(), "Reload all users in chat.", mod = true),
    CommandInfo("addphrase", listOf("addban", "addmute"), "Add a banned phrase to chat.", mod = true),
    CommandInfo(
        "removephrase", listOf("removeban", "removemute", "deletephrase", "deleteban", "deletemute", "dmute", "dban", "dphrase"),
        "Remove a banned phrase from chat.", mod = true,
    ),
    CommandInfo("reply", listOf("r"), "Reply to the last whisper you received."),
    CommandInfo("showpoll", listOf("showvote"), "Show the last poll again after closing it."),
    CommandInfo("stalk", listOf("s"), "Return a list of messages from <nick>."),
    CommandInfo("subonly", emptyList(), "Set subscriber-only chat mode <on>, <off>, or to a specific <tier> (1-5).", mod = true),
    CommandInfo("tag", emptyList(), "Mark <nick>'s messages."),
    CommandInfo("timestampformat", emptyList(), "Set the time format of the chat."),
    CommandInfo("unban", emptyList(), "Unban <nick>.", mod = true),
    CommandInfo("unhighlight", emptyList(), "Unhighlight <nick>."),
    CommandInfo("unignore", emptyList(), "Remove <nick> from your ignore list."),
    CommandInfo("unignoreall", emptyList(), "Remove all users from your ignore list."),
    CommandInfo("unmute", emptyList(), "Unmute <nick>.", mod = true),
    CommandInfo("unpin", listOf("unmotd"), "Unpin a message from chat.", mod = true),
    CommandInfo("untag", emptyList(), "Untag <nick>."),
    CommandInfo("untagall", emptyList(), "Untag all your tagged users."),
)

/** chat-gui's commands Glorp doesn't do, and why, so they don't go to chat as text. */
private val notHere = mapOf(
    "baninfo" to "needs the site's own login; your profile on the site shows bans",
    "host" to "needs the site's own login",
    "unhost" to "needs the site's own login",
    "exit" to "conversations close with the back button",
    "hint" to "there are no hints in Glorp",
    "connect" to "Glorp connects by itself",
)

/** The words autocomplete offers at the start of the input: `/name` and its aliases. */
fun commandWords(mod: Boolean): List<String> =
    commandList.filter { mod || !it.mod }.flatMap { listOf(it.name) + it.aliases }.map { "/$it" }.sorted()

fun helpText(mod: Boolean): String = buildString {
    append("Commands:")
    for (c in commandList.filter { mod || !it.mod }) {
        append("\n/").append(c.name)
        if (c.aliases.isNotEmpty()) append(" (").append(c.aliases.joinToString(", ") { "/$it" }).append(')')
        append(" - ").append(c.description)
    }
}

private val commandName = Regex("""^/([a-z0-9]+)\s?""", RegexOption.IGNORE_CASE)
private val nickShape = Regex("""^\w{3,20}$""")
private val interval = Regex("""(\d+)(s(?:econds?)?$|m(?:inutes?)?$|h(?:ours?)?$|d(?:ays?)?$)?""", RegexOption.IGNORE_CASE)

fun isNick(text: String) = nickShape.matches(text)

/** chat-gui's `parseTimeInterval`: `10m`, `2h`, `30` (seconds), in nanoseconds; 0 when unreadable. */
fun durationNanos(text: String): Long {
    val match = interval.find(text) ?: return 0
    val n = match.groupValues[1].toLongOrNull() ?: return 0
    val seconds = when (match.groupValues[2].lowercase().firstOrNull()) {
        'm' -> n * 60
        'h' -> n * 3600
        'd' -> n * 86400
        else -> n
    }
    return seconds * 1_000_000_000
}

private fun json(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(
    pairs.associate { (k, v) ->
        k to when (v) {
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> v as JsonElement
        }
    },
)

/**
 * Reads [text] (already trimmed) the way chat-gui's `cmdSEND` does: a `/word`
 * at the start is a command unless it is `/me`; anything else is a message.
 * [mod] says whether the user has moderator powers, which a few commands
 * check before sending.
 */
fun parseCommand(text: String, mod: Boolean): Command {
    val match = commandName.find(text) ?: return Command.Say(text)
    val name = match.groupValues[1].lowercase()
    if (name == "me") return Command.Say(text)
    val rest = text.substring(1 + name.length).trim()
    val args = rest.split(' ').filter { it.isNotEmpty() }
    return when (name) {
        "message", "msg", "whisper", "w", "tell", "t", "notify" -> {
            val nick = args.firstOrNull()
            val body = rest.substringAfter(' ', "").trim()
            when {
                nick == null || !isNick(nick) -> Command.Problem("Invalid nick - /msg nick message")
                body.isEmpty() -> Command.Problem("Write the message after the name - /msg $nick message")
                else -> Command.Whisper(nick, body)
            }
        }
        "reply", "r" -> Command.Reply
        "ignore" -> args.firstOrNull { !isNick(it) }
            ?.let { Command.Problem("$it is not a valid nick - /ignore <nick> OR /ignore <nick_1> <nick_2> ... <nick_n>.") }
            ?: Command.Ignore(args)
        "unignore" -> when {
            args.isEmpty() -> Command.Problem("Invalid nick - /unignore <nick> OR /unignore <nick_1> <nick_2> ... <nick_n>")
            else -> args.firstOrNull { !isNick(it) }
                ?.let { Command.Problem("$it is not a valid nick - /unignore <nick> OR /unignore <nick_1> <nick_2> ... <nick_n>.") }
                ?: Command.Unignore(args)
        }
        "unignoreall" -> when (args.firstOrNull()?.lowercase()) {
            "yes", "y" -> Command.UnignoreAll
            else -> Command.Problem("This command requires confirmation - /unignoreall yes OR /unignoreall y")
        }
        "highlight", "unhighlight" -> {
            val nick = args.firstOrNull()
            when {
                nick == null -> Command.Highlight(null, name == "highlight")
                !isNick(nick) -> Command.Problem("Invalid nick - /$name nick")
                else -> Command.Highlight(nick, name == "highlight")
            }
        }
        "tag" -> when {
            args.isEmpty() -> Command.Tag(null, emptyList())
            !isNick(args[0]) -> Command.Problem("Invalid nick - /tag <nick> [<color>, <note>]")
            else -> Command.Tag(args[0], args.drop(1))
        }
        "untag" -> when {
            args.isEmpty() -> Command.Untag(null)
            !isNick(args[0]) -> Command.Problem("Invalid nick - /untag <nick> [<color>, <note>]")
            else -> Command.Untag(args[0])
        }
        "untagall" -> when (args.firstOrNull()?.lowercase()) {
            "yes", "y" -> Command.UntagAll
            else -> Command.Problem("This command requires confirmation - /untagall yes OR /untagall y")
        }
        "help" -> Command.Help
        "timestampformat" -> when {
            rest.isEmpty() -> Command.TimestampFormat(null)
            // chat-gui's check, as it is.
            !Regex("""^[a-z :.,-\\*]+$""", RegexOption.IGNORE_CASE).matches(rest) ->
                Command.Problem("Invalid format, see: http://momentjs.com/docs/#/displaying/format/")
            else -> Command.TimestampFormat(rest)
        }
        "emotes" -> Command.EmoteList
        "embed", "e" -> args.firstOrNull()?.let(Command::Watch)
            ?: Command.Problem("Usage: /embed <link> OR /e <link>")
        "postembed", "pe" -> Command.PostEmbed(args)
        "poll", "vote", "v" -> Command.StartPoll(parsePoll(args), name)
        "pollstop", "votestop", "vs" -> Command.StopPoll
        "showpoll", "showvote" -> Command.ShowPoll
        "stalk", "s", "mentions", "m" -> {
            val stalk = name.startsWith("s")
            // chat-gui: a number first is the limit, for yourself.
            val first = args.firstOrNull()
            val selfLimit = first?.all { it.isDigit() } == true
            val nick = if (selfLimit) null else first
            val limit = (if (selfLimit) first else args.getOrNull(1))?.toIntOrNull()
                ?.coerceIn(1, LookBack.MAX_LIMIT) ?: LookBack.DEFAULT_LIMIT
            when {
                nick != null && !isNick(nick) -> Command.Problem("Invalid nick - /$name <nick> <limit>")
                stalk && nick == null && !selfLimit -> Command.Problem("Invalid nick - /stalk <nick> <limit>")
                stalk -> Command.Stalk(nick, limit)
                else -> Command.Mentions(nick, limit)
            }
        }
        "maxlines" -> when {
            args.isEmpty() -> Command.MaxLines(null)
            else -> args[0].toIntOrNull()?.let { kotlin.math.abs(it) }?.takeIf { it > 0 }?.let { Command.MaxLines(it) }
                ?: Command.Problem("Invalid argument - /maxlines is expecting a number.")
        }
        "open", "o" -> when {
            args.isEmpty() -> Command.Problem("Usage: /$name <nick>")
            !isNick(args[0]) -> Command.Problem("Invalid nick - /$name <nick>")
            else -> Command.Open(args[0])
        }
        "die", "suicide", "bitly" -> Command.Send("DIE", json("data" to ""))
        "mute" -> when {
            args.isEmpty() -> Command.Problem("Usage: /mute <nick> [<time>].")
            !isNick(args[0]) -> Command.Problem("Invalid nick - /mute <nick> [<time>].")
            else -> {
                val duration = args.getOrNull(1)?.let(::durationNanos) ?: 0
                if (duration > 0) Command.Send("MUTE", json("data" to args[0], "duration" to duration))
                else Command.Send("MUTE", json("data" to args[0]))
            }
        }
        "unmute", "unban" -> when {
            args.isEmpty() -> Command.Problem("Usage: /$name <nick>.")
            !isNick(args[0]) -> Command.Problem("Invalid nick.")
            else -> Command.Send(name.uppercase(), json("data" to args[0]))
        }
        "ban", "ipban" -> when {
            args.size < 3 -> Command.Problem("Usage: /$name <nick> <time> <reason> (time can be 'permanent').")
            !isNick(args[0]) -> Command.Problem("Invalid nick.")
            else -> {
                val reason = args.drop(2).joinToString(" ")
                val length = if (args[1].startsWith("perm", ignoreCase = true)) "ispermanent" to true
                else "duration" to durationNanos(args[1])
                Command.Send("BAN", json("nick" to args[0], "reason" to reason, length, "banip" to (name == "ipban")))
            }
        }
        "subonly" -> {
            val arg = args.firstOrNull()?.lowercase()
            when {
                arg == "off" -> Command.Send("SUBONLY", json("data" to "off"))
                arg == "on" -> Command.Send("SUBONLY", json("data" to "on", "tier" to 1))
                arg != null && arg.length == 1 && arg[0] in '1'..'5' -> Command.Send("SUBONLY", json("data" to "on", "tier" to arg.toInt()))
                else -> Command.Problem("Invalid argument - /subonly on | off | <tier 1-5>")
            }
        }
        "broadcast" -> Command.Send("BROADCAST", json("data" to args.joinToString(" ")))
        "pin", "motd" -> if (args.isEmpty()) Command.Problem("No message provided - /pin <message>")
        else Command.Send("PIN", json("data" to args.joinToString(" ")))
        "unpin", "unmotd" -> Command.Send("PIN", json("data" to ""))
        "reloadusers" -> Command.Send("RELOADUSERS", JsonObject(emptyMap()))
        "addphrase", "addban", "addmute", "removephrase", "removeban", "removemute",
        "deletephrase", "deleteban", "deletemute", "dmute", "dban", "dphrase" -> {
            val event = if (name.startsWith("add")) "ADDPHRASE" else "REMOVEPHRASE"
            when {
                !mod -> Command.Problem("You don't have the permissions to do that.")
                args.isEmpty() -> Command.Problem("No phrase provided - /${event.lowercase()} <phrase>")
                else -> Command.Send(event, json("data" to args.joinToString(" ")))
            }
        }
        else -> notHere[name]?.let { Command.Problem("/$name isn't in Glorp: $it.") }
            ?: Command.Problem("Unknown command. Try /help")
    }
}
