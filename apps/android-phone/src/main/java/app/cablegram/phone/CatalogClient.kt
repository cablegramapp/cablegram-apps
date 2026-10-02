package app.cablegram.phone

import android.util.Base64
import app.cablegram.phone.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class CatalogClient(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    /** Renews the two-hour access token on 401; omit only for anonymous calls. */
    private val tokens: AccountTokens? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .apply { if (tokens != null) authenticator(AccountAuthenticator(baseUrl, tokens)) }
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun importWeb(url: String, caption: String?, token: String, idempotencyKey: String): WebImportResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("url", url)
            caption?.takeIf { it.isNotBlank() }?.let { put("caption", it) }
            put("idempotency_key", "android:$idempotencyKey")
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/web-imports")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()
                    ?: "Could not analyze that link (${response.code})"
            }
            json.decodeFromString<WebImportResponse>(text)
        }
    }

    suspend fun saveWebToStorage(itemId: String, token: String): WebStorageResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/items/$itemId/storage-transfers")
            .header("Authorization", "Bearer $token")
            .post("{\"destination\":\"cablegram_managed\"}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()
                    ?: "Could not save that video (${response.code})"
            }
            json.decodeFromString<WebStorageResponse>(text)
        }
    }

    suspend fun libraryJob(jobId: String, token: String): LibraryJobResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/jobs/$jobId")
            .header("Authorization", "Bearer $token")
            .get().build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            check(response.isSuccessful) { runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull() ?: "Could not check transfer (${response.code})" }
            json.decodeFromString<LibraryJobResponse>(text)
        }
    }

    suspend fun suggestTitles(query: String, token: String?): List<TitleSuggestion> = withContext(Dispatchers.IO) {
        if (token.isNullOrBlank() || query.trim().length < 2) return@withContext emptyList()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/suggest?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                json.decodeFromString<TitleSuggestResponse>(response.body?.string().orEmpty()).suggestions
            }
        }.getOrDefault(emptyList())
    }

    suspend fun enrich(
        query: String,
        token: String?,
        itemId: String?,
        mediaType: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        year: Int? = null,
        imdbId: String? = null,
    ): CatalogMetadata? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("query", query)
            if (!itemId.isNullOrBlank()) put("itemId", itemId)
            if (!mediaType.isNullOrBlank()) put("mediaType", mediaType)
            if (seasonNumber != null) put("seasonNumber", seasonNumber)
            if (episodeNumber != null) put("episodeNumber", episodeNumber)
            if (year != null) put("year", year)
            if (!imdbId.isNullOrBlank()) put("imdbId", imdbId)
        })
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/enrich")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<MatchResponse>(response.body?.string().orEmpty()).metadata
            }
        }.getOrNull()
    }

    suspend fun resolveTitle(query: String, token: String?): TitleResolveResponse = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("query", query) })
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/resolve-title")
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@use TitleResolveResponse(found = false)
                json.decodeFromString<TitleResolveResponse>(response.body?.string().orEmpty())
            }
        }.getOrDefault(TitleResolveResponse(found = false))
    }

    suspend fun pingHealth(): String? = withContext(Dispatchers.IO) {
        val url = "${baseUrl.trimEnd('/')}/health"
        val fast = client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
        runCatching {
            fast.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (response.isSuccessful) null else "HTTP ${response.code}"
            }
        }.onFailure { PairLog.e("health check failed $url", it) }
            .getOrElse { it.message ?: "unreachable" }
    }

    suspend fun identifyStills(images: List<Pair<String, String>>, token: String?): IdentifyStillsResult = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext IdentifyStillsResult(error = "no stills")
        val body = json.encodeToString(buildJsonObject {
            put("images", buildJsonArray {
                images.forEach { (mime, data) ->
                    add(buildJsonObject {
                        put("mimeType", mime)
                        put("data", data)
                    })
                }
            })
        })
        val url = "${baseUrl.trimEnd('/')}/api/library/identify-stills"
        PairLog.i("POST identify-stills $url frames=${images.size} payloadKb=${body.length / 1024}")
        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        val longClient = client.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(95, TimeUnit.SECONDS)
            .build()
        runCatching {
            longClient.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                PairLog.i("POST identify-stills HTTP ${response.code} body=${text.take(180)}")
                if (!response.isSuccessful) {
                    return@use IdentifyStillsResult(error = "HTTP ${response.code}")
                }
                IdentifyStillsResult(metadata = json.decodeFromString<MatchResponse>(text).metadata)
            }
        }.onFailure { PairLog.e("identify-stills request failed api=$baseUrl", it) }
            .getOrElse { IdentifyStillsResult(error = it.message ?: "network error") }
    }

    suspend fun downloadBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://")) return@withContext null
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    suspend fun match(query: String): CatalogMetadata? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("query", query) })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/library/match")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<MatchResponse>(response.body?.string().orEmpty()).metadata
            }
        }.getOrNull()
    }

    suspend fun announce(pin: String, host: String, port: Int, publicBaseUrl: String? = null, deviceId: String? = null, token: String? = null, hardwareId: String? = null): Boolean = withContext(Dispatchers.IO) {
        if (deviceId.isNullOrBlank() || token.isNullOrBlank()) return@withContext false
        val body = json.encodeToString(buildJsonObject {
            put("lan_host", host)
            put("lan_port", port)
            hardwareId?.let { put("hardware_id", it) }
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/devices/$deviceId")
            .header("Authorization", "Bearer $token")
            .patch(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                PairLog.i("PATCH /api/devices/$deviceId HTTP ${response.code} ${PairLog.pinTail(pin)}")
                response.isSuccessful
            }
        }.onFailure { PairLog.e("announce request failed api=$baseUrl", it) }.getOrDefault(false)
    }

    /** Revokes this phone's refresh token (sign-out). Best effort. */
    suspend fun logout(refreshToken: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("refresh_token", refreshToken) })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/auth/logout")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /**
     * Deletes the signed-in account and everything stored for it (`POST /api/account/delete`). The server checks
     * [password] before deleting, so a wrong one is a 401 `invalid_credentials`.
     *
     * The request goes out without the token-renewing authenticator: that would resend a wrong password after
     * every 401 and count twice against the 15-minute sign-in lock. An expired access token (401 `unauthorized`,
     * answered before the password is looked at) is renewed here instead, once, and the request is repeated.
     * The password is only ever in the request body; nothing here logs it.
     */
    suspend fun deleteAccount(token: String, password: String): AccountDeletionResult = withContext(Dispatchers.IO) {
        val first = postDeleteAccount(token, password)
        if (first != AccountDeletionResult.SessionExpired || tokens == null) return@withContext first
        val fresh = renewedAccessToken(token) ?: return@withContext first
        postDeleteAccount(fresh, password)
    }

    private fun postDeleteAccount(token: String, password: String): AccountDeletionResult {
        val body = json.encodeToString(buildJsonObject { put("password", password) })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/account/delete")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newBuilder().authenticator(Authenticator.NONE).build().newCall(request).execute().use { response ->
                val error = runCatching {
                    json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                when {
                    response.code == 204 -> AccountDeletionResult.Deleted
                    response.code == 429 -> AccountDeletionResult.RateLimited
                    response.code == 401 && error == "invalid_credentials" -> AccountDeletionResult.WrongPassword
                    response.code == 401 -> AccountDeletionResult.SessionExpired
                    else -> AccountDeletionResult.Failed
                }
            }
        } catch (_: java.io.IOException) {
            AccountDeletionResult.Offline
        }
    }

    /** A cheap authenticated call; its 401 makes [AccountAuthenticator] renew the token. Null when it did not change. */
    private fun renewedAccessToken(stale: String): String? {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/me")
            .header("Authorization", "Bearer $stale")
            .get()
            .build()
        runCatching { client.newCall(request).execute().close() }
        return tokens?.accountToken?.takeIf { it.isNotBlank() && it != stale }
    }

    /** POST JSON; returns (status, body) with status null when the server can't be reached. */
    private fun postJson(path: String, token: String?, body: kotlinx.serialization.json.JsonObject): Pair<Int?, kotlinx.serialization.json.JsonObject?> {
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return runCatching {
            client.newCall(builder.build()).execute().use { response ->
                val parsed = runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
                response.code to parsed
            }
        }.getOrDefault(null to null)
    }

    /** Whether the signed-in account's email is confirmed; null when offline. */
    suspend fun emailVerified(token: String): Boolean? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/auth/email").header("Authorization", "Bearer $token").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["verified"]?.jsonPrimitive?.booleanOrNull
            }
        }.getOrNull()
    }

    suspend fun sendEmailCode(token: String) = withContext(Dispatchers.IO) { postJson("/api/auth/email/send-code", token, buildJsonObject {}) }

    suspend fun verifyEmail(token: String, code: String) = withContext(Dispatchers.IO) {
        postJson("/api/auth/email/verify", token, buildJsonObject { put("code", code) })
    }

    suspend fun forgotPassword(email: String) = withContext(Dispatchers.IO) {
        postJson("/api/auth/password/forgot", null, buildJsonObject { put("email", email) })
    }

    suspend fun resetPassword(email: String, code: String, password: String) = withContext(Dispatchers.IO) {
        postJson("/api/auth/password/reset", null, buildJsonObject { put("email", email); put("code", code); put("password", password) })
    }

    /** This month's relay usage for the household and this phone; null when offline. */
    suspend fun relayUsage(token: String, phoneDeviceId: String?): RelayUsage? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/relay/usage" + (phoneDeviceId?.let { "?device_id=$it" } ?: ""))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<RelayUsage>(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** Household profiles (owner first); null when offline. */
    suspend fun profiles(token: String): List<HouseholdProfile>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/profiles")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<HouseholdProfiles>(response.body?.string().orEmpty()).profiles
            }
        }.getOrNull()
    }

    /** Deletes a household profile with its progress and My List. Returns the HTTP status (null offline). */
    suspend fun deleteProfile(token: String, profileId: String): Int? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/profiles/$profileId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        runCatching { client.newCall(request).execute().use { it.code } }.getOrNull()
    }

    // ---- Telegram as cloud storage (spec 004, contracts/telegram-link.md) ----

    /** The household's Telegram link; null when offline. */
    suspend fun telegramLink(token: String): TelegramLinkInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/telegram/link")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<TelegramLinkInfo>(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** Records the linked account and library channel (no secrets). Throws when offline. */
    suspend fun putTelegramLink(
        token: String,
        telegramUserId: Long,
        displayName: String,
        libraryChatId: Long,
        phoneDeviceId: String?,
    ): TelegramLinkResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("telegram_user_id", telegramUserId.toString())
            put("display_name", displayName.take(128))
            put("library_chat_id", libraryChatId.toString())
            phoneDeviceId?.let { put("phone_device_id", it) }
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/telegram/link")
            .header("Authorization", "Bearer $token")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> TelegramLinkResult(json.decodeFromString<TelegramLinkInfo>(text))
                response.code == 409 -> TelegramLinkResult(
                    link = null,
                    conflictName = runCatching {
                        json.parseToJsonElement(text).jsonObject["linked_display_name"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull() ?: "another account",
                )
                else -> error("Could not save the Telegram link (${response.code})")
            }
        }
    }

    /** TVs waiting for this household's Telegram approval; null when offline. */
    suspend fun pendingTvLogins(token: String): List<PendingTvLogin>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/telegram/tv-logins/pending")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<PendingTvLogins>(response.body?.string().orEmpty()).requests
            }
        }.getOrNull()
    }

    /** Reports the outcome of a TV login; [error] must be a Telegram error name (never free text). */
    suspend fun postTvLoginResult(token: String, requestId: String, outcome: String, error: String? = null): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("outcome", outcome)
            error?.let { put("error", it) }
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/telegram/tv-logins/$requestId/result")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Registers a video from the household's library channel (spec 004 US3); idempotent by Telegram file. */
    suspend fun registerTelegramVideo(token: String, video: app.cablegram.telegram.TgVideo) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("source_kind", "telegram")
            put("origin_filename", video.fileName.ifBlank { "Telegram video ${video.messageId}" })
            put("origin_identity", app.cablegram.telegram.TelegramLibrarySync.originIdentity(video))
            put("stable_source_key", app.cablegram.telegram.TelegramLibrarySync.stableKey(video))
            if (video.file.size > 0) put("bytes_total", video.file.size)
            if (video.mimeType.isNotBlank()) put("playback_mime", video.mimeType.take(100))
            if (video.durationSeconds > 0) put("duration_seconds", video.durationSeconds)
            app.cablegram.telegram.TelegramLibrarySync.title(video)?.let { put("title", it) }
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/items")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { check(it.isSuccessful || it.code == 409) { "register ${it.code}" } }
    }

    /** Asks a Home TV to approve this phone's Telegram QR login (FR-023); the link goes to the TV, never to be stored. */
    suspend fun postPhoneLogin(token: String, loginLink: String, tvDeviceId: String, phoneDeviceId: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("login_link", loginLink)
            put("tv_device_id", tvDeviceId)
            // The TV names this phone when it asks the owner to allow the sign-in.
            put("phone_device_id", phoneDeviceId)
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/telegram/phone-logins")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** "Save to Telegram": adds the uploaded [video] as a Telegram source of the phone's title [localItemId]. */
    suspend fun attachTelegramCopy(token: String, localItemId: String, video: app.cablegram.telegram.TgVideo): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("source_kind", "telegram")
            // The server knows this phone title by the id the phone gave it, not by its own item id.
            put("attach_to_origin_identity", localItemId)
            put("origin_filename", video.fileName.ifBlank { "Telegram video ${video.messageId}" })
            put("origin_identity", app.cablegram.telegram.TelegramLibrarySync.originIdentity(video))
            put("stable_source_key", app.cablegram.telegram.TelegramLibrarySync.stableKey(video))
            if (video.file.size > 0) put("bytes_total", video.file.size)
            if (video.mimeType.isNotBlank()) put("playback_mime", video.mimeType.take(100))
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/items")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** After a full channel scan: sources not in [present] are marked unavailable. */
    suspend fun reconcileTelegramVideos(token: String, present: Set<String>) = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("present", buildJsonArray { present.forEach { add(it) } }) })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/sources/telegram/reconcile")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { check(it.isSuccessful) { "reconcile ${it.code}" } }
    }

    /** Renames a household title (PATCH /api/catalog/items/:id). */
    suspend fun patchTitle(token: String, itemId: String, title: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("title", title) })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/items/$itemId")
            .header("Authorization", "Bearer $token").patch(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Hides or deletes a title (`hide`, `delete`, `delete_source`); null when the request failed. */
    suspend fun removeTelegramTitle(token: String, itemId: String, mode: String, stableSourceKey: String? = null): TelegramRemovalResult? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("mode", mode)
            stableSourceKey?.let { put("stable_source_key", it) }
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/items/$itemId/removal")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                json.decodeFromString<TelegramRemovalResult>(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** Tells the control plane how a channel deletion ended; [error] is kept for the retry. */
    suspend fun reportTelegramDeletion(token: String, stableSourceKey: String, error: String? = null): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            if (error == null) put("outcome", "deleted") else { put("outcome", "failed"); put("error", error.take(500)) }
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/tombstones/$stableSourceKey/result")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun telegramTombstones(token: String): List<TelegramTombstone>? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/tombstones")
            .header("Authorization", "Bearer $token").get().build()
        runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                json.decodeFromString<TelegramTombstones>(r.body?.string().orEmpty()).tombstones
            }
        }.getOrNull()
    }

    suspend fun restoreTelegramTitle(token: String, stableSourceKey: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/catalog/tombstones/$stableSourceKey")
            .header("Authorization", "Bearer $token").delete().build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    // ---- TV trust and Telegram sessions (spec 004 US8) ----

    /** Changes a TV's trust (extend the end time, or opt in to direct Telegram). */
    suspend fun setTvTrust(token: String, tvDeviceId: String, trust: TvTrust): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("trust_level", if (trust.temporary) "temporary" else "home")
            if (trust.temporary) trust.expiresAt?.let { put("trust_expires_at", it.toString()) }
            put("telegram_direct_allowed", trust.telegramDirect)
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/devices/$tvDeviceId")
            .header("Authorization", "Bearer $token").patch(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Records the Telegram session a TV holds, so the phone can end it later. */
    suspend fun putTvTelegramSession(token: String, tvDeviceId: String, telegramSessionId: Long): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("telegram_session_id", telegramSessionId.toString()) })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/telegram/tv-sessions/$tvDeviceId")
            .header("Authorization", "Bearer $token").put(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** TV sessions to end now; null when offline. */
    suspend fun dueTvTelegramSessions(token: String): List<DueTvSession>? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/telegram/tv-sessions?due=true")
            .header("Authorization", "Bearer $token").get().build()
        runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use null
                json.decodeFromString<DueTvSessions>(r.body?.string().orEmpty()).sessions
            }
        }.getOrNull()
    }

    /** [outcome] is `terminated`, `already_gone`, or `failed` with a Telegram error name. */
    suspend fun reportTvSessionEnded(token: String, tvDeviceId: String, outcome: String, error: String? = null): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("outcome", outcome)
            error?.let { put("error", it) }
        })
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/telegram/tv-sessions/$tvDeviceId/terminated")
            .header("Authorization", "Bearer $token").post(body.toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** "Sign out of Telegram" for one TV: the phone ends its session and the TV wipes its Telegram data. */
    suspend fun signOutTvTelegram(token: String, tvDeviceId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/telegram/tv-sessions/$tvDeviceId/sign-out")
            .header("Authorization", "Bearer $token").post("{}".toRequestBody("application/json".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Disconnects Telegram for the household; TVs are told to log out. */
    suspend fun deleteTelegramLink(token: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/telegram/link")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** Revokes a paired TV for the whole household (it is signed out and loses LAN access). */
    suspend fun revokeDevice(deviceId: String, token: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/devices/$deviceId")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                PairLog.i("DELETE /api/devices/$deviceId HTTP ${response.code}")
                response.isSuccessful
            }
        }.onFailure { PairLog.e("revoke request failed api=$baseUrl", it) }.getOrDefault(false)
    }

    suspend fun pairPhone(pin: String, existingToken: String?, trust: TvTrust = TvTrust.Home): PhonePairResponse? = withContext(Dispatchers.IO) {
        if (existingToken.isNullOrBlank()) return@withContext null
        val body = json.encodeToString(buildJsonObject {
            put("pin", pin)
            if (trust.temporary) {
                put("trust_level", "temporary")
                trust.expiresAt?.let { put("trust_expires_at", it.toString()) }
                put("telegram_direct_allowed", trust.telegramDirect)
            }
        })
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/auth/device/claim")
            .header("Authorization", "Bearer $existingToken")
            .post(body.toRequestBody("application/json".toMediaType()))
        runCatching {
            client.newCall(builder.build()).execute().use { response ->
                PairLog.i("POST /api/auth/device/claim HTTP ${response.code}")
                if (!response.isSuccessful) return@use null
                val parsed = json.decodeFromString<LanClaimResponse>(response.body?.string().orEmpty())
                PhonePairResponse(
                    token = existingToken,
                    needsName = false,
                    tvName = "TV",
                    lanCapability = parsed.lanCapability,
                    tvDeviceId = parsed.deviceId,
                )
            }
        }.onFailure { PairLog.e("phone pair failed", it) }.getOrNull()
    }

    suspend fun registerAccount(email: String, password: String, accountName: String): PhonePairResponse? = withContext(Dispatchers.IO) {
        authAccount("/api/auth/register", email, password, 201, accountName)
    }

    suspend fun loginAccount(email: String, password: String): PhonePairResponse? = withContext(Dispatchers.IO) {
        authAccount("/api/auth/login", email, password, 200)
    }

    /** HTTP status of the last failed sign-in/registration (null: no response, e.g. offline). */
    var lastAuthFailureCode: Int? = null
        private set

    /** Error code of the last failed sign-in/registration, e.g. `disposable_email`. */
    var lastAuthFailureError: String? = null
        private set

    private fun authAccount(path: String, email: String, password: String, ok: Int, accountName: String? = null): PhonePairResponse? {
        lastAuthFailureCode = null
        val body = json.encodeToString(buildJsonObject {
            put("email", email)
            put("password", password)
            accountName?.trim()?.takeIf { it.isNotEmpty() }?.let { put("household_name", it) }
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}$path")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (response.code != ok && response.code !in 200..299) {
                    lastAuthFailureCode = response.code
                    lastAuthFailureError = runCatching {
                        json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject["error"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    return@use null
                }
                val parsed = json.decodeFromString<AuthTokenResponse>(response.body?.string().orEmpty())
                PhonePairResponse(
                    token = parsed.access_token,
                    refreshToken = parsed.refresh_token,
                    userId = parsed.user_id,
                    displayName = parsed.display_name,
                    needsName = parsed.display_name.isNullOrBlank(),
                    householdId = parsed.household_id,
                )
            }
        }.getOrNull()
    }

    suspend fun registerPhoneDevice(token: String, hardwareId: String? = null): PhoneDeviceResponse? = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { hardwareId?.let { put("hardware_id", it) } })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/devices/phone")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<PhoneDeviceResponse>(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /**
     * R-2 companion: discover revoked TV capabilities. When the phone can reach
     * the control plane it learns which household devices are revoked; the LAN
     * server then stops accepting those TVs' capability tokens mid-stream.
     * Returns the set of revoked device ids (empty when offline / error).
     */
    suspend fun fetchRevokedDeviceIds(token: String): Set<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptySet<String>()
                val me = json.decodeFromString<MeResponse>(response.body?.string().orEmpty())
                me.devices.filter { !it.revokedAt.isNullOrBlank() }.map { it.id }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    /** The signed-in household's name ("" when unnamed); null when offline or on error. */
    suspend fun householdName(token: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val household = json.decodeFromString<MeResponse>(response.body?.string().orEmpty()).household
                ((household as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
            }
        }.getOrNull()
    }

    /** Active (unrevoked) TVs in the signed-in household; null when offline. */
    suspend fun householdTvs(token: String): List<MeDevice>? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<MeResponse>(response.body?.string().orEmpty())
                    .devices.filter { it.kind == "tv" && it.revokedAt.isNullOrBlank() }
            }
        }.getOrNull()
    }

    /** Whether this phone's registered device still exists, unrevoked, in the signed-in household. */
    suspend fun phoneDeviceStatus(token: String, deviceId: String): PhoneDeviceStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/me")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use PhoneDeviceStatus.Unknown
                val me = json.decodeFromString<MeResponse>(response.body?.string().orEmpty())
                val device = me.devices.firstOrNull { it.id == deviceId && it.kind == "phone" }
                if (device != null && device.revokedAt.isNullOrBlank()) PhoneDeviceStatus.Registered else PhoneDeviceStatus.Missing
            }
        }.getOrDefault(PhoneDeviceStatus.Unknown)
    }

    // ---- T075 / R-5: private playback approvals (Constitution VI) ----

    /** Ask the control plane which private playback attempts are waiting. */
    suspend fun fetchPendingPrivateApprovals(token: String): List<PendingApproval> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/playback/private-approvals/pending")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<PendingApproval>()
                json.decodeFromString<PendingApprovalsResponse>(response.body?.string().orEmpty()).items
            }
        }.getOrDefault(emptyList())
    }

    suspend fun approvePrivatePlayback(token: String, attemptId: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/playback/private-approvals/$attemptId/approve")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<ApprovalResponse>(response.body?.string().orEmpty()).token
            }
        }.getOrNull()
    }

    suspend fun denyPrivatePlayback(token: String, attemptId: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/playback/private-approvals/$attemptId/deny")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** The TV device id owning [capability] in this household; null when unknown or offline. */
    suspend fun verifyLanCapability(token: String, capability: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/lan-capabilities/verify")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(buildJsonObject { put("capability", capability) }).toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.parseToJsonElement(response.body?.string().orEmpty())
                    .let { (it as kotlinx.serialization.json.JsonObject)["tv_device_id"] }
                    ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            }
        }.getOrNull()
    }

    /** Confirms a TV's LAN pass for a private title; null when invalid or offline. */
    suspend fun verifyLanPass(token: String, pass: String): VerifiedPass? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/playback/lan-passes/verify")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(buildJsonObject { put("pass", pass) }).toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = json.decodeFromString<LanPassResponse>(response.body?.string().orEmpty())
                VerifiedPass(
                    originIdentities = body.originIdentities.toSet(),
                    tvDeviceId = body.tvDeviceId,
                    expiresAtMs = java.time.Instant.parse(body.expiresAt).toEpochMilli(),
                )
            }
        }.getOrNull()
    }

    suspend fun setDisplayName(token: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject { put("displayName", name) })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/account/name")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    /** T078 / R-8: send a remote-control command, optionally targeted at one TV. */
    suspend fun postCommand(
        token: String,
        command: String,
        videoId: String? = null,
        targetDeviceId: String? = null,
        arguments: kotlinx.serialization.json.JsonObject = buildJsonObject {},
    ): Boolean = withContext(Dispatchers.IO) {
        if (targetDeviceId.isNullOrBlank()) return@withContext false
        val payload = remoteCommandBody(command, targetDeviceId, videoId, arguments)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/control/commands")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(payload).toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun pushLibrary(
        token: String,
        items: List<LibraryItem>,
        deviceId: String? = null,
        posterProvider: suspend (LibraryItem) -> ByteArray? = { null },
    ): Boolean {
        var all = true
        for (item in items) {
            if (!pushLibraryItem(token, item, deviceId, posterProvider)) all = false
        }
        return all
    }

    /** Flow 5: push one catalog item (with fingerprint); used by resumable sync. */
    suspend fun pushLibraryItem(
        token: String,
        item: LibraryItem,
        deviceId: String? = null,
        posterProvider: suspend (LibraryItem) -> ByteArray? = { null },
    ): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("origin_filename", item.filename.ifBlank { item.title })
            put("origin_identity", item.id)
            if (!isWeakLocalTitle(item)) put("title", item.title)
            item.posterUrl?.let { put("poster_url", it) }
            item.durationSeconds?.let { put("duration_seconds", it) }
            put("media_type", item.mediaType)
            put("private", item.isPrivate)
            if (item.genres.isNotEmpty()) put("genres", buildJsonArray { item.genres.forEach(::add) })
            if (item.sourceUri != null) {
                put("stable_source_key", item.sourceUri)
                put("phone_location", item.sourceUri)
            }
            item.fingerprint?.let { put("source_fingerprint", it) }
            if (!deviceId.isNullOrBlank()) put("serving_device_id", deviceId)
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/catalog/items")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val imported = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 409) return@use null
                response.body?.string()?.let { json.decodeFromString<CatalogImportResponse>(it) }
            }
        }.getOrNull() ?: return@withContext false
        if (item.posterUrl.isNullOrBlank() && !item.isPrivate) {
            val poster = posterProvider(item)
            if (poster != null && !uploadPoster(token, imported.id, poster)) return@withContext false
        }
        true
    }

    private suspend fun uploadPoster(token: String, itemId: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("poster_data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/catalog/items/$itemId/poster")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun reconcileSources(token: String, sources: List<LibraryItem>, deviceId: String? = null, fingerprintOf: (LibraryItem) -> String? = { null }): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            deviceId?.let { put("serving_device_id", it) }
            put("present_sources", buildJsonArray {
                sources.forEach { item ->
                    buildJsonObject {
                        item.sourceUri?.let {
                            put("stable_source_key", it)
                            put("phone_location", it)
                        }
                        fingerprintOf(item)?.let { put("source_fingerprint", it) }
                    }.also(::add)
                }
            })
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/catalog/sources/reconcile")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun fetchCatalog(token: String, failOnError: Boolean = false): List<RemoteCatalogItem> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/catalog/items")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Could not refresh the catalog (${response.code})" }
                json.decodeFromString<RemoteCatalogResponse>(response.body?.string().orEmpty()).items
            }
        }.getOrElse { if (failOnError) throw it else emptyList() }
    }

    suspend fun pullLibrary(token: String): List<LibraryItem>? = withContext(Dispatchers.IO) {
        val remote = fetchCatalog(token)
        if (remote.isEmpty()) return@withContext null
        remote.map { item ->
            val source = item.sources.firstOrNull()
            LibraryItem(
                id = source?.originIdentity ?: item.id,
                title = item.title ?: "Untitled",
                filename = source?.originFilename ?: item.title ?: "Untitled",
                durationSeconds = item.durationSeconds,
                posterUrl = item.posterUrl,
                importedAt = java.time.Instant.now().toString(),
            )
        }
    }

    suspend fun storageStatus(token: String): StorageStatusResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/storage/status")
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<StorageStatusResponse>(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /**
     * Connects the household's own R2 bucket (spec 005). The control plane checks the keys with a real write before it
     * keeps them. [secret] is sent once, over TLS, and is not kept by this class.
     */
    suspend fun connectR2(token: String, accountId: String, bucket: String, accessKeyId: String, secret: String): R2ConnectResult = withContext(Dispatchers.IO) {
        val body = json.encodeToString(buildJsonObject {
            put("account_id", accountId)
            put("bucket", bucket)
            put("access_key_id", accessKeyId)
            put("secret_access_key", secret)
        })
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/storage/connect/r2")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) return@use R2ConnectResult(null)
                val text = response.body?.string().orEmpty()
                // Only a stable error string is kept; a 400 means a field the server did not accept.
                val code = runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()
                R2ConnectResult(code?.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: if (response.code == 400) "invalid_request" else "http_${response.code}")
            }
        } catch (_: java.io.IOException) {
            R2ConnectResult("offline")
        }
    }

    /**
     * Starts the Google Drive sign-in (spec 006): the server makes the state and PKCE and answers with the Google URL to
     * open in a Custom Tab. [GoogleConnectStart.error] is the server's stable error string when it could not.
     */
    suspend fun connectGoogle(token: String): GoogleConnectStart = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/storage/connect/google")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val url = runCatching { json.parseToJsonElement(text).jsonObject["authorize_url"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    return@use if (url != null && url.startsWith("https://")) GoogleConnectStart(url, null) else GoogleConnectStart(null, "invalid_response")
                }
                val code = runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()
                GoogleConnectStart(null, code?.takeIf { it.matches(Regex("[a-z_]{1,40}")) } ?: "http_${response.code}")
            }
        } catch (_: java.io.IOException) {
            GoogleConnectStart(null, "offline")
        }
    }

    /** Save to Cloud against the household's own storage (specs 005, 006); [token] is the phone's account token. */
    fun ownCloudApi(token: String): OwnCloudApi = object : OwnCloudApi {
        private suspend fun call(path: String, method: String, body: String? = null): String = withContext(Dispatchers.IO) {
            val builder = Request.Builder().url("${baseUrl.trimEnd('/')}$path").header("Authorization", "Bearer $token")
            when (method) {
                "GET" -> builder.get()
                "DELETE" -> builder.delete()
                else -> builder.method(method, (body ?: "{}").toRequestBody("application/json".toMediaType()))
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Only the stable error string is kept; nothing the server echoed is shown or logged.
                    val code = runCatching { json.decodeFromString<ApiError>(text).error }.getOrNull()?.takeIf { it.matches(Regex("[a-z_]{1,40}")) }
                    throw R2ApiException(response.code, code ?: "http_${response.code}")
                }
                text
            }
        }

        override suspend fun start(originIdentity: String, sizeBytes: Long, contentType: String, fileName: String): R2UploadStart =
            json.decodeFromString(call("/api/storage/uploads", "POST", json.encodeToString(buildJsonObject {
                put("attach_to_origin_identity", originIdentity)
                put("size_bytes", sizeBytes)
                put("content_type", contentType)
                put("file_name", fileName)
            })))

        override suspend fun parts(uploadId: String, from: Int, count: Int): R2Parts =
            json.decodeFromString(call("/api/storage/uploads/$uploadId/parts?from=$from&count=$count", "GET"))

        override suspend fun complete(uploadId: String, parts: List<R2PartRef>): R2Done =
            json.decodeFromString(call("/api/storage/uploads/$uploadId/complete", "POST", json.encodeToString(buildJsonObject {
                put("parts", buildJsonArray { parts.forEach { add(buildJsonObject { put("part", it.part); put("etag", it.etag) }) } })
            })))

        override suspend fun session(uploadId: String): DriveSession =
            json.decodeFromString(call("/api/storage/uploads/$uploadId/session", "GET"))

        override suspend fun complete(uploadId: String): R2Done =
            json.decodeFromString(call("/api/storage/uploads/$uploadId/complete", "POST", "{}"))

        override suspend fun abort(uploadId: String) {
            call("/api/storage/uploads/$uploadId", "DELETE")
        }
    }

    /**
     * Where to read this phone title's copy in the household's own storage back from, with the headers that request needs
     * (a presigned R2 URL needs none; Google Drive needs a short-lived bearer token). Null if it has no copy.
     */
    suspend fun ownCloudReadUrl(token: String, originIdentity: String): ReadUrl? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/storage/read-url")
            .header("Authorization", "Bearer $token")
            .post(json.encodeToString(buildJsonObject { put("origin_identity", originIdentity) }).toRequestBody("application/json".toMediaType()))
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                parseReadUrl(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun disconnectStorage(token: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/storage/disconnect")
            .header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun ads(): AdsResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/ads").get().build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                json.decodeFromString<AdsResponse>(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private fun isWeakLocalTitle(item: LibraryItem): Boolean {
        val filenameTitle = item.filename.substringBeforeLast('.').trim()
        return item.title.isBlank() || item.title == filenameTitle || item.title == item.filename
    }
}

/** How `POST /api/account/delete` ended, see [CatalogClient.deleteAccount]. */
enum class AccountDeletionResult { Deleted, WrongPassword, RateLimited, SessionExpired, Offline, Failed }

enum class PhoneDeviceStatus { Registered, Missing, Unknown }
