package app.cablegram.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.TimeUnit

/** The account credentials an [AccountAuthenticator] reads and renews. */
interface AccountTokens {
    var accountToken: String?
    /** Rotated by the server on every renewal; the previous value stops working. */
    var refreshToken: String?
}

/**
 * Phone access tokens expire after two hours. Without renewal every
 * long-running path (private-playback approvals, LAN announce, library sync,
 * remote control) silently fails with 401 for the rest of the session.
 *
 * On a 401 this exchanges the stored refresh token for a new access token and
 * retries the request once. When the server rejects the refresh token (or
 * none was stored by an older build) the dead access token is cleared, so the
 * app asks the user to sign in again instead of failing quietly.
 */
class AccountAuthenticator(
    private val baseUrl: String,
    private val tokens: AccountTokens,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build(),
) : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        val header = response.request.header("Authorization") ?: return null
        if (!header.startsWith("Bearer ") || response.priorResponse != null) return null
        val sent = header.removePrefix("Bearer ")
        val fresh = synchronized(lock) {
            val current = tokens.accountToken
            // Another request renewed the token while this one was in flight.
            if (!current.isNullOrBlank() && current != sent) current else renew()
        } ?: return null
        return response.request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }

    private fun renew(): String? {
        val refreshToken = tokens.refreshToken
        if (refreshToken.isNullOrBlank()) {
            tokens.accountToken = null
            return null
        }
        val body = buildJsonObject { put("refresh_token", refreshToken) }.toString()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/auth/refresh")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) {
                    tokens.accountToken = null
                    return@use null
                }
                if (!response.isSuccessful) return@use null
                val body = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                // Store the rotated refresh token before the access token: the old one is already
                // revoked server-side, so losing the new one would sign the user out.
                body["refresh_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { tokens.refreshToken = it }
                body["access_token"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.also { tokens.accountToken = it }
            }
        }.onFailure { PairLog.w("Access token renewal failed", it) }.getOrNull()
    }

    private companion object {
        /** Shared by every client so concurrent 401s renew only once. */
        val lock = Any()
    }
}
