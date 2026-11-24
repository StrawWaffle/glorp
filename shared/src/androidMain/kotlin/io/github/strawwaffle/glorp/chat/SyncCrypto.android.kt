package io.github.strawwaffle.glorp.chat

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

actual object SyncCrypto {
    private val random = SecureRandom()

    actual val available: Boolean get() = true

    actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    actual fun random(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    actual fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    actual fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
        cipher.doFinal(blob, 12, blob.size - 12)
    }.getOrNull()
}
