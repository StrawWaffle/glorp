package io.github.strawwaffle.glorp.video

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal actual val jsThread: CoroutineDispatcher = newSingleThreadContext("glorp-js")
