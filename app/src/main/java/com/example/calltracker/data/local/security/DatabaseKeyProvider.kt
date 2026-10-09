package com.example.calltracker.data.local.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the SQLCipher key.
 *
 * A random 256-bit key is generated once and stored encrypted (AES-GCM) by a non-exportable Android Keystore key.
 * It's handed to SQLCipher in raw-key form (`x'…'`), which skips PBKDF2 and keeps DB open time low on cheap phones.
 */
@Singleton
class DatabaseKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    sealed interface Key {
        val passphrase: ByteArray

        /** The existing key was decrypted. */
        class Existing(override val passphrase: ByteArray) : Key

        /** A new key was created; any database written with an older key is unreadable and must be deleted. */
        class Created(override val passphrase: ByteArray) : Key
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    @Synchronized
    fun getOrCreate(): Key {
        val stored = prefs.getString(PREF_WRAPPED_KEY, null)
        if (stored != null) {
            try {
                return Key.Existing(toPassphrase(unwrap(stored)))
            } catch (e: Exception) {
                // Keystore key lost (e.g. OEM keystore reset). The DB can't be decrypted any more.
                Log.e(TAG, "Database key could not be unwrapped; generating a new one", e)
            }
        }
        val raw = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        // commit, not apply: the key must be on disk before the database is created with it.
        prefs.edit(commit = true) { putString(PREF_WRAPPED_KEY, wrap(raw)) }
        return Key.Created(toPassphrase(raw))
    }

    private fun wrap(raw: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ct = Base64.encodeToString(cipher.doFinal(raw), Base64.NO_WRAP)
        return "$iv:$ct"
    }

    private fun unwrap(stored: String): ByteArray {
        val (iv, ct) = stored.split(':').let { it[0] to it[1] }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKeystoreKey() ?: error("Keystore key missing"),
            GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP))
    }

    private fun existingKeystoreKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }

    private fun keystoreKey(): SecretKey = existingKeystoreKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private fun toPassphrase(raw: ByteArray): ByteArray {
        val hex = raw.joinToString("") { "%02x".format(it) }
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val TAG = "DatabaseKeyProvider"
        const val PREFS = "db_key"
        const val PREF_WRAPPED_KEY = "wrapped_key"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "calltracker_db_kek"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val KEY_BYTES = 32
    }
}
