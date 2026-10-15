package app.cablegram.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Why the last attempt to delete the account did not delete it; the account is untouched in every case. */
enum class AccountDeletionError { WrongCode, RateLimited, SessionExpired, Offline, Failed }

/**
 * "Delete account" (Google Play requires it in the app), confirmed with a sign-in code mailed to the account's
 * address (spec 007: accounts have no password). The code is passed straight to [delete] and is never kept,
 * logged or stored here. Only when the server answers 204 does [wipeLocal] run; every failure
 * leaves the user signed in and able to try again.
 */
class AccountDeletionController(
    private val token: () -> String?,
    private val delete: suspend (token: String, code: String) -> AccountDeletionResult,
    private val wipeLocal: suspend () -> Unit,
) {
    /** True while the request runs: the confirm button stays disabled. */
    var running by mutableStateOf(false)
        private set

    var error by mutableStateOf<AccountDeletionError?>(null)
        private set

    /** Returns true when the account is gone and this phone has been signed out. */
    suspend fun submit(code: String): Boolean {
        if (running || code.isEmpty()) return false
        running = true
        error = null
        try {
            val accountToken = token()
            val result = if (accountToken.isNullOrBlank()) AccountDeletionResult.SessionExpired else delete(accountToken, code)
            error = when (result) {
                AccountDeletionResult.Deleted -> null
                AccountDeletionResult.WrongCode -> AccountDeletionError.WrongCode
                AccountDeletionResult.RateLimited -> AccountDeletionError.RateLimited
                AccountDeletionResult.SessionExpired -> AccountDeletionError.SessionExpired
                AccountDeletionResult.Offline -> AccountDeletionError.Offline
                AccountDeletionResult.Failed -> AccountDeletionError.Failed
            }
            if (result != AccountDeletionResult.Deleted) return false
            wipeLocal()
            return true
        } finally {
            running = false
        }
    }

    /** Leaving the confirmation screen forgets the last error. */
    fun reset() {
        error = null
    }
}
