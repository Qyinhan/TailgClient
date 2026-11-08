package dev.tailg.core.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.tailg.core.domain.SessionMutationGate
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only the current session token, encrypted using an Android Keystore AES-GCM key. */
class SessionVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("secure_session_v1", Context.MODE_PRIVATE)
    private val keyAlias = "dev.tailg.client.session.v1"
    private val mutationGate = SessionMutationGate()

    /** Capture on the main thread BEFORE launching a login request. */
    @Synchronized
    fun generation(): Long = mutationGate.ticket()

    /** An older response may never overwrite logout or a newer successful login. */
    @Synchronized
    fun saveAfterLoginIfCurrent(expectedGeneration: Long, token: String): Boolean =
        mutationGate.commitIfCurrent(expectedGeneration) { persist(token) }

    private fun persist(token: String) {
        require(token.isNotBlank() && token.length <= 8192)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString("encrypted_token", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) { "Unable to persist session" }
    }

    @Synchronized
    fun read(): String? {
        val encoded = preferences.getString("encrypted_token", null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, 12))
            cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
                .takeIf(String::isNotBlank)
        } catch (_: Exception) {
            // A restored, corrupted or invalidated Keystore blob must never become plaintext.
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove("encrypted_token").commit()) { "Unable to clear session" }
        mutationGate.invalidate()
    }

    /** Reject a late response after logout or after another request rotated the session. */
    @Synchronized
    fun rotateIfCurrent(previousToken: String, newToken: String): Boolean {
        if (read() != previousToken) return false
        return mutationGate.commitIfCurrent(mutationGate.ticket()) { persist(newToken) }
    }

    /** Reject a late 401 from an older session after a fresh login. */
    @Synchronized
    fun clearIfCurrent(previousToken: String): Boolean {
        if (read() != previousToken) return false
        clear()
        return true
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
