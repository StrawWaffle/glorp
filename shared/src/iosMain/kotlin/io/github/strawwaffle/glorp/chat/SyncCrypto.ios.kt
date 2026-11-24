package io.github.strawwaffle.glorp.chat

/** Not on iOS yet: sync stays off there. */
actual object SyncCrypto {
    actual val available: Boolean get() = false
    actual fun sha256(data: ByteArray): ByteArray = error("no sync on iOS yet")
    actual fun random(size: Int): ByteArray = error("no sync on iOS yet")
    actual fun encrypt(key: ByteArray, plain: ByteArray): ByteArray = error("no sync on iOS yet")
    actual fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? = null
}
