package app.cablegram.phone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Spec 007: how this server lets people in. `google` is null when the server has no Google client (self-hosted). */
@Serializable
data class SignInMethods(
    val google: GoogleMethod? = null,
    @SerialName("email_code") val emailCode: Boolean = true,
)

@Serializable
data class GoogleMethod(@SerialName("client_id") val clientId: String)

/** The current Terms of Service and Privacy Policy, as `GET /api/legal` reports them. */
@Serializable
data class LegalDocuments(val terms: LegalDocument, val privacy: LegalDocument) {
    companion object {
        /** Links that still work when the server can't be reached; the version is unknown, so nothing can be accepted. */
        val Fallback = LegalDocuments(
            LegalDocument("", "https://cablegram.app/terms.html"),
            LegalDocument("", "https://cablegram.app/privacy.html"),
        )
    }
}

@Serializable
data class LegalDocument(val version: String, val url: String)

/** What a sign-in step returned: an account, or a new person who must first create one. */
sealed interface SignInResult {
    data class SignedIn(val account: PhonePairResponse) : SignInResult
    /** The email is proven (by code or Google) but has no account: show Create your account. */
    data class SignupRequired(val ticket: String, val email: String, val name: String?) : SignInResult
    /** [status] null: no answer (offline). [error] is the server's error code, e.g. `invalid_code`. */
    data class Failed(val status: Int?, val error: String?) : SignInResult
}
