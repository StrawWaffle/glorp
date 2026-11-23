package io.github.strawwaffle.glorp.video

import kotlin.math.ceil

/**
 * A live HLS media playlist rewritten for less delay, as players built for
 * Amazon IVS (Kick) and Twitch read theirs:
 *
 * - `#EXT-X-PREFETCH` (and Twitch's `#EXT-X-TWITCH-PREFETCH`) name the next
 *   segments while they're still being made; their addresses answer as the
 *   video arrives. They become ordinary segments, as long as the last one.
 * - `#EXT-X-TARGETDURATION` is the longest a segment may be (IVS says 6 for
 *   2-second segments), and with nothing better a player stays three of
 *   those behind live. It's brought down to the longest segment present.
 *
 * With prefetched segments the newest finished one is no longer the limit:
 * video is there up to about now. How far behind to play is then set
 * against the clock ([LOW_LATENCY_TARGET_MS]), not as three segments (6 s on
 * 2-second segments, over 12 s on a channel sending 4-second ones).
 *
 * Anything else (a multivariant playlist, one without segments) is left as
 * it is.
 */
fun lowLatencyPlaylist(text: String): String {
    if (!text.startsWith("#EXTM3U") || "#EXTINF:" !in text) return text
    val lines = text.lines()
    val durations = lines.filter { it.startsWith("#EXTINF:") }
        .mapNotNull { it.removePrefix("#EXTINF:").substringBefore(',').trim().toDoubleOrNull() }
    if (durations.isEmpty()) return text
    val last = durations.last()
    val longest = ceil(durations.max()).toInt().coerceAtLeast(1)
    val out = StringBuilder(text.length + 256)
    for (line in lines) {
        val prefetch = PREFETCH_TAGS.firstOrNull { line.startsWith(it) }
        when {
            prefetch != null -> {
                val url = line.removePrefix(prefetch).trim()
                if (url.isNotEmpty()) out.append("#EXTINF:").append(last).append(",live\n").append(url).append('\n')
            }
            line.startsWith("#EXT-X-TARGETDURATION:") -> {
                val declared = line.removePrefix("#EXT-X-TARGETDURATION:").trim().toIntOrNull()
                out.append(if (declared != null && declared > longest) "#EXT-X-TARGETDURATION:$longest" else line).append('\n')
            }
            else -> out.append(line).append('\n')
        }
    }
    return out.toString().trimEnd('\n') + "\n"
}

/**
 * How far behind real time (the segments' own clock) a low-latency live
 * stream plays. On a Pixel, 2.3 s held without stalls but with only a
 * second of buffer; this leaves about two. A stall moves it 2 s back.
 */
const val LOW_LATENCY_TARGET_MS = 3_500L

private val PREFETCH_TAGS = listOf("#EXT-X-PREFETCH:", "#EXT-X-TWITCH-PREFETCH:")
