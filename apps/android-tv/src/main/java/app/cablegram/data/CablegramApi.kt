package app.cablegram.data

import app.cablegram.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response

/** Whether [url] is the control plane at [baseUrl] (same host and port), the only source of server time. */
internal fun isControlPlane(url: okhttp3.HttpUrl, baseUrl: String): Boolean {
    val base = baseUrl.toHttpUrlOrNull() ?: return false
    return url.host == base.host && url.port == base.port
}

class CablegramApi(
    private val baseUrl: String = BuildConfig.API_BASE_URL,
    // T-net/ANR: short, explicit timeouts so a stalled network fails fast
    // instead of leaving dozens of pending calls to pile up and thrash the
    // (often low-memory) TV process.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        // Learn how far this TV's clock is from the server's, so progress queued offline is stamped in server time.
        // Only the control plane's answers count: the same client also asks a phone on the LAN whether it answers,
        // and a phone's clock must not become the TV's idea of server time.
        .addInterceptor { chain ->
            chain.proceed(chain.request()).also { response ->
                if (isControlPlane(response.request.url, baseUrl)) ServerClock.shared.observe(response.headers.getDate("Date"))
            }
        }
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun getLibraryAds(): LibraryAds = try {
        execute(Request.Builder().url(url("api/ads")).get().build())
    } catch (_: Exception) {
        LibraryAds()
    }

    suspend fun createDeviceSession(deviceName: String, hardwareId: String? = null): DeviceSession {
        val body = json.encodeToString(buildJsonObject {
            put("deviceName", deviceName)
            hardwareId?.let { put("hardwareId", it) }
        })
        val request = Request.Builder()
            .url(url("api/auth/device"))
            .post(body.toRequestBody(jsonMediaType))
            .build()
        val dto = execute<DeviceSessionDto>(request)
        val qr = "cablegram://pair?token=${dto.pin}&name=${java.net.URLEncoder.encode(deviceName, "UTF-8")}"
        return DeviceSession(
            sessionId = dto.sessionId,
            pairingToken = dto.pairingToken,
            pin = dto.pin,
            qrUrl = qr,
            phoneQrUrl = qr,
            expiresAt = dto.expiresAt,
        )
    }

    suspend fun getPairingStatus(sessionId: String, pairingToken: String): PairingStatus {
        val request = Request.Builder()
            .url(url("api/auth/device/$sessionId"))
            .header("Authorization", "Pairing $pairingToken")
            .get()
            .build()
        val dto = execute<PairingPollDto>(request)
        return PairingStatus(
            status = dto.status,
            token = dto.accessToken,
            sessionId = sessionId,
            // First poll after pairing carries the LAN capability (R-1);
            // the phone's LAN server accepts only this token, not the PIN.
            lanCapability = dto.lanCapability,
        )
    }

    suspend fun getVideos(token: String, lanCapability: String? = null, profileId: String? = null): VideoLibrary {
        // Progress (resume position, Continue Watching) is per profile; without
        // profile_id the catalog carries no progress at all.
        val request = authenticatedRequest(catalogPath(profileId), token).get().build()
        return buildLibrary(execute<CatalogResponse>(request), token, lanCapability)
    }

    /** What a conditional library fetch found. */
    sealed interface LibraryFetch {
        data class Changed(val library: VideoLibrary, val etag: String?) : LibraryFetch
        /** The control plane answered 304: the list the caller already has is current. */
        data object NotModified : LibraryFetch
    }

    /**
     * [getVideos] with If-None-Match, for the TV's periodic sync: an unchanged library costs one small
     * 304 instead of the full list and a device lookup.
     */
    suspend fun getVideosIfChanged(token: String, lanCapability: String?, profileId: String?, etag: String?): LibraryFetch {
        val builder = authenticatedRequest(catalogPath(profileId), token)
        if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
        val request = builder.get().build()
        val fetched = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (response.code == 304) return@use null
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw decodeApiError(response.code, body, json)
                json.decodeFromString<CatalogResponse>(body) to response.header("ETag")
            }
        } ?: return LibraryFetch.NotModified
        return LibraryFetch.Changed(buildLibrary(fetched.first, token, lanCapability), fetched.second)
    }

    private suspend fun buildLibrary(catalog: CatalogResponse, token: String, lanCapability: String?): VideoLibrary {
        val devices = if (lanCapability.isNullOrBlank()) emptyList() else runCatching {
            execute<MeDto>(authenticatedRequest("api/me", token).get().build()).devices
        }.getOrDefault(emptyList())
        return VideoLibrary(
            videos = catalog.items.map { item ->
                val source = item.sources.firstOrNull { it.isPhoneSource() }
                    ?: item.sources.firstOrNull { it.kind != OWN_CLOUD && !it.originIdentity.isNullOrBlank() }
                    ?: item.sources.firstOrNull()
                val phone = servingPhone(devices, source)
                val localPosterUrl = if (phone != null && !source?.originIdentity.isNullOrBlank()) {
                    lanUrl(phone, "poster/${source?.originIdentity}", lanCapability)
                } else null
                Video(
                    id = item.id,
                    title = item.title,
                    // The phone may not know a file's duration; the TV learns it
                    // while playing and reports it with progress.
                    durationSeconds = item.durationSeconds ?: item.progress?.durationSeconds,
                    // Private posters deliberately are not published by the
                    // control plane. Local posters also cover failed uploads.
                    posterUrl = if (source?.isPrivate == true) localPosterUrl else item.posterUrl ?: localPosterUrl,
                    backdropUrl = item.backdropUrl,
                    genres = item.genres,
                    director = item.director,
                    releaseYear = item.year ?: item.releaseYear,
                    tmdbId = item.tmdbId,
                    overview = item.overview,
                    matchStatus = item.matchStatus,
                    // A finished title starts from the beginning and leaves Continue Watching.
                    resumePositionSeconds = item.progress?.takeIf { it.state != "completed" }?.positionSeconds,
                    mediaType = item.mediaType,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.episodeTitle,
                    tier = "hot",
                    ingestStage = "ready",
                    ingestProgress = 100,
                    source = source?.kind,
                    originIdentity = source?.originIdentity,
                    addedAtTimestamp = item.createdAt.orEmpty(),
                    inMyList = item.inMyList,
                )
            },
        )
    }

    private fun catalogPath(profileId: String?) =
        if (profileId.isNullOrBlank()) "api/catalog/items"
        else "api/catalog/items?profile_id=${java.net.URLEncoder.encode(profileId, "UTF-8")}"

    suspend fun getProfiles(token: String): ProfileLibrary =
        execute(authenticatedRequest("api/profiles", token).get().build())

    suspend fun createProfile(name: String, token: String) {
        val body = json.encodeToString(buildJsonObject { put("name", name); put("profileType", "adult") })
        executeNoContent(authenticatedRequest("api/profiles", token).post(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun requestProfileSwitch(profileId: String, deviceName: String, token: String): ProfileSwitchRequest {
        val body = json.encodeToString(buildJsonObject { put("profileId", profileId); put("deviceName", deviceName) })
        return execute(authenticatedRequest("api/profiles/switch-requests", token).post(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun getProfileSwitchRequest(requestId: String, token: String): ProfileSwitchRequest =
        execute(authenticatedRequest("api/profiles/switch-requests/$requestId", token).get().build())

    /** R-4 / FR-008: verify a profile PIN before the TV attaches to it. */
    /** Returns the HTTP status: 2xx unlocked, 403 wrong PIN, 429 locked out after too many tries. */
    suspend fun unlockProfile(profileId: String, pin: String, token: String): Int {
        val body = json.encodeToString(buildJsonObject { put("pin", pin) })
        val request = authenticatedRequest("api/profiles/$profileId/unlock", token)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { it.code }
        }
    }

    // ---- Telegram as cloud storage (spec 004, contracts/telegram-link.md) ----

    suspend fun telegramLink(token: String): TelegramLinkDto =
        execute(authenticatedRequest("api/telegram/link", token).get().build())

    /**
     * An iPhone-only household has no Cablegram phone app to link Telegram, so a TV that signed in by its own
     * QR code links it. The server accepts this only while the household has no link (403 otherwise).
     */
    suspend fun putTelegramLink(token: String, telegramUserId: Long, displayName: String, libraryChatId: Long): TelegramLinkDto {
        val body = json.encodeToString(buildJsonObject {
            put("telegram_user_id", telegramUserId)
            put("display_name", displayName)
            put("library_chat_id", libraryChatId)
        })
        return execute(authenticatedRequest("api/telegram/link", token).put(body.toRequestBody(jsonMediaType)).build())
    }

    /** Offers this TV's Telegram login link to the household phone; returns the request id. */
    suspend fun postTelegramTvLogin(token: String, loginLink: String): String {
        val body = json.encodeToString(buildJsonObject { put("login_link", loginLink) })
        val response: TelegramTvLoginDto = execute(authenticatedRequest("api/telegram/tv-logins", token).post(body.toRequestBody(jsonMediaType)).build())
        return response.requestId
    }

    suspend fun telegramTvLoginState(token: String, requestId: String): TelegramTvLoginStateDto =
        execute(authenticatedRequest("api/telegram/tv-logins/$requestId", token).get().build())

    /** Asks the household phone to type the Telegram password; it is sealed to [publicKey], so only this TV can read it. */
    suspend fun postTelegramPasswordRequest(token: String, publicKey: String, hint: String): String {
        val body = json.encodeToString(buildJsonObject {
            put("public_key", publicKey)
            if (hint.isNotBlank()) put("hint", hint.take(200))
        })
        val response: TelegramTvLoginDto = execute(authenticatedRequest("api/telegram/tv-password-requests", token).post(body.toRequestBody(jsonMediaType)).build())
        return response.requestId
    }

    /** Withdraws a password request this TV no longer needs, so the phone's notification goes away. */
    suspend fun cancelTelegramPasswordRequest(token: String, requestId: String) =
        executeNoContent(authenticatedRequest("api/telegram/tv-password-requests/$requestId", token).delete().build())

    /** The sealed password arrives here exactly once, in the answer where [TelegramPasswordRequestDto.state] is "delivered". */
    suspend fun telegramPasswordRequest(token: String, requestId: String): TelegramPasswordRequestDto =
        execute(authenticatedRequest("api/telegram/tv-password-requests/$requestId", token).get().build())

    /** Phones waiting for this Home TV to approve their Telegram QR login (spec 004 FR-023). */
    suspend fun pendingPhoneLogins(token: String): List<PendingPhoneLoginDto> =
        execute<PendingPhoneLoginsDto>(authenticatedRequest("api/telegram/phone-logins/pending", token).get().build()).requests

    /** [error] must be a Telegram error name, never free text. */
    suspend fun postPhoneLoginResult(token: String, requestId: String, outcome: String, error: String? = null) {
        val body = json.encodeToString(buildJsonObject {
            put("outcome", outcome)
            error?.let { put("error", it) }
        })
        executeNoContent(authenticatedRequest("api/telegram/phone-logins/$requestId/result", token).post(body.toRequestBody(jsonMediaType)).build())
    }

    /** Registers a video from the household's library channel (spec 004 T007); idempotent by file. */
    suspend fun registerTelegramVideo(token: String, video: app.cablegram.telegram.TgVideo) {
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
        executeNoContent(authenticatedRequest("api/catalog/items", token).post(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun reconcileTelegramVideos(token: String, present: Set<String>) {
        val body = json.encodeToString(buildJsonObject {
            put("present", kotlinx.serialization.json.JsonArray(present.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        })
        executeNoContent(authenticatedRequest("api/catalog/sources/telegram/reconcile", token).post(body.toRequestBody(jsonMediaType)).build())
    }

    suspend fun telegramLogoutAck(token: String) {
        executeNoContent(authenticatedRequest("api/telegram/logout-ack", token).post("{}".toRequestBody(jsonMediaType)).build())
    }

    /** A cheap authenticated call: fails with 401 once this TV's session is revoked or its account deleted (CAB-37). */
    suspend fun checkSession(token: String) = executeNoContent(authenticatedRequest("api/me", token).get().build())

    /** Revokes this TV's own device on the control plane (best effort on unpair). */
    suspend fun revokeSelf(token: String) {
        executeNoContent(authenticatedRequest("api/devices/self", token).delete().build())
    }

    suspend fun getCommands(token: String): TvCommandLibrary =
        execute(authenticatedRequest("api/control/commands?delivery=ack", token).get().build())

    suspend fun completeCommand(commandId: String, token: String) {
        executeNoContent(authenticatedRequest("api/control/commands/$commandId/complete", token).post("{}".toRequestBody(jsonMediaType)).build())
    }

    suspend fun rejectCommand(commandId: String, reason: String, token: String) {
        val body = json.encodeToString(buildJsonObject { put("reason", reason) })
        executeNoContent(authenticatedRequest("api/control/commands/$commandId/reject", token).post(body.toRequestBody(jsonMediaType)).build())
    }

    /** Opens the low-latency TV command channel. Completion is sent by the
     * caller only after the command has been dispatched to the TV UI/player. */
    fun openCommandSocket(
        token: String,
        onCommand: (TvCommand) -> Unit,
        onClosed: (WebSocket) -> Unit,
        onReady: () -> Unit = {},
        onReceipt: (String) -> Unit = {},
    ): WebSocket {
        val socketUrl = url("api/control/commands/socket")
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val request = Request.Builder().url(socketUrl).header("Authorization", "Bearer $token").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = json.parseToJsonElement(text).jsonObject
                    when (root["type"]?.jsonPrimitive?.content) {
                        "command" -> onCommand(json.decodeFromJsonElement<TvCommand>(root.getValue("command")))
                        "ready" -> onReady()
                        "receipt" -> onReceipt(root.getValue("id").jsonPrimitive.content)
                    }
                }.onFailure { webSocket.close(1008, "invalid_message") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClosed(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed(webSocket)
        })
    }

    internal fun commandTransport(): CommandTransport = object : CommandTransport {
        override fun connect(token: String, onReady: () -> Unit, onCommand: (TvCommand) -> Unit,
                             onReceipt: (String) -> Unit, onClosed: () -> Unit): CommandConnection {
            val socket = openCommandSocket(token, onCommand, { onClosed() }, onReady, onReceipt)
            return object : CommandConnection {
                override fun send(text: String) = socket.send(text)
                override fun close() { socket.cancel() }
            }
        }
        override suspend fun poll(token: String) = getCommands(token).commands
        override suspend fun result(token: String, record: CommandRecord) {
            if (record.status == "complete") completeCommand(record.command.id, token)
            else rejectCommand(record.command.id, record.reason ?: "unsupported_command", token)
        }
    }

    suspend fun reportPlaybackState(
        videoId: String?, positionSeconds: Int, durationSeconds: Int?, isPlaying: Boolean,
        volume: Int, muted: Boolean, engine: String? = null, token: String,
        profileId: String? = null,
    ) {
        val activeVideoId = videoId?.takeIf { it.isNotBlank() } ?: return
        // R-3: playback states ride the progress endpoint as state changes, so
        // pause/stop/completed are persisted, not just playing positions.
        val state = when {
            isPlaying -> "playing"
            positionSeconds > 0 && durationSeconds != null && durationSeconds > 0 &&
                positionSeconds >= durationSeconds * 95 / 100 -> "completed"
            else -> "paused"
        }
        runCatching {
            updateProgress(
                videoId = activeVideoId,
                positionSeconds = positionSeconds,
                token = token,
                profileId = profileId,
                state = state,
                durationSeconds = durationSeconds,
            )
        }
    }

    suspend fun attachProfile(profileId: String, token: String) {}

    suspend fun detachProfile(profileId: String, token: String) {}

    /** A saved, already-synchronized subtitle travels with the playback whichever path carries the video. */
    suspend fun getPlayback(
        videoId: String,
        token: String,
        lanPin: String? = null,
        onStatus: (String) -> Unit = {},
        onAwaitingApproval: () -> Unit = {},
        telegramUrl: suspend (String) -> String? = { null },
        telegramPhoneId: () -> String? = { null },
        /** Spec 006: the local stream for a cloud copy that needs a header (Google Drive); see [resolvePlayback]. */
        cloudStream: (url: String, headers: Map<String, String>) -> String? = { _, _ -> null },
    ): PlaybackResponse {
        val playback = resolvePlayback(videoId, token, lanPin, onStatus, onAwaitingApproval, telegramUrl, telegramPhoneId, cloudStream)
        if (playback.status != "ready" || playback.subtitles.isNotEmpty()) return playback
        val subtitles = runCatching {
            execute<SubtitlePlaybackResponse>(authenticatedRequest("api/subtitles/playback/item/$videoId", token).get().build()).subtitles
        }.getOrDefault(emptyList())
        return if (subtitles.isEmpty()) playback else playback.copy(subtitles = subtitles)
    }

    private suspend fun resolvePlayback(
        videoId: String,
        token: String,
        lanPin: String? = null,
        onStatus: (String) -> Unit = {},
        onAwaitingApproval: () -> Unit = {},
        /** Spec 004: a local URL for a `tg:<chat>:<message>` source through this TV's Telegram, or null. */
        telegramUrl: suspend (String) -> String? = { null },
        /** The phone that can stream Telegram titles to this TV (spec 004 US8); null when unknown. */
        telegramPhoneId: () -> String? = { null },
        /**
         * Spec 006: a local URL that plays [url] with [headers] for a player that cannot send them (LibVLC), or null when
         * there is none. Used only for a cloud copy that needs a header (Google Drive).
         */
        cloudStream: (url: String, headers: Map<String, String>) -> String? = { _, _ -> null },
    ): PlaybackResponse {
        val catalog = execute<CatalogResponse>(authenticatedRequest("api/catalog/items", token).get().build())
        val item = catalog.items.firstOrNull { it.id == videoId }
            ?: throw IllegalStateException("Video is not available")

        // T075 / R-5 (Constitution VI): a private item MUST NOT start streaming
        // until the authorized phone approves this exact attempt. No approval →
        // no URL, regardless of LAN availability or cached media.
        if (item.sources.any { it.isPrivate }) {
            return privatePlaybackFlow(videoId, item.title, item.posterUrl, token, lanPin, onStatus, onAwaitingApproval, cloudStream)
        }

        if (item.sources.any { it.kind == "web" }) {
            onStatus("Refreshing the original video link…")
            return resolveWebPlayback(videoId, token)
        }

        // Spec 004 FR-008: phone over Wi‑Fi, then Telegram, then the relay. Telegram needs neither the
        // phone nor its mobile data.
        val telegramSource = item.sources.firstOrNull {
            it.kind == "telegram" && it.availability != "unavailable" && !it.originIdentity.isNullOrBlank()
        }
        val telegram = telegramSource?.originIdentity?.let { telegramUrl(it) }
        // A phone copy that was freed up after Save to Telegram (archived, unavailable) is not a source to try.
        val source = item.sources.firstOrNull {
            it.isPhoneSource() && it.archiveState != "archived" && it.availability != "unavailable"
        }
        // Spec 005: a copy in the household's own R2 bucket. The control plane turns it into a short-lived
        // presigned URL, so playback needs neither the phone nor the relay.
        val hasR2 = item.sources.any { it.kind == OWN_CLOUD && it.availability != "unavailable" && it.archiveState != "archived" }
        // Spec 006: for Google Drive the answer also carries the bearer header and a short expiry, so the whole response is kept.
        var ownCloudLooked = false
        var ownCloudFound: PlaybackResponse? = null
        var ownCloudLimited = false
        val ownCloud: suspend () -> PlaybackResponse? = {
            if (!ownCloudLooked) {
                ownCloudLooked = true
                ownCloudFound = try {
                    resolveWebPlayback(videoId, token).takeIf { it.status == "ready" && it.url != null }
                        ?.let { withLocalCloudStream(it, cloudStream) }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: ApiException) {
                    if (failure.statusCode == 429) ownCloudLimited = true
                    null
                } catch (_: Exception) {
                    null
                }
            }
            ownCloudFound
        }
        val limitedNotice = {
            PlaybackResponse(
                status = "denied",
                prepareLabel = "Your cloud storage is limiting downloads of this video. Try again in a little while.",
                title = item.title,
                posterUrl = item.posterUrl,
            )
        }
        if (source == null && hasR2) {
            ownCloud()?.let { return it.copy(title = item.title, posterUrl = item.posterUrl, fallbackUrl = telegram) }
            if (ownCloudLimited && telegram == null) return limitedNotice()
        }
        if (source == null) {
            // No Telegram session on this TV (temporary TV, or not signed in yet): the phone streams it.
            val viaPhone = if (telegram == null && telegramSource != null) telegramThroughPhone(token, item, telegramSource, telegramPhoneId(), lanPin) else null
            return if (viaPhone != null) {
                viaPhone
            } else if (telegram != null) {
                // If Telegram stalls on this TV, the phone's own Telegram session can carry the rest; found only then.
                val resolver: (suspend () -> String?)? = if (telegramSource != null) {
                    { runCatching { telegramThroughPhone(token, item, telegramSource, telegramPhoneId(), lanPin)?.url }.getOrNull() }
                } else null
                PlaybackResponse(status = "ready", url = telegram, title = item.title, posterUrl = item.posterUrl, fallbackResolver = resolver)
            } else {
                PlaybackResponse(
                    status = "denied",
                    prepareLabel = if (telegramSource == null) "This video is no longer in your Telegram channel"
                    else "Telegram isn't connected on this TV. Check Settings → Telegram.",
                    title = item.title,
                    posterUrl = item.posterUrl,
                )
            }
        }
        val identity = source.originIdentity
        val me = execute<MeDto>(authenticatedRequest("api/me", token).get().build())
        val phone = servingPhone(me.devices, source)
        val host = phone?.lastLanHost
        if (phone != null && !identity.isNullOrBlank()) {
            // LAN first; the relay carries the same request when the phone isn't reachable here.
            val lan = if (!host.isNullOrBlank()) lanUrl(phone, "media/$identity", lanPin) else null
            val relay = relayUrl(phone, "media/$identity", lanPin, null, token)
            // Spec 005: LAN, then the user's R2 bucket, then the relay. R2 beats the relay because it
            // costs the phone's mobile data and Cablegram's relay quota nothing.
            if (lan == null && hasR2) {
                ownCloud()?.let {
                    return it.copy(title = item.title, posterUrl = item.posterUrl, fallbackUrl = telegram ?: relay)
                }
            }
            val primary = lan ?: relay
            if (primary != null) {
                return PlaybackResponse(
                    status = "ready",
                    url = primary,
                    title = item.title,
                    posterUrl = item.posterUrl,
                    fallbackUrl = if (lan != null) telegram ?: (if (hasR2) null else relay) else telegram,
                    // Looked up only if the LAN stalls: the cloud copy first, the relay when it cannot be reached. A fallback is a
                    // bare URL and must not carry a cloud token: Drive is used through the local CloudStreamServer URL, which holds
                    // the token itself, and a copy that would still need a header is skipped.
                    fallbackResolver = if (lan != null && telegram == null && hasR2) ({ ownCloud()?.takeIf { it.headers.isEmpty() }?.url ?: relay }) else null,
                )
            }
        }
        // No phone to ask on this Wi‑Fi and no relay: the bucket still has it.
        if (hasR2) {
            ownCloud()?.let { return it.copy(title = item.title, posterUrl = item.posterUrl, fallbackUrl = telegram) }
            if (ownCloudLimited && telegram == null) return limitedNotice()
        }
        if (telegram != null) {
            return PlaybackResponse(status = "ready", url = telegram, title = item.title, posterUrl = item.posterUrl)
        }
        return PlaybackResponse(
            status = "preparing",
            prepareStage = PHONE_OFFLINE_STAGE,
            prepareLabel = "Waiting for your phone on this Wi‑Fi",
            title = item.title,
            posterUrl = item.posterUrl,
        )
    }

    /**
     * T075: request a one-time approval for this playback attempt, then poll
     * until the phone approves (→ redeem token, return ready URL) or denies /
     * expires (→ denied state; TV MUST NOT start playback).
     */
    private suspend fun privatePlaybackFlow(
        videoId: String,
        title: String?,
        posterUrl: String?,
        token: String,
        lanPin: String?,
        onStatus: (String) -> Unit = {},
        onAwaitingApproval: () -> Unit = {},
        cloudStream: (String, Map<String, String>) -> String? = { _, _ -> null },
    ): PlaybackResponse {
        val request = runCatching {
            val body = json.encodeToString(buildJsonObject { put("media_item_id", videoId) })
            execute<GrantRequestDto>(
                authenticatedRequest("api/playback/private-approvals", token)
                    .post(body.toRequestBody(jsonMediaType)).build(),
            )
        }.getOrNull() ?: return PlaybackResponse(
            status = "denied",
            prepareLabel = "The control service is unreachable right now — try again in a moment",
            title = title,
            posterUrl = posterUrl,
        )
        val attemptId = request.attemptId
        onStatus("Waiting for your phone to approve. Tap Allow once in the Cablegram phone notification.")
        onAwaitingApproval()
        var settled = false
        try {
            return awaitApproval(attemptId, videoId, title, posterUrl, token, lanPin, cloudStream).also { settled = it.status == "ready" || it.prepareLabel == DENIED_LABEL }
        } finally {
            // Timed out or the viewer left: withdraw the request so phones stop
            // offering an approval nobody is waiting for.
            if (!settled) withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    executeNoContent(
                        authenticatedRequest("api/playback/private-approvals/$attemptId/cancel", token)
                            .post("{}".toRequestBody(jsonMediaType)).build(),
                    )
                }
            }
        }
    }

    private suspend fun awaitApproval(
        attemptId: String,
        videoId: String,
        title: String?,
        posterUrl: String?,
        token: String,
        lanPin: String?,
        cloudStream: (String, Map<String, String>) -> String? = { _, _ -> null },
    ): PlaybackResponse {
        var waited = 0
        while (waited < GRANT_WAIT_SECONDS * 1000) {
            delay(2000)
            waited += 2000
            val status = runCatching {
                execute<GrantStatusDto>(
                    authenticatedRequest("api/playback/private-approvals/$attemptId", token).get().build(),
                ).status
            }.getOrNull()
            when (status) {
                "approved" -> {
                    val current = execute<CatalogResponse>(authenticatedRequest("api/catalog/items", token).get().build())
                        .items.firstOrNull { it.id == videoId }
                    if (current?.sources?.any { it.kind == "web" || it.kind == "cloud_object" } == true) {
                        return resolveWebPlayback(videoId, token, attemptId)
                    }
                    // Spec 006: as for any title, the phone over Wi-Fi comes before the household's own cloud (R2, Drive),
                    // which costs internet bandwidth and the provider's download limits. One approval opens one path only,
                    // so the phone is checked before the approval is spent: the cloud copy is used when it doesn't answer.
                    val phoneSource = current?.sources?.firstOrNull {
                        it.isPhoneSource() && it.archiveState != "archived" && it.availability != "unavailable"
                    }
                    val hasOwnCloud = current?.sources?.any {
                        it.kind == OWN_CLOUD && it.availability != "unavailable" && it.archiveState != "archived"
                    } == true
                    var devices: List<DeviceHintDto>? = null
                    suspend fun devices() = devices ?: execute<MeDto>(authenticatedRequest("api/me", token).get().build()).devices.also { devices = it }
                    val phoneOnLan = phoneSource?.let { servingPhone(devices(), it) }
                    if (hasOwnCloud && (phoneOnLan == null || !answersOnLan(phoneOnLan))) {
                        // A private title in Google Drive needs the local stream too, or the player reaches Drive without its header.
                        return withLocalCloudStream(resolveWebPlayback(videoId, token, attemptId), cloudStream)
                    }
                    val source = phoneSource ?: current?.sources?.firstOrNull { it.kind != OWN_CLOUD && !it.originIdentity.isNullOrBlank() }
                    val identity = source?.originIdentity ?: return deniedPlayback(title, posterUrl)
                    val phone = phoneOnLan ?: servingPhone(devices(), source) ?: return deniedPlayback(title, posterUrl)
                    // Consume this approval once and get the pass the phone
                    // requires before it streams a private title.
                    val pass = runCatching {
                        execute<LanPassDto>(
                            authenticatedRequest("api/playback/private-approvals/$attemptId/start", token)
                                .post("{}".toRequestBody(jsonMediaType)).build(),
                        )
                    }.getOrNull() ?: return PlaybackResponse(
                        status = "denied",
                        prepareLabel = "This approval was already used or expired. Start playback again on the TV.",
                        title = title,
                        posterUrl = posterUrl,
                    )
                    val lan = phone.lastLanHost?.takeIf { it.isNotBlank() }?.let { lanUrl(phone, "media/$identity", lanPin, pass.lanPass) }
                    val relay = relayUrl(phone, "media/$identity", lanPin, pass.lanPass, token)
                    return PlaybackResponse(
                        status = "ready",
                        url = lan ?: relay ?: return deniedPlayback(title, posterUrl),
                        title = title,
                        posterUrl = posterUrl,
                        attemptId = attemptId,
                        fallbackUrl = if (lan != null) relay else null,
                    )
                }
                "denied", "consumed", "expired" -> return PlaybackResponse(
                    status = "denied",
                    prepareLabel = when (status) {
                        "denied" -> DENIED_LABEL
                        else -> "This request expired. Start playback again on the TV."
                    },
                    title = title,
                    posterUrl = posterUrl,
                )
                // pending → KEEP polling this same attempt (do NOT return:
                // returning would make the outer loop create a brand-new
                // approval attempt every few seconds → notification flood).
                // The label is reported by the caller's Resolving screen.
            }
        }
        return PlaybackResponse(
            status = "denied",
            prepareLabel = "No approval came in time. Start playback again when your phone is nearby.",
            title = title,
            posterUrl = posterUrl,
        )
    }

    private suspend fun resolveWebPlayback(videoId: String, token: String, attemptId: String? = null): PlaybackResponse {
        val body = json.encodeToString(buildJsonObject {
            put("media_item_id", videoId)
            attemptId?.let { put("attempt_id", it) }
        })
        return execute(
            authenticatedRequest("api/playback/resolve", token)
                .post(body.toRequestBody(jsonMediaType))
                .build(),
        )
    }

    /**
     * A bearer header the player cannot send (LibVLC, Google Drive) is added by the TV's local stream instead, so the token
     * stays out of the URL. Anything else, including a web title's Referer or User-Agent, is left as the server sent it.
     */
    private fun withLocalCloudStream(resolved: PlaybackResponse, cloudStream: (String, Map<String, String>) -> String?): PlaybackResponse {
        val url = resolved.url ?: return resolved
        if (resolved.headers.keys.none { it.equals("Authorization", ignoreCase = true) }) return resolved
        val local = cloudStream(url, resolved.headers) ?: return resolved
        return resolved.copy(url = local, headers = emptyMap())
    }

    private fun deniedPlayback(title: String?, posterUrl: String?) = PlaybackResponse(
        status = "denied",
        prepareLabel = "Playback wasn't approved on the phone",
        title = title,
        posterUrl = posterUrl,
    )

    private companion object {
        const val GRANT_WAIT_SECONDS = 60
        const val DENIED_LABEL = "Playback wasn't approved on the phone"
    }

    suspend fun getLoadingVideos(token: String): List<String> = try {
        execute<LoadingVideosResponse>(authenticatedRequest("api/videos/loading-videos", token).get().build()).videos
    } catch (_: Exception) {
        emptyList()
    }

    suspend fun requestTranscode(videoId: String, reason: String, token: String): TranscodeResponse {
        throw IllegalStateException("Cablegram does not transcode household files. Play from the phone on this network.")
    }

    suspend fun deleteFailedVideo(videoId: String, token: String) {}

    suspend fun updateProgress(
        videoId: String,
        positionSeconds: Int,
        token: String,
        profileId: String? = null,
        state: String = "playing",
        durationSeconds: Int? = null,
        clientUpdatedAt: Long? = null,
    ) {
        val pid = profileId ?: return
        val body = json.encodeToString(buildJsonObject {
            put("position_seconds", positionSeconds)
            put("state", state)
            durationSeconds?.let { put("duration_seconds", it) }
            clientUpdatedAt?.let { iso ->
                put(
                    "client_updated_at",
                    java.time.Instant.ofEpochMilli(iso).toString(),
                )
            }
        })
        val request = authenticatedRequest("api/profiles/$pid/progress/$videoId", token)
            .put(body.toRequestBody(jsonMediaType))
            .build()
        executeNoContent(request)
    }

    /** My List is stored per profile on the server and shared by every TV. */
    suspend fun setMyList(profileId: String, videoIds: List<String>, inMyList: Boolean, token: String) {
        val body = json.encodeToString(buildJsonObject {
            put("item_ids", kotlinx.serialization.json.JsonArray(videoIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            put("present", inMyList)
        })
        executeNoContent(authenticatedRequest("api/profiles/$profileId/my-list", token).put(body.toRequestBody(jsonMediaType)).build())
    }

    private fun authenticatedRequest(path: String, token: String) = Request.Builder()
        .url(url(path))
        .header("Authorization", "Bearer $token")

    private fun url(path: String): String = "${baseUrl.trimEnd('/')}/${path.trimStart('/')}"

    private val relayTickets = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()

    /**
     * Spec 003: the relay URL for a phone path. The short-lived ticket (`rt`) authorizes this TV at
     * the relay because LibVLC cannot send headers; `token`/`pass` are checked by the phone as on LAN.
     * Null when the control plane cannot issue a ticket (offline, relay disabled).
     */
    /** `/telegram/<unique file id>` on the phone that holds the Telegram session, over the LAN or the relay. */
    private suspend fun telegramThroughPhone(
        token: String, item: CatalogItemDto, source: CatalogSourceDto, phoneId: String?, lanPin: String?,
    ): PlaybackResponse? {
        val uniqueId = source.stableSourceKey?.removePrefix("tgfile:")?.takeIf { it.isNotBlank() } ?: return null
        val me = execute<MeDto>(authenticatedRequest("api/me", token).get().build())
        val phone = me.devices.firstOrNull { it.kind == "phone" && it.revokedAt.isNullOrBlank() && (phoneId == null || it.id == phoneId) } ?: return null
        val lan = if (!phone.lastLanHost.isNullOrBlank()) lanUrl(phone, "telegram/$uniqueId", lanPin) else null
        val relay = relayUrl(phone, "telegram/$uniqueId", lanPin, null, token)
        val primary = lan ?: relay ?: return null
        return PlaybackResponse(
            status = "ready", url = primary, title = item.title, posterUrl = item.posterUrl,
            fallbackUrl = if (lan != null) relay else null,
        )
    }

    private suspend fun relayUrl(phone: DeviceHintDto, path: String, capability: String?, privatePass: String?, token: String): String? {
        val phoneId = phone.id ?: return null
        val cached = relayTickets[phoneId]?.takeIf { it.second - System.currentTimeMillis() > 15 * 60_000L }?.first
        val ticket = cached ?: runCatching {
            val body = json.encodeToString(buildJsonObject { put("phone_device_id", phoneId) })
            val issued = execute<RelayTicketDto>(authenticatedRequest("api/relay/ticket", token).post(body.toRequestBody(jsonMediaType)).build())
            val expiresAt = runCatching { java.time.Instant.parse(issued.expiresAt).toEpochMilli() }.getOrDefault(System.currentTimeMillis() + 3_600_000L)
            relayTickets[phoneId] = issued.ticket to expiresAt
            issued.ticket
        }.getOrNull() ?: return null
        return okhttp3.HttpUrl.Builder()
            .scheme(if (baseUrl.startsWith("https")) "https" else "http")
            .host(baseUrl.toHttpUrl().host)
            .port(baseUrl.toHttpUrl().port)
            .addPathSegments("relay/v1/p/$phoneId/$path")
            .apply {
                capability?.let { addQueryParameter("token", it) }
                privatePass?.let { addQueryParameter("pass", it) }
                addQueryParameter("rt", ticket)
            }
            .build()
            .toString()
    }

    /** Whether the phone's library server answers on this Wi-Fi at all; any HTTP reply counts, nothing is fetched. */
    private suspend fun answersOnLan(phone: DeviceHintDto): Boolean = withContext(Dispatchers.IO) {
        val host = phone.lastLanHost?.takeIf { it.isNotBlank() } ?: return@withContext false
        val url = okhttp3.HttpUrl.Builder().scheme("http").host(host).port(phone.lastLanPort ?: 8765).addPathSegment("library").build()
        val quick = client.newBuilder().connectTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        runCatching { quick.newCall(Request.Builder().url(url).head().build()).execute().use { true } }.getOrDefault(false)
    }

    private fun lanUrl(phone: DeviceHintDto, path: String, capability: String?, privatePass: String? = null): String {
        val base = okhttp3.HttpUrl.Builder()
            .scheme("http")
            .host(requireNotNull(phone.lastLanHost))
            .port(phone.lastLanPort ?: 8765)
            .addPathSegments(path)
        if (!capability.isNullOrBlank()) base.addQueryParameter("token", capability)
        if (!privatePass.isNullOrBlank()) base.addQueryParameter("pass", privatePass)
        return base.build().toString()
    }

    private suspend inline fun <reified T> execute(request: Request): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw decodeApiError(response.code, body, json)
            json.decodeFromString<T>(body)
        }
    }


    private suspend fun executeNoContent(request: Request): Unit = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw decodeApiError(response.code, response.body?.string().orEmpty(), json)
            }
        }
    }
}

@Serializable
private data class DeviceSessionDto(
    @kotlinx.serialization.SerialName("session_id") val sessionId: String,
    val pin: String,
    @kotlinx.serialization.SerialName("pairing_token") val pairingToken: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class PairingPollDto(
    val status: String,
    @kotlinx.serialization.SerialName("access_token") val accessToken: String? = null,
    @kotlinx.serialization.SerialName("lan_capability") val lanCapability: String? = null,
)

@Serializable
private data class CatalogResponse(val items: List<CatalogItemDto> = emptyList())

@Serializable
private data class CatalogItemDto(
    val id: String,
    @kotlinx.serialization.SerialName("created_at") val createdAt: String? = null,
    @kotlinx.serialization.SerialName("in_my_list") val inMyList: Boolean = false,
    val title: String? = null,
    @kotlinx.serialization.SerialName("duration_seconds") val durationSeconds: Int? = null,
    @kotlinx.serialization.SerialName("poster_url") val posterUrl: String? = null,
    @kotlinx.serialization.SerialName("backdrop_url") val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val director: String? = null,
    @kotlinx.serialization.SerialName("release_year") val releaseYear: Int? = null,
    val year: Int? = null,
    @kotlinx.serialization.SerialName("tmdb_id") val tmdbId: Int? = null,
    @kotlinx.serialization.SerialName("media_type") val mediaType: String = "movie",
    @kotlinx.serialization.SerialName("season_number") val seasonNumber: Int? = null,
    @kotlinx.serialization.SerialName("episode_number") val episodeNumber: Int? = null,
    @kotlinx.serialization.SerialName("episode_title") val episodeTitle: String? = null,
    val overview: String? = null,
    @kotlinx.serialization.SerialName("match_status") val matchStatus: String = "matched",
    val sources: List<CatalogSourceDto> = emptyList(),
    val progress: CatalogProgressDto? = null,
)

@Serializable
private data class CatalogSourceDto(
    val kind: String? = null,
    @kotlinx.serialization.SerialName("origin_identity") val originIdentity: String? = null,
    @kotlinx.serialization.SerialName("private") val isPrivate: Boolean = false,
    @kotlinx.serialization.SerialName("serving_device_id") val servingDeviceId: String? = null,
    val availability: String? = null,
    @kotlinx.serialization.SerialName("stable_source_key") val stableSourceKey: String? = null,
    @kotlinx.serialization.SerialName("archive_state") val archiveState: String? = null,
)

@Serializable
private data class CatalogProgressDto(
    @kotlinx.serialization.SerialName("position_seconds") val positionSeconds: Int? = null,
    @kotlinx.serialization.SerialName("duration_seconds") val durationSeconds: Int? = null,
    val state: String? = null,
)

@Serializable
private data class MeDto(val devices: List<DeviceHintDto> = emptyList())

@Serializable
internal data class RelayTicketDto(
    val ticket: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
)

@Serializable
internal data class DeviceHintDto(
    val id: String? = null,
    val kind: String? = null,
    @kotlinx.serialization.SerialName("revoked_at") val revokedAt: String? = null,
    @kotlinx.serialization.SerialName("last_lan_host") val lastLanHost: String? = null,
    @kotlinx.serialization.SerialName("last_lan_port") val lastLanPort: Int? = null,
)

/** Playback is waiting for the serving phone to come online on the LAN. */
internal const val PHONE_OFFLINE_STAGE = "phone_offline"

/**
 * The phone that serves a title: the source's own serving device when known.
 * A household accumulates phone records (reinstalls, second phones); picking
 * the first one with a LAN hint streams from a stale address. Revoked phones
 * never serve.
 */
internal fun servingPhone(devices: List<DeviceHintDto>, servingDeviceId: String?): DeviceHintDto? {
    val phones = devices.filter { it.kind == "phone" && it.revokedAt.isNullOrBlank() }
    val serving = servingDeviceId?.let { id -> phones.firstOrNull { it.id == id } }
    // The known serving phone has not announced a LAN address yet: wait for it
    // instead of asking a different phone for a file it does not have.
    if (serving != null) return serving.takeIf { !it.lastLanHost.isNullOrBlank() }
    // Legacy sources carry no serving device; a revoked/re-registered one is gone.
    return phones.firstOrNull { !it.lastLanHost.isNullOrBlank() }
}

/** `own_cloud` is a copy in the household's own cloud storage (specs 005, 006), not a phone to ask. */
private const val OWN_CLOUD = "own_cloud"

private fun CatalogSourceDto.isPhoneSource() = kind != "telegram" && kind != OWN_CLOUD && !originIdentity.isNullOrBlank()

private fun servingPhone(devices: List<DeviceHintDto>, source: CatalogSourceDto?) =
    servingPhone(devices, source?.servingDeviceId)

@Serializable
private data class GrantRequestDto(
    @kotlinx.serialization.SerialName("attempt_id") val attemptId: String,
    @kotlinx.serialization.SerialName("expires_at") val expiresAt: String,
)

@Serializable
private data class GrantStatusDto(val status: String)

@Serializable
private data class LanPassDto(@kotlinx.serialization.SerialName("lan_pass") val lanPass: String)

/** T077: kept — the control plane still serves library ad copy (`api/ads`). */
@Serializable
data class LibraryAds(
    val enabled: Boolean = false,
    val headline: String? = null,
    val body: String? = null,
)

@Serializable
data class TelegramLinkDto(
    val linked: Boolean = false,
    @kotlinx.serialization.SerialName("telegram_user_id") val telegramUserId: kotlinx.serialization.json.JsonPrimitive? = null,
    @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
    @kotlinx.serialization.SerialName("library_chat_id") val libraryChatId: kotlinx.serialization.json.JsonPrimitive? = null,
    @kotlinx.serialization.SerialName("telegram_logout_required") val logoutRequired: Boolean = false,
    /** False for a temporary TV without the owner's opt-in: it plays Telegram titles through the phone. */
    @kotlinx.serialization.SerialName("telegram_direct") val telegramDirect: Boolean = true,
    @kotlinx.serialization.SerialName("trust_level") val trustLevel: String = "home",
    /** The phone whose Telegram session can stream titles to this TV. */
    @kotlinx.serialization.SerialName("phone_device_id") val phoneDeviceId: String? = null,
) {
    val userId: Long? get() = telegramUserId?.content?.toLongOrNull()
    val chatId: Long? get() = libraryChatId?.content?.toLongOrNull()
}

@Serializable
data class PendingPhoneLoginsDto(val requests: List<PendingPhoneLoginDto> = emptyList())

@Serializable
data class PendingPhoneLoginDto(
    @kotlinx.serialization.SerialName("request_id") val requestId: String,
    @kotlinx.serialization.SerialName("login_link") val loginLink: String,
    @kotlinx.serialization.SerialName("phone_name") val phoneName: String = "Phone",
    @kotlinx.serialization.SerialName("expires_in_ms") val expiresInMs: Long = 60_000,
)

@Serializable
data class TelegramTvLoginDto(@kotlinx.serialization.SerialName("request_id") val requestId: String)

@Serializable
data class TelegramPasswordRequestDto(val state: String, val sealed: String? = null)

@Serializable
data class TelegramTvLoginStateDto(val state: String, val error: String? = null)

