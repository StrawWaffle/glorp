package io.github.strawwaffle.glorp.diag

import android.content.Context
import android.os.Build
import java.io.File
import kotlin.time.Clock

/**
 * Saves a crash to the app's private files before Android closes the app, so
 * the next start can offer to report it. Only the last one is kept, and
 * nothing leaves the phone unless the user sends it.
 */
object Crashes {
    @Volatile
    private var installed = false

    fun install(context: Context, version: String) {
        if (installed) return
        installed = true
        val file = file(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.writeText(crashText(error, thread.name, version, Clock.System.now().toString(), AppLog.lines()))
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun diagnostics(context: Context, version: String): Diagnostics {
        val file = file(context)
        return object : Diagnostics {
            override val version = version
            override val device = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            override fun lastCrash(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()
            override fun clearCrash() {
                file.delete()
            }
        }
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, "last-crash.txt")
}
