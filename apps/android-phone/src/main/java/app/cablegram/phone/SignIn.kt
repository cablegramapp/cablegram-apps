package app.cablegram.phone

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.SecureRandom

/** Where the person is in signing in (spec 007). */
sealed interface SignInStep {
    /** Continue with Google, or an email for a code. */
    data object Start : SignInStep
    data class Code(val email: String) : SignInStep
    /** The email is proven but has no account: name, plus one unticked box for 18+ and the Terms. */
    data class CreateAccount(val ticket: String, val email: String, val name: String) : SignInStep
}

/**
 * Sign-in with Google or an email code, and Create your account. Nothing about a new person is stored on the server
 * before [createAccount]; [signedIn] runs once the server returned an account.
 */
class SignInController(
    private val client: () -> CatalogClient,
    private val signedIn: suspend (PhonePairResponse) -> Unit,
) {
    var step by mutableStateOf<SignInStep>(SignInStep.Start)
        private set
    /** Null until loaded, and while the server can't be reached: then only the email code is offered. */
    var methods by mutableStateOf<SignInMethods?>(null)
        private set
    var legal by mutableStateOf<LegalDocuments?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    /** What went wrong in the last step, ready to show; null when nothing did. */
    var message by mutableStateOf<String?>(null)

    /** The documents to link to: the server's, or the public pages when it can't be reached. */
    val documents: LegalDocuments get() = legal ?: LegalDocuments.Fallback

    suspend fun load() {
        val c = client()
        methods = c.signInMethods() ?: methods
        legal = c.legalDocuments() ?: legal
    }

    suspend fun requestCode(email: String) = run {
        val address = email.trim()
        val failed = client().requestSignInCode(address)
        if (failed == null) step = SignInStep.Code(address) else message = failed.text()
    }

    suspend fun resendCode() {
        val current = step as? SignInStep.Code ?: return
        run {
            val failed = client().requestSignInCode(current.email)
            message = failed?.text() ?: "A new code is on its way."
        }
    }

    suspend fun verifyCode(code: String) {
        val current = step as? SignInStep.Code ?: return
        run { handle(client().verifySignInCode(current.email, code.trim())) }
    }

    /** [idToken] came from Google for this sign-in's [nonce]; see [googleIdToken]. */
    suspend fun google(idToken: String, nonce: String) = run { handle(client().googleSignIn(idToken, nonce)) }

    suspend fun createAccount(name: String) {
        val current = step as? SignInStep.CreateAccount ?: return
        run {
            val docs = legal ?: client().legalDocuments()?.also { legal = it }
            if (docs == null) {
                message = "Can't reach Cablegram. Check your connection."
                return@run
            }
            when (val result = client().signUp(current.ticket, name.trim(), docs)) {
                is SignInResult.Failed -> when (result.error) {
                    "terms_outdated" -> {
                        legal = client().legalDocuments() ?: legal
                        message = "The Terms or Privacy Policy just changed. Please read them again."
                    }
                    "invalid_signup_ticket" -> {
                        step = SignInStep.Start
                        message = "That took too long. Start again."
                    }
                    else -> message = result.text()
                }
                else -> handle(result)
            }
        }
    }

    fun back() {
        step = SignInStep.Start
        message = null
    }

    /** Signed out, or the account was deleted: start over. */
    fun reset() {
        step = SignInStep.Start
        message = null
        busy = false
    }

    private suspend fun handle(result: SignInResult) {
        when (result) {
            is SignInResult.SignedIn -> {
                signedIn(result.account)
                step = SignInStep.Start
            }
            is SignInResult.SignupRequired -> step = SignInStep.CreateAccount(result.ticket, result.email, result.name.orEmpty())
            is SignInResult.Failed -> message = result.text()
        }
    }

    private suspend fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        try { block() } finally { busy = false }
    }
}

/** A short, specific sentence for a failed sign-in step. */
fun SignInResult.Failed.text(): String = when (error) {
    "invalid_code" -> "That code isn't right. Check the email and try again."
    "code_expired" -> "That code expired or was tried too often. Ask for a new one."
    "code_rate_limited" -> "A code was sent a moment ago. Wait a minute before asking again."
    "rate_limited" -> "Too many tries. Wait a while and try again."
    "mail_unavailable" -> "We couldn't send the email. Try again in a few minutes."
    "disposable_email" -> "Temporary email addresses can't be used. Use an email address you keep."
    "invalid_google_token" -> "Google sign-in didn't work. Try again."
    "google_email_unverified" -> "Your Google account's email isn't verified. Sign in with an email code instead."
    "google_account_mismatch" -> "This email is already linked to another Google account."
    "google_not_configured" -> "Google sign-in isn't available on this server."
    "terms_not_accepted" -> "Tick the box to accept the Terms first."
    "terms_outdated" -> "The Terms just changed. Please read them again."
    else -> when (status) {
        null -> "Can't reach Cablegram. Check your connection or the API server in Settings."
        400 -> "Check the email address and try again."
        else -> "Something went wrong. Try again in a moment."
    }
}

/** What the Sign in with Google sheet gave back. */
sealed interface GoogleIdResult {
    data class Token(val idToken: String, val nonce: String) : GoogleIdResult
    /** The person closed the sheet: nothing to report. */
    data object Cancelled : GoogleIdResult
    data class Failed(val message: String) : GoogleIdResult
}

/**
 * Shows Google's "Sign in with Google" sheet (Credential Manager) and returns an ID token for [serverClientId], our
 * web client. A fresh nonce goes into the token, and the server checks it. [activity] must be an Activity context.
 */
suspend fun googleIdToken(activity: Context, serverClientId: String): GoogleIdResult {
    val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
    val option = GetSignInWithGoogleOption.Builder(serverClientId).setNonce(nonce).build()
    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
    return try {
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            GoogleIdResult.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken, nonce)
        } else {
            GoogleIdResult.Failed("Google didn't return an account. Try again, or use an email code.")
        }
    } catch (_: GetCredentialCancellationException) {
        GoogleIdResult.Cancelled
    } catch (e: GetCredentialException) {
        PairLog.w("Google sign-in failed: ${e.type}")
        GoogleIdResult.Failed("Google sign-in isn't available right now. Use an email code instead.")
    }
}
