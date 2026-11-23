package io.github.strawwaffle.glorp.diag

import io.ktor.http.encodeURLParameter

/** What a problem report needs from the platform. */
interface Diagnostics {
    /** Glorp's version, such as "0.1.23". */
    val version: String

    /** The phone and its system, such as "Google Pixel 9, Android 16 (API 36)". */
    val device: String

    /** What the last crash saved (see [crashText]), until it's reported or dismissed. */
    fun lastCrash(): String?

    fun clearCrash()
}

/** Where reports go: a GitHub issue form whose fields the link fills in. */
const val ISSUES = "https://github.com/StrawWaffle/glorp/issues/new"

/**
 * A problem report as the user sees it before sending, and the link that
 * opens it as a new issue. The issue form (`.github/ISSUE_TEMPLATE/problem.yml`)
 * takes its fields from the link's query, by field id.
 */
class ProblemReport(
    val what: String,
    val version: String,
    val device: String,
    val crash: String?,
    val log: List<String>,
) {
    val title: String
        get() = what.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
            ?: crash?.lineSequence()?.firstOrNull { it.startsWith("Exception: ") }?.removePrefix("Exception: ")?.take(80)?.let { "Crash: $it" }
            ?: "Problem report"

    /** The crash, then the log, cut from the oldest end to fit [budget] characters. */
    fun details(budget: Int = Int.MAX_VALUE): String {
        val crashPart = crash?.let { "Crash:\n${it.trimEnd()}\n\n" }.orEmpty()
        if (log.isEmpty() || crashPart.length >= budget) return crashPart.trimEnd().take(budget)
        val header = "Recent log (UTC):\n"
        // Room for the note on what was left out, too.
        var room = budget - crashPart.length - header.length - 32
        val kept = ArrayDeque<String>()
        for (line in log.asReversed()) {
            if (line.length + 1 > room) break
            kept.addFirst(line)
            room -= line.length + 1
        }
        val cut = log.size - kept.size
        val note = if (cut > 0) "($cut earlier lines left out)\n" else ""
        return crashPart + header + note + kept.joinToString("\n")
    }

    /** All of it as plain text, for copying. */
    fun text(): String = buildString {
        append("What happened: ").append(what.ifBlank { "(not given)" }).append('\n')
        append("Glorp ").append(version).append(" on ").append(device).append("\n\n")
        append(details())
    }

    /**
     * The new-issue link with the fields filled in. Links past about 8 KB are
     * refused, so the log is cut to fit.
     */
    fun issueLink(limit: Int = 7_500): String {
        fun param(name: String, value: String) = "&$name=" + value.encodeURLParameter()
        val fixed = "$ISSUES?template=problem.yml" + param("title", title) + param("what", what) +
            param("version", version) + param("device", device) + "&details="
        // Encoding can triple a character; fit the details by trying smaller budgets.
        var budget = limit - fixed.length
        while (budget > 0) {
            val encoded = details(budget).encodeURLParameter()
            if (fixed.length + encoded.length <= limit) return fixed + encoded
            budget -= maxOf(200, (fixed.length + encoded.length - limit) / 2)
        }
        return fixed
    }
}

/** What a crash saves: when, where, the exception and its stack, and the log just before. */
fun crashText(error: Throwable, thread: String, version: String, atIso: String, log: List<String>): String = buildString {
    append("Exception: ").append(error::class.qualifiedName ?: error::class.simpleName).append(": ")
        .append(redact(error.message.orEmpty()).take(300)).append('\n')
    append("Thread: ").append(thread).append(", Glorp ").append(version).append(", at ").append(atIso).append('\n')
    error.stackTraceToString().lineSequence().drop(1).take(40).forEach { append(redact(it)).append('\n') }
    if (log.isNotEmpty()) {
        append("\nLog before it (UTC):\n")
        log.takeLast(60).forEach { append(it).append('\n') }
    }
}
