package io.github.strawwaffle.glorp.community

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val wallClock = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

internal actual fun zonedEpochSeconds(dateTime: String, zone: String): Long? = try {
    LocalDateTime.parse(dateTime.trim(), wallClock).atZone(ZoneId.of(zone)).toEpochSecond()
} catch (_: Exception) {
    null
}

private val shortLocal = DateTimeFormatter.ofPattern("EEE d MMM, ")

internal actual fun localDateTime(epochSeconds: Long): String {
    val moment = Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault())
    return moment.format(shortLocal) + moment.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}

internal actual fun localOffsetMillis(epochMillis: Long): Long = java.util.TimeZone.getDefault().getOffset(epochMillis).toLong()
