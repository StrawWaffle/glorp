package io.github.strawwaffle.glorp.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the sign-in session (the site's cookies or a login key, never a
 * password) encrypted with an AES key that lives in the Android Keystore and
 * never leaves it: the app can use the key but not read it, so the stored
 * ciphertext is useless to anything else. It sits in the app's private
 * preferences. If the key is gone (a restored backup, a wiped keystore) the
 * session is simply forgotten.
 */
class KeystoreSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    override fun load(): Session? {
        val blob = prefs.getString(KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            Session.decode(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString())
        } catch (e: Exception) {
            // Unreadable for good (the key was replaced, or the data is
            // damaged): forget it. Anything else may pass; keep the login.
            if (e is javax.crypto.AEADBadTagException || e is IllegalArgumentException ||
                e is android.security.keystore.KeyPermanentlyInvalidatedException
            ) prefs.edit().remove(KEY).apply()
            null
        }
    }

    override fun save(session: Session?) {
        if (session == null) {
            prefs.edit().remove(KEY).apply()
            return
        }
        // A keystore that won't seal it shouldn't crash signing in: the login
        // then lasts until the app closes.
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val sealed = cipher.iv + cipher.doFinal(Session.encode(session).encodeToByteArray())
            prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
        }.onFailure { io.github.strawwaffle.glorp.diag.AppLog.note("signin", "couldn't save the login", it) }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "glorp-session"
        const val KEY = "login-v2"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
