package io.github.strawwaffle.glorp.update

import io.github.strawwaffle.glorp.chat.ChatPrefs
import io.github.strawwaffle.glorp.chat.ChatSocket
import io.github.strawwaffle.glorp.chat.chatJson
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/** A release on GitHub with an APK to install. */
data class Release(
    val version: String,
    val apkUrl: String,
    /** The APK's SHA-256 as hex, from GitHub's asset digest or the `.sha256` file beside it. */
    val sha256: String?,
    val page: String,
)

/** Where an update is at, for the menu to show. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val percent: Int) : UpdateState

    /** Android's installer has it; it asks the user to confirm. */
    data class Installing(val release: Release) : UpdateState

    /** Android needs the user to let Glorp install apps first, in the settings page just opened. */
    data class NeedsPermission(val release: Release) : UpdateState
    data class Failed(val release: Release?, val why: String) : UpdateState
}

/** What only the platform can do: download, check and hand an APK to the system installer. */
interface Installer {
    /** Downloads [release], checks its SHA-256 and starts the install, reporting progress in percent. */
    suspend fun install(release: Release, progress: (Int) -> Unit): UpdateState
}

/**
 * Glorp's own updates, from its GitHub releases: a check at most once a day
 * (or when asked), and an install through [installer]. Phones refuse an
 * APK that isn't signed with the same key, so a release can only come from
 * whoever holds it.
 */
class Updates(
    private val client: HttpClient,
    private val prefs: ChatPrefs,
    val currentVersion: String,
    private val installer: Installer,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * Whether test versions (pre-releases, `0.1.23-test.1`) are offered too,
     * for people who want to try what's next and report what breaks.
     */
    var testVersions: Boolean
        get() = prefs.get(TEST_VERSIONS) == "true"
        set(value) {
            prefs.put(TEST_VERSIONS, value.toString())
            // What the last check found may not fit any more.
            prefs.put(CHECKED, null)
            prefs.put(FOUND, null)
            _state.value = UpdateState.Idle
            check(force = true)
        }

    /** Checks unless one was done in the last day; [force] checks anyway. */
    fun check(force: Boolean = false) {
        val now = Clock.System.now().toEpochMilliseconds()
        val last = prefs.get(CHECKED)?.toLongOrNull() ?: 0
        if (!force && now - last < DAY_MS) {
            // Still offer what the last check found, without asking again.
            prefs.get(FOUND)?.let { found ->
                runCatching { chatJson.decodeFromString<Saved>(found) }.getOrNull()
                    ?.takeIf { isNewer(it.version, currentVersion) }
                    ?.let { _state.value = UpdateState.Available(Release(it.version, it.apkUrl, it.sha256, it.page)) }
            }
            return
        }
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            val release = latest()
            if (release == null) {
                _state.value = if (force) UpdateState.Failed(null, "Couldn't reach GitHub. Try again later.") else UpdateState.Idle
                return@launch
            }
            prefs.put(CHECKED, now.toString())
            if (isNewer(release.version, currentVersion)) {
                prefs.put(FOUND, chatJson.encodeToString(Saved.serializer(), Saved(release.version, release.apkUrl, release.sha256, release.page)))
                _state.value = UpdateState.Available(release)
            } else {
                prefs.put(FOUND, null)
                _state.value = if (force) UpdateState.UpToDate else UpdateState.Idle
            }
        }
    }

    fun install(release: Release) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(release, 0)
        scope.launch {
            _state.value = installer.install(release) { _state.value = UpdateState.Downloading(release, it) }
        }
    }

    private suspend fun latest(): Release? = try {
        // GitHub's "latest" is never a pre-release; test versions need the list.
        val tests = testVersions
        val response = client.get("https://api.github.com/repos/$REPO/releases" + if (tests) "?per_page=20" else "/latest") {
            header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT)
            header(HttpHeaders.Accept, "application/vnd.github+json")
        }
        val body = if (response.status.isSuccess()) response.bodyAsText() else null
        val newest = when {
            body == null -> null
            tests -> newestRelease(body)
            else -> readRelease(body)
        }
        newest?.let { found ->
            if (found.sha256 != null) found
            else found.copy(sha256 = checksumFile(found))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun checksumFile(release: Release): String? = runCatching {
        val response = client.get(release.apkUrl + ".sha256") { header(HttpHeaders.UserAgent, ChatSocket.USER_AGENT) }
        if (!response.status.isSuccess()) null else response.bodyAsText().trim().substringBefore(' ').lowercase()
            .takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }.getOrNull()

    @Serializable
    private class Saved(val version: String, val apkUrl: String, val sha256: String?, val page: String)

    companion object {
        const val REPO = "StrawWaffle/glorp"
        private const val CHECKED = "update-checked"
        private const val FOUND = "update-found"
        private const val TEST_VERSIONS = "update-test-versions"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

@Serializable
private class GitHubRelease(
    val tag_name: String = "",
    val html_url: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private class GitHubAsset(val name: String = "", val browser_download_url: String = "", val digest: String? = null)

/** The release's version and its Glorp APK; null when it has none, or it's a pre-release. */
internal fun readRelease(json: String): Release? = asRelease(chatJson.decodeFromString<GitHubRelease>(json), prereleases = false)

/** The newest release in a list of them, test versions included. */
internal fun newestRelease(json: String): Release? =
    chatJson.decodeFromString<List<GitHubRelease>>(json)
        .mapNotNull { asRelease(it, prereleases = true) }
        .reduceOrNull { a, b -> if (isNewer(b.version, a.version)) b else a }

private fun asRelease(release: GitHubRelease, prereleases: Boolean): Release? {
    if (release.draft || (release.prerelease && !prereleases)) return null
    val apk = release.assets.firstOrNull { it.name.startsWith("glorp") && it.name.endsWith(".apk") } ?: return null
    val digest = apk.digest?.removePrefix("sha256:")?.lowercase()?.takeIf { it.length == 64 }
    return Release(release.tag_name.removePrefix("v"), apk.browser_download_url, digest, release.html_url)
}

/**
 * Whether [candidate] (`1.2.0`) is later than [current]. A test version
 * (`1.2.0-test.3`) comes before its release and after the test versions
 * numbered lower; another suffix (`-dev`) is ignored.
 */
fun isNewer(candidate: String, current: String): Boolean {
    fun parts(v: String): List<Int> {
        val numbers = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val test = v.substringAfter("-test.", "").toIntOrNull()
        return List(3) { numbers.getOrElse(it) { 0 } } + numbers.drop(3) + (test ?: Int.MAX_VALUE)
    }
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
