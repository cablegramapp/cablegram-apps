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
    private val resources: () -> android.content.res.Resources,
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
        if (failed == null) step = SignInStep.Code(address) else message = failed.text(resources())
    }

    suspend fun resendCode() {
        val current = step as? SignInStep.Code ?: return
        run {
            val failed = client().requestSignInCode(current.email)
            message = failed?.text(resources()) ?: resources().getString(R.string.ui_a_new_code_is_on_its_way_8f5c29)
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
                message = resources().getString(R.string.ui_can_t_reach_cablegram_check_your_connection_0f7f38)
                return@run
            }
            when (val result = client().signUp(current.ticket, name.trim(), docs)) {
                is SignInResult.Failed -> when (result.error) {
                    "terms_outdated" -> {
                        legal = client().legalDocuments() ?: legal
                        message = resources().getString(R.string.ui_the_terms_or_privacy_policy_just_changed_please_read_t_fa977a)
                    }
                    "invalid_signup_ticket" -> {
                        step = SignInStep.Start
                        message = resources().getString(R.string.ui_that_took_too_long_start_again_8a44f3)
                    }
                    else -> message = result.text(resources())
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
            is SignInResult.Failed -> message = result.text(resources())
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
fun SignInResult.Failed.text(resources: android.content.res.Resources): String = when (error) {
    "invalid_code" -> resources.getString(R.string.ui_that_code_isn_t_right_check_the_email_and_try_again_38ed4a)
    "code_expired" -> resources.getString(R.string.ui_that_code_expired_or_was_tried_too_often_ask_for_a_new_0aa93e)
    "code_rate_limited" -> resources.getString(R.string.ui_a_code_was_sent_a_moment_ago_wait_a_minute_before_aski_cc929d)
    "rate_limited" -> resources.getString(R.string.ui_too_many_tries_wait_a_while_and_try_again_114288)
    "mail_unavailable" -> resources.getString(R.string.ui_we_couldn_t_send_the_email_try_again_in_a_few_minutes_fb5f5f)
    "disposable_email" -> resources.getString(R.string.ui_temporary_email_addresses_can_t_be_used_use_an_email_a_5c8d8f)
    "invalid_google_token" -> resources.getString(R.string.ui_google_sign_in_didn_t_work_try_again_78c785)
    "google_email_unverified" -> resources.getString(R.string.ui_your_google_account_s_email_isn_t_verified_sign_in_wit_c385f8)
    "google_account_mismatch" -> resources.getString(R.string.ui_this_email_is_already_linked_to_another_google_account_05cce9)
    "google_not_configured" -> resources.getString(R.string.ui_google_sign_in_isn_t_available_on_this_server_abf693)
    "terms_not_accepted" -> resources.getString(R.string.ui_tick_the_box_to_accept_the_terms_first_5e5035)
    "terms_outdated" -> resources.getString(R.string.ui_the_terms_just_changed_please_read_them_again_648707)
    else -> when (status) {
        null -> resources.getString(R.string.ui_can_t_reach_cablegram_check_your_connection_or_the_api_e3f77a)
        400 -> resources.getString(R.string.ui_check_the_email_address_and_try_again_5e6910)
        else -> resources.getString(R.string.ui_something_went_wrong_try_again_in_a_moment_156a12)
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
            GoogleIdResult.Failed(activity.resources.getString(R.string.ui_google_didn_t_return_an_account_try_again_or_use_an_em_555820))
        }
    } catch (_: GetCredentialCancellationException) {
        GoogleIdResult.Cancelled
    } catch (e: GetCredentialException) {
        PairLog.w("Google sign-in failed: ${e.type}")
        GoogleIdResult.Failed(activity.resources.getString(R.string.ui_google_sign_in_isn_t_available_right_now_use_an_email__d91f26))
    }
}
