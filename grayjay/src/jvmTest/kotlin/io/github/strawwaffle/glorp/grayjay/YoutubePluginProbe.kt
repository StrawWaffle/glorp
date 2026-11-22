package io.github.strawwaffle.glorp.grayjay

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTimedValue

/**
 * Runs FUTO's published YouTube plugin against real YouTube. Not part of the
 * normal test run: it needs the network and takes a while. Run with
 * `-Dglorp.probe=https://www.youtube.com/watch?v=…`.
 */
class YoutubePluginProbe {
    @Test
    fun probe() {
        val url = System.getProperty("glorp.probe") ?: return
        val cache = File(System.getProperty("java.io.tmpdir"), "glorp-plugin-cache").apply { mkdirs() }
        val log = { line: String -> println("plugin: " + line.take(300)) }
        runBlocking {
            val http = HttpClient(OkHttp) {
                install(HttpCookies)
                install(HttpTimeout) { requestTimeoutMillis = 30_000 }
            }
            val config = cached(cache, "YoutubeConfig.json") { http.get("https://plugins.grayjay.app/Youtube/YoutubeConfig.json").bodyAsText() }
            val script = cached(cache, "YoutubeScript.js") { http.get("https://plugins.grayjay.app/Youtube/YoutubeScript.js").bodyAsText() }
            println("verified: " + verifyScript(config, script))
            val (runtime, loadTime) = measureTimedValue {
                PluginRuntime.load(config, script, http, Dispatchers.IO, settings = mapOf("use_native_ump" to "false"), log = log, trace = { if ("youtubei" in it) println("http: $it") })
            }
            println("loaded and enabled in $loadTime")
            runtime.use {
                val (details, time) = measureTimedValue { withTimeout(3.minutes) { it.contentDetails(url) } }
                println("details in $time: ${details.take(1500)}")
            }
        }
    }

    private suspend fun cached(dir: File, name: String, fetch: suspend () -> String): String {
        val file = File(dir, name)
        if (!file.exists()) file.writeText(fetch())
        return file.readText()
    }
}
