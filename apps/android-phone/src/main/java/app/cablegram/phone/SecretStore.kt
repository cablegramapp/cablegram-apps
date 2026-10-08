package app.cablegram.phone

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
 * T079 / R-9: secrets at rest on the phone.
 *
 * Wraps the account token (and device id) with an Android Keystore AES-GCM
 * key so a plain SharedPreferences read no longer yields a usable bearer
 * token. Legacy plaintext migration is performed by [PairingStore] before
 * first use, so this class only ever touches encrypted values.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("cablegram_phone_secrets", Context.MODE_PRIVATE)

    /** Account JWT, encrypted at rest. */
    var accountToken: String?
        get() = decrypt(prefs.getString(KEY_ACCOUNT, null))
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_ACCOUNT) else putString(KEY_ACCOUNT, encrypt(value))
            }.apply()
        }

    /** Long-lived session refresh token, encrypted at rest. */
    var refreshToken: String?
        get() = decrypt(prefs.getString(KEY_REFRESH, null))
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_REFRESH) else putString(KEY_REFRESH, encrypt(value))
            }.apply()
        }

    /** Phone device id, encrypted at rest. */
    var phoneDeviceId: String?
        get() = decrypt(prefs.getString(KEY_DEVICE, null))
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_DEVICE) else putString(KEY_DEVICE, encrypt(value))
            }.apply()
        }

    /** Presence is checked separately from decryption so a damaged record never falls back to plaintext. */
    internal val hasPairedTvs: Boolean get() = prefs.contains(KEY_PAIRED_TVS)

    internal fun readPairedTvs(): String? = decrypt(prefs.getString(KEY_PAIRED_TVS, null), KEY_PAIRED_TVS)

    /** Persist ciphertext before the caller deletes any legacy plaintext. */
    internal fun savePairedTvs(json: String) {
        val encrypted = encrypt(json, KEY_PAIRED_TVS)
        check(prefs.edit().putString(KEY_PAIRED_TVS, encrypted).commit()) { "Couldn't save paired TVs securely." }
    }

    /** A previous failed commit can leave ciphertext in memory: confirm durability before cleanup on retry. */
    internal fun confirmPairedTvsSaved() {
        val encrypted = prefs.getString(KEY_PAIRED_TVS, null) ?: error("Missing encrypted pairing record.")
        check(prefs.edit().putString(KEY_PAIRED_TVS, encrypted).commit()) { "Couldn't save paired TVs securely." }
    }

    private fun encrypt(plaintext: String, associatedData: String? = null): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        associatedData?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(encoded: String?, associatedData: String? = null): String? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val parts = encoded.split(":", limit = 2)
            if (parts.size != 2) return@runCatching null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            associatedData?.let { cipher.updateAAD(it.toByteArray(Charsets.UTF_8)) }
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "cablegram.phone.account"
        const val KEY_ACCOUNT = "account_token_enc"
        const val KEY_DEVICE = "phone_device_id_enc"
        const val KEY_REFRESH = "refresh_token_enc"
        const val KEY_PAIRED_TVS = "paired_tvs_enc"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
