package app.cablegram.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never stringify credentials. Ciphertext lives in noBackupFilesDir (also excluded from device transfer). */
@Serializable class PersonalSubtitleCredentials(val key: String, val username: String = "", val password: String = "") {
    override fun toString() = "PersonalSubtitleCredentials(redacted)"
}
class SubtitleCredentialStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "subtitle-credentials").apply { mkdirs() }
    private val json = Json
    private fun alias(user: String, provider: String) = "cablegram.subtitles." + MessageDigest.getInstance("SHA-256").digest("$user:$provider".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(alias: String): SecretKey = (store().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
    @Synchronized fun read(user: String, provider: String): PersonalSubtitleCredentials? {
        val name = alias(user, provider); val file = File(directory, name)
        if (!file.exists()) return null
        return try {
            val bytes = file.readBytes(); require(bytes.size in 29..16384)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, store().getKey(name, null), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(name.toByteArray())
            json.decodeFromString<PersonalSubtitleCredentials>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        } catch (_: Exception) { remove(user, provider); null }
    }
    @Synchronized fun save(user: String, provider: String, credentials: PersonalSubtitleCredentials) {
        require(provider in setOf("subdl", "opensubtitles"))
        require(credentials.key.isNotBlank() && credentials.key.length <= 4096 && credentials.key.all { it.code in 33..126 })
        require(credentials.username.length <= 1024 && credentials.password.length <= 4096)
        require(provider != "opensubtitles" || credentials.username.isNotBlank() && credentials.password.isNotBlank())
        val name = alias(user, provider); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(name)); cipher.updateAAD(name.toByteArray())
        val bytes = json.encodeToString(credentials).toByteArray()
        try {
            val file = android.util.AtomicFile(File(directory, name)); val out = file.startWrite()
            try { out.write(cipher.iv + cipher.doFinal(bytes)); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        } finally { bytes.fill(0) }
    }
    @Synchronized fun remove(user: String, provider: String) {
        val name = alias(user, provider); File(directory, name).delete(); File(directory, "$name.bak").delete()
        store().deleteEntry(name)
    }
    fun clear(user: String) { listOf("subdl", "opensubtitles").forEach { remove(user, it) } }
}
