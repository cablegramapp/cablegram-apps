package app.cablegram.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Shared by apps/android-phone and apps/android-tv (spec 004). Keep both copies identical.

/**
 * The key TDLib encrypts its database (and so the Telegram auth key) with (FR-004): 32 random bytes,
 * stored wrapped by an Android Keystore AES-GCM key that cannot leave the device.
 *
 * Extra depth only (spec 004 US8): an app that can run as Cablegram can use the session anyway.
 * If the Keystore key is lost, the wrapped key cannot be read; [databaseKey] then wipes the Telegram
 * directories and starts fresh, and the user links Telegram again.
 */
class TelegramDatabaseKey(private val context: Context) {
    private val prefs = context.getSharedPreferences("cablegram_telegram", Context.MODE_PRIVATE)

    val databaseDirectory: File get() = File(context.filesDir, "telegram/db")
    val filesDirectory: File get() = File(context.cacheDir, "telegram/files")

    fun databaseKey(): ByteArray {
        prefs.getString(PREF_KEY, null)?.let { wrapped ->
            unwrap(wrapped)?.let { return it }
            // Undecryptable (Keystore reset): the old database is unreadable, so drop it.
            wipe()
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString(PREF_KEY, wrap(key)).apply()
        return key
    }

    /** Deletes the Telegram database, downloaded pieces and the wrapped key (sign-out, unpair, expiry). */
    fun wipe() {
        File(context.filesDir, "telegram").deleteRecursively()
        File(context.cacheDir, "telegram").deleteRecursively()
        prefs.edit().remove(PREF_KEY).apply()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray? = runCatching {
        val (iv, data) = encoded.split(":", limit = 2).also { require(it.size == 2) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        cipher.doFinal(Base64.decode(data, Base64.NO_WRAP))
    }.getOrNull()

    private fun keystoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "cablegram.telegram.database"
        const val PREF_KEY = "database_key_wrapped"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
