package io.github.strawwaffle.glorp.community

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.timeZoneWithName

internal actual fun zonedEpochSeconds(dateTime: String, zone: String): Long? {
    val timeZone = NSTimeZone.timeZoneWithName(zone) ?: return null
    val formatter = NSDateFormatter().apply {
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
        this.timeZone = timeZone
        dateFormat = "yyyy-MM-dd HH:mm:ss"
    }
    return formatter.dateFromString(dateTime.trim())?.timeIntervalSince1970?.toLong()
}

internal actual fun localDateTime(epochSeconds: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble())
    val day = NSDateFormatter().apply {
        timeZone = NSTimeZone.localTimeZone
        setLocalizedDateFormatFromTemplate("EEEdMMM")
    }
    val time = NSDateFormatter().apply {
        timeZone = NSTimeZone.localTimeZone
        timeStyle = NSDateFormatterShortStyle
    }
    return day.stringFromDate(date) + ", " + time.stringFromDate(date)
}
