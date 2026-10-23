package app.cablegram.phone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Spec 007: how this server lets people in. `google` is null when the server has no Google client (self-hosted). */
@Serializable
data class SignInMethods(
    val mode: String = "account",
    val google: GoogleMethod? = null,
    @SerialName("email_code") val emailCode: Boolean = true,
)

@Serializable
data class GoogleMethod(@SerialName("client_id") val clientId: String)

@Serializable
data class ServerFeatures(
    val artwork: Boolean = false,
    @SerialName("ai_title_resolution") val aiTitleResolution: Boolean = false,
    val subtitles: Boolean = false,
    val relay: Boolean = false,
    @SerialName("web_import") val webImport: Boolean = false,
    @SerialName("cloud_storage") val cloudStorage: Boolean = false,
    val telegram: Boolean = true,
    @SerialName("lan_playback") val lanPlayback: Boolean = true,
    @SerialName("profile_lock") val profileLock: Boolean = true,
) {
    companion object {
        /** Older hosted servers that do not expose `GET /api/server` support all hosted features. */
        val HostedDefault = ServerFeatures(
            artwork = true,
            aiTitleResolution = true,
            subtitles = true,
            relay = true,
            webImport = true,
            cloudStorage = true,
            telegram = true,
            lanPlayback = true,
            profileLock = true,
        )
    }
}

@Serializable
data class ServerAuthConfig(
    val mode: String = "account",
    val google: GoogleMethod? = null,
    @SerialName("email_code") val emailCode: Boolean = true,
)

@Serializable
data class ServerCapabilities(
    @SerialName("api_version") val apiVersion: Int = 1,
    val edition: String = "hosted",
    @SerialName("server_version") val serverVersion: String = "0.1.0",
    val auth: ServerAuthConfig = ServerAuthConfig(),
    val features: ServerFeatures = ServerFeatures.HostedDefault,
    val legal: LegalDocuments? = null,
) {
    companion object {
        val HostedDefault = ServerCapabilities()
    }
}

@Serializable
data class JoinRequestCreated(
    val id: String,
    @SerialName("short_code") val shortCode: String,
    @SerialName("poll_secret") val pollSecret: String,
    @SerialName("expires_at") val expiresAt: String = "",
)

@Serializable
data class JoinRequestPollResponse(
    val id: String,
    val status: String,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("household_id") val householdId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("display_name") val displayName: String? = null,
)

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
