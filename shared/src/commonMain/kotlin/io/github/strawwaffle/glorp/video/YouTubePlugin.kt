package io.github.strawwaffle.glorp.video

import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.grayjay.PluginRuntime
import io.github.strawwaffle.glorp.grayjay.verifyScript
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.Path
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * FUTO's YouTube plugin for Grayjay, run by the `grayjay` module.
 *
 * The plugin isn't shipped with the app: it is downloaded from FUTO's plugin
 * site, as Grayjay downloads it, so fixes for YouTube's changes arrive the day
 * FUTO publishes them. The script must match the signature in its config;
 * anything else is refused. It's checked for an update at most once a day.
 *
 * The engine (a few tens of MB with the plugin in it) is loaded on the first
 * lookup and closed after a few idle minutes.
 */
class YouTubePlugin(
    private val download: HttpClient,
    /** A client for the plugin's own requests, with its own cookie jar. */
    private val pluginHttp: () -> HttpClient,
    private val scope: CoroutineScope,
    private val dir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "glorp-plugins" / "youtube",
) {
    private val mutex = Mutex()
    private var runtime: PluginRuntime? = null
    private var idle: Job? = null

    /** What the plugin finds for a video: a live stream's playlist or a recording's files. */
    internal suspend fun details(videoId: String): YtDetails? = mutex.withLock {
        idle?.cancel()
        val rt = runtime ?: load().also { runtime = it }
        try {
            parseYtDetails(rt.contentDetails("https://www.youtube.com/watch?v=$videoId"))
        } finally {
            idle = scope.launch {
                delay(3.minutes)
                mutex.withLock { runtime?.close(); runtime = null }
            }
        }
    }

    fun close() {
        idle?.cancel()
        runtime?.close()
        runtime = null
    }

    private suspend fun load(): PluginRuntime {
        val (config, script) = fetch()
        return PluginRuntime.load(
            config,
            script,
            pluginHttp(),
            Dispatchers.IO,
            // The native UMP path needs a browser for attestation, which Glorp doesn't run.
            settings = mapOf("use_native_ump" to "false"),
            log = { println("YouTube plugin: ${it.take(200)}") },
        )
    }

    /** The cached plugin, refreshed when a day old and FUTO has published a new version. */
    private suspend fun fetch(): Pair<String, String> {
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir)
        val configFile = dir / "YoutubeConfig.json"
        val scriptFile = dir / "YoutubeScript.js"
        val checkedFile = dir / "checked"
        val cachedConfig = if (fs.exists(configFile)) fs.read(configFile) { readUtf8() } else null
        val cachedScript = if (fs.exists(scriptFile)) fs.read(scriptFile) { readUtf8() } else null
        val usable = cachedConfig != null && cachedScript != null && verifyScript(cachedConfig, cachedScript)
        val checkedAt = if (fs.exists(checkedFile)) fs.read(checkedFile) { readUtf8() }.toLongOrNull() ?: 0 else 0
        val now = Clock.System.now().toEpochMilliseconds()
        if (usable && now - checkedAt < 24.hours.inWholeMilliseconds) return cachedConfig!! to cachedScript!!

        try {
            val config = get("$BASE/YoutubeConfig.json")
            val sameVersion = usable && version(config) == version(cachedConfig!!)
            val script = if (sameVersion) cachedScript!! else get("$BASE/YoutubeScript.js")
            check(verifyScript(config, script)) { "The YouTube plugin's signature doesn't match its script" }
            fs.write(configFile) { writeUtf8(config) }
            if (!sameVersion) fs.write(scriptFile) { writeUtf8(script) }
            fs.write(checkedFile) { writeUtf8(now.toString()) }
            return config to script
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline or the site is down: yesterday's plugin is better than none.
            if (usable) return cachedConfig!! to cachedScript!!
            throw e
        }
    }

    private suspend fun get(url: String): String {
        val response = download.get(url) { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
        check(response.status.isSuccess()) { "$url answered ${response.status.value}" }
        return response.bodyAsText()
    }

    private fun version(config: String): String? =
        (Json.parseToJsonElement(config).jsonObject["version"] as? JsonPrimitive)?.content

    private companion object {
        const val BASE = "https://plugins.grayjay.app/Youtube"
    }
}

