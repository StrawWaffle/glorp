package io.github.strawwaffle.glorp.grayjay

// Security.framework's SecKeyVerifySignature comes with the iOS app. Until
// then nothing is trusted, so no unchecked plugin runs.
internal actual fun verifySha512Rsa(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = false
