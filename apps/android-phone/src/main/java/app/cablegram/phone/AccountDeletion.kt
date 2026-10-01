package app.cablegram.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Why the last attempt to delete the account did not delete it; the account is untouched in every case. */
enum class AccountDeletionError { WrongPassword, RateLimited, SessionExpired, Offline, Failed }

/**
 * "Delete account" (Google Play requires it in the app). The password is passed straight to [delete] and is
 * never kept, logged or stored here. Only when the server answers 204 does [wipeLocal] run; every failure
 * leaves the user signed in and able to try again.
 */
class AccountDeletionController(
    private val token: () -> String?,
    private val delete: suspend (token: String, password: String) -> AccountDeletionResult,
    private val wipeLocal: suspend () -> Unit,
) {
    /** True while the request runs: the confirm button stays disabled. */
    var running by mutableStateOf(false)
        private set

    var error by mutableStateOf<AccountDeletionError?>(null)
        private set

    /** Returns true when the account is gone and this phone has been signed out. */
    suspend fun submit(password: String): Boolean {
        if (running || password.isEmpty()) return false
        running = true
        error = null
        try {
            val accountToken = token()
            val result = if (accountToken.isNullOrBlank()) AccountDeletionResult.SessionExpired else delete(accountToken, password)
            error = when (result) {
                AccountDeletionResult.Deleted -> null
                AccountDeletionResult.WrongPassword -> AccountDeletionError.WrongPassword
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
