package io.github.strawwaffle.glorp.video

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/** A thread of its own with a 16 MB stack, like the main thread's room, for the plugin's deep calls. */
internal actual val jsThread: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread(null, task, "glorp-js", 16L * 1024 * 1024).apply { isDaemon = true }
}.asCoroutineDispatcher()
