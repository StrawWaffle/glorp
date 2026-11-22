package io.github.strawwaffle.glorp.chat

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val retryWindow = 3.seconds
private val retryWindowCap = 60.seconds
private val retryMin = 500.milliseconds

/**
 * chat-gui's reconnect backoff: full jitter over a window that doubles with
 * each failed attempt, capped at a minute. The jitter matters as much as the
 * backoff, because a server restart drops every client at the same moment.
 */
fun retryDelay(attempts: Int, random: () -> Double): Duration {
    val window = if (attempts < 6) minOf(retryWindowCap, retryWindow * (1 shl attempts)) else retryWindowCap
    return retryMin + (window - retryMin) * random()
}
