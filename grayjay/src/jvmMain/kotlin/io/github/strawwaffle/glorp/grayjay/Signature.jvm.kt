package io.github.strawwaffle.glorp.grayjay

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal actual fun verifySha512Rsa(data: ByteArray, signatureBase64: String, publicKeyBase64: String): Boolean = try {
    val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getMimeDecoder().decode(publicKeyBase64)))
    Signature.getInstance("SHA512withRSA").run {
        initVerify(key)
        update(data)
        verify(Base64.getMimeDecoder().decode(signatureBase64))
    }
} catch (_: Exception) {
    false
}
