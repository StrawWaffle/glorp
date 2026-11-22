package io.github.strawwaffle.glorp.community

/**
 * Seconds since the epoch for a wall-clock time (`yyyy-MM-dd HH:mm:ss`) in a
 * named time zone, or null if either can't be read. dinkdonk.mov writes its
 * schedule in New York time.
 */
internal expect fun zonedEpochSeconds(dateTime: String, zone: String): Long?

/** A moment as a short date and time in the phone's own time zone and locale. */
internal expect fun localDateTime(epochSeconds: Long): String

/** How far the phone's time zone is ahead of UTC at [epochMillis], in milliseconds. */
internal expect fun localOffsetMillis(epochMillis: Long): Long

/** [epochMillis] in the phone's time zone, as chat's `timestampformat` writes it. */
internal fun localTime(epochMillis: Long, format: String): String {
    val local = epochMillis + localOffsetMillis(epochMillis)
    val secondOfDay = ((local / 1000 % 86_400L + 86_400L) % 86_400L).toInt()
    return io.github.strawwaffle.glorp.chat.formatTime(secondOfDay / 3600, secondOfDay / 60 % 60, secondOfDay % 60, format)
}
