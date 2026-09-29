package app.cablegram.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("cablegram_auth", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getAccounts(): List<AccountCredential> {
        val encoded = preferences.getString(ACCOUNTS_KEY, null)
        if (encoded != null) {
            val accounts = runCatching {
                val decrypted = decrypt(encoded)
                json.decodeFromString<List<AccountCredential>>(decrypted)
            }.getOrNull()
            if (!accounts.isNullOrEmpty()) {
                return accounts
            }
        }

        // Backward compatibility migration: check legacy single-account keys
        val legacyToken = getLegacyToken()
        val legacySessionId = preferences.getString(SESSION_KEY, null)
        if (legacyToken != null && legacySessionId != null) {
            val account = AccountCredential(
                sessionId = legacySessionId,
                token = legacyToken,
                userId = extractUserIdFromToken(legacyToken),
            )
            val list = listOf(account)
            saveAccountsInternal(list)
            return list
        }

        return emptyList()
    }

    suspend fun saveAccount(account: AccountCredential) {
        val currentAccounts = getAccounts().toMutableList()
        val accountUserId = account.effectiveUserId

        // Remove existing account with the same sessionId or same non-null userId
        currentAccounts.removeAll { existing ->
            existing.sessionId == account.sessionId ||
                (accountUserId != null && existing.effectiveUserId == accountUserId)
        }
        currentAccounts.add(account)
        saveAccountsInternal(currentAccounts)
        setActiveSessionId(account.sessionId)
    }

    suspend fun removeAccount(sessionId: String) {
        val currentAccounts = getAccounts().filterNot { it.sessionId == sessionId }
        saveAccountsInternal(currentAccounts)
        if (getActiveSessionId() == sessionId) {
            setActiveSessionId(currentAccounts.firstOrNull()?.sessionId)
        }
    }

    suspend fun getActiveAccount(): AccountCredential? {
        val accounts = getAccounts()
        if (accounts.isEmpty()) return null
        val activeSessionId = getActiveSessionId()
        return accounts.firstOrNull { it.sessionId == activeSessionId } ?: accounts.first()
    }

    suspend fun getActiveSessionId(): String? = preferences.getString(ACTIVE_SESSION_KEY, null)

    suspend fun setActiveSessionId(sessionId: String?) {
        if (sessionId == null) {
            preferences.edit().remove(ACTIVE_SESSION_KEY).apply()
        } else {
            preferences.edit().putString(ACTIVE_SESSION_KEY, sessionId).apply()
        }
    }

    suspend fun getToken(): String? = getActiveAccount()?.token

    suspend fun getSessionId(): String? = getActiveAccount()?.sessionId

    suspend fun saveToken(token: String) {
        val currentSession = getSessionId() ?: "default_session"
        saveAccount(
            AccountCredential(
                sessionId = currentSession,
                token = token,
                userId = extractUserIdFromToken(token),
            ),
        )
    }

    suspend fun saveSessionId(sessionId: String) {
        val currentToken = getToken()
        if (currentToken != null) {
            saveAccount(
                AccountCredential(
                    sessionId = sessionId,
                    token = currentToken,
                    userId = extractUserIdFromToken(currentToken),
                ),
            )
        } else {
            setActiveSessionId(sessionId)
        }
    }

    suspend fun clear() {
        preferences.edit()
            .remove(TOKEN_KEY)
            .remove(SESSION_KEY)
            .remove(ACCOUNTS_KEY)
            .remove(ACTIVE_SESSION_KEY)
            .apply()
    }

    private fun saveAccountsInternal(accounts: List<AccountCredential>) {
        if (accounts.isEmpty()) {
            preferences.edit().remove(ACCOUNTS_KEY).apply()
            return
        }
        val serialized = json.encodeToString(accounts)
        val encrypted = encrypt(serialized)
        preferences.edit().putString(ACCOUNTS_KEY, encrypted).apply()
    }

    private fun getLegacyToken(): String? {
        val encoded = preferences.getString(TOKEN_KEY, null) ?: return null
        return runCatching { decrypt(encoded) }.getOrNull()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val encrypted = Base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "$iv:$encrypted"
    }

    private fun decrypt(encoded: String): String {
        val parts = encoded.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun encryptionKey(): SecretKey {
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
        const val KEY_ALIAS = "cablegram.tv.jwt"
        const val TOKEN_KEY = "encrypted_tv_jwt"
        const val SESSION_KEY = "tv_session_id"
        const val ACCOUNTS_KEY = "encrypted_tv_accounts"
        const val ACTIVE_SESSION_KEY = "active_tv_session_id"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
