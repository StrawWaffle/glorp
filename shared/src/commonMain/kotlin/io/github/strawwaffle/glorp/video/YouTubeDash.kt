package io.github.strawwaffle.glorp.video

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/** One of a YouTube video's files, as the Grayjay plugin describes it. */
@Serializable
internal data class YtSource(
    val type: String? = null,
    val name: String? = null,
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val container: String? = null,
    val codec: String? = null,
    val bitrate: Long? = null,
    val duration: Long? = null,
    val itag: Int? = null,
    val initStart: Long? = null,
    val initEnd: Long? = null,
    val indexStart: Long? = null,
    val indexEnd: Long? = null,
    val original: Boolean = false,
    /** What the plugin's request modifier adds to a request for this file. */
    val headers: Map<String, String> = emptyMap(),
) {
    /** Playable from byte ranges: an address, and where its init and index are. */
    val ranged: Boolean
        get() = url != null && container != null && codec != null &&
            initStart != null && initEnd != null && indexStart != null && indexEnd != null
}

@Serializable
internal data class YtDetails(
    val name: String? = null,
    val isLive: Boolean = false,
    val live: YtSource? = null,
    val sources: List<YtSource> = emptyList(),
    val audio: List<YtSource> = emptyList(),
)

private val detailsJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

internal fun parseYtDetails(text: String): YtDetails = detailsJson.decodeFromString(text)

/**
 * A recording as one DASH manifest, so the player can switch qualities and
 * seek: each file YouTube serves separately (video at each quality, audio)
 * becomes a representation whose init and index are byte ranges of it, the
 * way YouTube's own player reads them. Null when there's no video and audio
 * to pair.
 *
 * Only the original audio is kept where a video has dubs; the player picks
 * among the rest by what the phone can decode.
 */
internal fun ytDashManifest(details: YtDetails): String? {
    val video = details.sources.filter { it.ranged && it.container!!.startsWith("video/") }
    val allAudio = details.audio.filter { it.ranged && it.container!!.startsWith("audio/") }
    val audio = allAudio.filter { it.original }.ifEmpty { allAudio }
    if (video.isEmpty() || audio.isEmpty()) return null
    val seconds = (video + audio).firstNotNullOfOrNull { it.duration?.takeIf { d -> d > 0 } } ?: return null

    val out = StringBuilder()
    out.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
    out.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" """)
    out.append("""type="static" minBufferTime="PT1.5S" mediaPresentationDuration="PT${seconds}S">""").append('\n')
    out.append("""<Period start="PT0S" duration="PT${seconds}S">""").append('\n')
    var id = 0
    // One adaptation set per container: the player switches quality within one.
    for ((container, group) in (video + audio).groupBy { it.container!! }) {
        val isVideo = container.startsWith("video/")
        out.append("""<AdaptationSet mimeType="${xml(container)}" subsegmentAlignment="true"""")
        if (isVideo) out.append(""" contentType="video">""") else out.append(""" contentType="audio" lang="und">""")
        out.append('\n')
        for (s in group.distinctBy { it.itag ?: it.url }) {
            out.append("""<Representation id="${id++}" codecs="${xml(s.codec!!)}" bandwidth="${s.bitrate ?: 1}"""")
            if (isVideo && s.width != null && s.height != null) out.append(""" width="${s.width}" height="${s.height}"""")
            if (!isVideo) out.append(""" audioSamplingRate="48000"""")
            out.append(">\n")
            if (!isVideo) {
                out.append("""<AudioChannelConfiguration schemeIdUri="urn:mpeg:dash:23003:3:audio_channel_configuration:2011" value="2"/>""").append('\n')
            }
            out.append("<BaseURL>").append(xml(s.url!!)).append("</BaseURL>\n")
            out.append("""<SegmentBase indexRange="${s.indexStart}-${s.indexEnd}">""")
            out.append("""<Initialization range="${s.initStart}-${s.initEnd}"/></SegmentBase>""").append('\n')
            out.append("</Representation>\n")
        }
        out.append("</AdaptationSet>\n")
    }
    out.append("</Period>\n</MPD>\n")
    return out.toString()
}

/**
 * The headers to send with every request for a recording's files: the
 * plugin's, less the hop-by-hop `TE` that HTTP stacks set for themselves.
 */
internal fun ytHeaders(details: YtDetails): Map<String, String> =
    (details.sources + details.audio).firstOrNull { it.headers.isNotEmpty() }?.headers.orEmpty()
        .filterKeys { !it.equals("TE", ignoreCase = true) }

/** A manifest as an address the player reads without a request. */
internal fun dataUri(mime: String, text: String): String =
    "data:$mime;base64," + Base64.encode(text.encodeToByteArray())

private fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
