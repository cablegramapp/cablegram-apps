package app.cablegram

import android.content.Context
import android.os.Build
import app.cablegram.data.ApiException
import app.cablegram.data.CablegramApi
import app.cablegram.data.PairLog
import app.cablegram.telegram.TdlibTelegramApi
import app.cablegram.telegram.TelegramDatabaseKey
import app.cablegram.telegram.TelegramLibrarySync
import app.cablegram.telegram.TelegramRole
import app.cablegram.telegram.TelegramSession
import app.cablegram.telegram.TelegramState
import app.cablegram.telegram.TgParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the TV shows about Telegram in Settings (spec 004 US2). */
sealed interface TvTelegramStatus {
    /** The household hasn't connected Telegram (or this build has no Telegram credentials). */
    data object Off : TvTelegramStatus
    /**
     * Nobody has linked Telegram to this household yet. The owner can do it from this TV by scanning its QR code
     * in the Telegram app: the way in for a household with no Cablegram phone app (an iPhone with the web app).
     */
    data object CanConnect : TvTelegramStatus
    data object Connecting : TvTelegramStatus
    /**
     * Waiting for the household phone to approve [link]; [since] is when waiting began (QR fallback after 20 s).
     * [standalone]: the owner chose to connect from this TV, so no phone is asked and the QR code shows at once.
     */
    data class WaitingForPhone(val link: String, val since: Long, val standalone: Boolean = false) : TvTelegramStatus
    data class Connected(val name: String, val libraryChatId: Long?) : TvTelegramStatus
    /**
     * The phone approved this TV, but the account has two-step verification: Telegram wants the password
     * on the new device too (as Telegram Desktop does after a QR scan). [error] explains a rejected try.
     */
    data class NeedsPassword(val hint: String, val busy: Boolean = false, val error: String? = null) : TvTelegramStatus
    data class Problem(val message: String) : TvTelegramStatus
    /** A temporary TV: Telegram titles play through the owner's phone; nothing Telegram is stored here. */
    data object ThroughPhone : TvTelegramStatus
}

/**
 * The TV's own Telegram session (spec 004 US2). Each TV is its own Telegram device; it never copies
 * the phone's session (FR-002). It starts only when the household has linked Telegram, and signs in
 * by posting its login link for the household phone to approve (FR-003), with a QR code as fallback.
 */
class TvTelegram(
    private val context: Context,
    private val api: CablegramApi,
    /** The library changed because of the channel (new or deleted videos): refresh what the TV shows. */
    private val onLibraryChanged: () -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<TvTelegramStatus>(TvTelegramStatus.Off)
    val status: StateFlow<TvTelegramStatus> = _status.asStateFlow()

    @Volatile private var session: TelegramSession? = null
    private var watchJob: Job? = null
    private var syncJob: Job? = null
    @Volatile private var token: String? = null
    @Volatile private var expectedUserId: Long? = null
    @Volatile private var libraryChatId: Long? = null
    @Volatile private var linkedPhoneId: String? = null
    /** The owner chose "Connect Telegram on this TV" for a household nobody has linked yet. */
    @Volatile private var standalone = false

    private val prefs = context.getSharedPreferences("cablegram_tv_trust", Context.MODE_PRIVATE)

    init {
        // A temporary TV left alone for 12 hours forgets the Telegram data it holds (spec 004 US8).
        if (prefs.getBoolean(PREF_TEMPORARY, false) && System.currentTimeMillis() - prefs.getLong(PREF_ACTIVE, 0L) > IDLE_WIPE_MS) {
            PairLog.i("Temporary TV idle for 12 h: wiping local Telegram data")
            wipeLocalData()
        }
    }

    private val _viaPhone = MutableStateFlow(prefs.getBoolean(PREF_VIA_PHONE, false))

    /**
     * The viewer chose to always play Telegram titles through the phone while this TV isn't signed in to Telegram, so the
     * TV stops asking. Signing in on the TV still wins: a signed-in TV streams straight from Telegram.
     */
    val viaPhone: StateFlow<Boolean> = _viaPhone.asStateFlow()

    fun setViaPhone(value: Boolean) {
        prefs.edit().putBoolean(PREF_VIA_PHONE, value).apply()
        _viaPhone.value = value
    }

    /** The phone that can stream Telegram titles to this TV when it holds no session of its own. */
    fun phoneDeviceId(): String? = linkedPhoneId

    val configured: Boolean get() = BuildConfig.TELEGRAM_API_ID != 0

    /** Called after the library loads (and on refreshes): follows the household link. */
    fun sync(currentToken: String) {
        if (!configured) return
        token = currentToken
        scope.launch {
            val link = runCatching { api.telegramLink(currentToken) }
                .onFailure { PairLog.e("Telegram link check failed", it) }
                .getOrNull() ?: return@launch
            prefs.edit()
                .putBoolean(PREF_TEMPORARY, link.trustLevel == "temporary")
                .putLong(PREF_ACTIVE, System.currentTimeMillis())
                .apply()
            linkedPhoneId = link.phoneDeviceId
            when {
                link.logoutRequired -> {
                    signOutAndWipe()
                    runCatching { api.telegramLogoutAck(currentToken) }
                }
                !link.linked -> if (session == null) {
                    _status.value = if (link.trustLevel == "home" && link.telegramDirect) TvTelegramStatus.CanConnect else TvTelegramStatus.Off
                }
                !link.telegramDirect -> {
                    // A temporary TV without the owner's opt-in holds no Telegram session at all (US8).
                    if (session != null) signOutAndWipe()
                    _status.value = TvTelegramStatus.ThroughPhone
                }
                else -> {
                    expectedUserId = link.userId
                    libraryChatId = link.chatId
                    ensureSession()
                }
            }
        }
    }

    /**
     * Signs this TV in by QR code for a household nobody has linked, then links it (see [onState]). Only the owner
     * starts this, from Settings, and only while the household has no link. Elsewhere a TV never creates a channel.
     */
    fun connectStandalone() {
        if (!configured || session != null || _status.value != TvTelegramStatus.CanConnect) return
        standalone = true
        expectedUserId = null
        libraryChatId = null
        ensureSession()
    }

    /** The owner backed out of [connectStandalone] before finishing. */
    fun cancelConnect() {
        if (standalone) signOutAndWipe()
    }

    /** The two-step verification password, typed on the TV; it goes to Telegram only, never to Cablegram. */
    fun submitPassword(password: String) {
        session?.submitPassword(password)
    }

    private var passwordJob: Job? = null
    private var passwordRequestedAt = 0L

    /**
     * Asks the household phone to type the Telegram password (a notification with a text field). The phone seals it to a
     * one-time key made here, so the control plane relays bytes it cannot read; the password reaches Telegram only.
     * Safe to call again: an open request is kept, an expired or answered one is replaced by a fresh one.
     */
    fun askPhoneForPassword() {
        val waiting = _status.value as? TvTelegramStatus.NeedsPassword ?: return
        val currentToken = token ?: return
        if (passwordJob?.isActive == true && System.currentTimeMillis() - passwordRequestedAt < PASSWORD_REQUEST_MS) return
        passwordJob?.cancel()
        passwordRequestedAt = System.currentTimeMillis()
        passwordJob = scope.launch {
            val keys = app.cablegram.telegram.PasswordSeal.newTvKeys()
            val requestId = try {
                api.postTelegramPasswordRequest(currentToken, keys.publicKey, waiting.hint)
            } catch (e: ApiException) {
                // 409 not linked, 403 temporary TV: this TV is asked in Settings only.
                PairLog.i("Telegram password not asked from the phone: ${e.error ?: e.statusCode}")
                return@launch
            }
            // Collected: the sealed bytes are gone from the control plane. Otherwise the request is withdrawn when this
            // stops waiting (signed in on the TV, timed out, replaced), so the phone doesn't keep asking.
            var collected = false
            try {
                while (System.currentTimeMillis() - passwordRequestedAt < PASSWORD_REQUEST_MS && _status.value is TvTelegramStatus.NeedsPassword) {
                    kotlinx.coroutines.delay(PASSWORD_POLL_MS)
                    val answer = runCatching { api.telegramPasswordRequest(currentToken, requestId) }.getOrNull() ?: continue
                    if (answer.state != "delivered" && answer.state != "pending" && answer.state != "sealed") return@launch
                    val sealed = answer.sealed ?: continue
                    collected = true
                    val password = app.cablegram.telegram.PasswordSeal.open(keys, requestId, sealed)
                    if (password == null) {
                        PairLog.i("Telegram password from the phone could not be opened")
                        return@launch
                    }
                    PairLog.i("Telegram password received from the phone")
                    submitPassword(password)
                    val current = session ?: return@launch
                    kotlinx.coroutines.withTimeoutOrNull(20_000) { current.busy.first { !it } }
                    // An accepted password moves Telegram on, which can land just after the check returns: give it a moment.
                    kotlinx.coroutines.withTimeoutOrNull(PASSWORD_SETTLE_MS) { _status.first { it !is TvTelegramStatus.NeedsPassword } }
                    // Telegram refused it: ask again, so the phone offers the text field once more.
                    if (_status.value is TvTelegramStatus.NeedsPassword && current.lastError.value != null) {
                        passwordRequestedAt = 0L
                        askPhoneForPassword()
                    }
                    return@launch
                }
            } finally {
                if (!collected) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        runCatching { api.cancelTelegramPasswordRequest(currentToken, requestId) }
                    }
                }
            }
        }
    }

    /** Unpair, revocation, household disconnect: log out and delete everything Telegram left here. */
    fun signOutAndWipe() {
        val current = session
        session = null
        standalone = false
        postedLink = null
        watchJob?.cancel()
        watchJob = null
        syncJob?.cancel()
        syncJob = null
        phoneLoginJob?.cancel()
        phoneLoginJob = null
        passwordJob?.cancel()
        passwordJob = null
        streamServer?.stop()
        streamServer = null
        _status.value = TvTelegramStatus.Off
        scope.launch {
            current?.logOut()
            TelegramDatabaseKey(context).wipe()
        }
    }

    /**
     * Drops the Telegram session and database and the image cache this TV holds: after its end time, when
     * a temporary TV sat idle, or when the household disconnected. Safe to call any time.
     */
    fun wipeLocalData() {
        signOutAndWipe()
        runCatching { context.cacheDir.deleteRecursively() }
        runCatching { TelegramDatabaseKey(context).wipe() }
    }

    private fun ensureSession() {
        if (session != null) return
        _status.value = TvTelegramStatus.Connecting
        val keys = TelegramDatabaseKey(context)
        val created = TelegramSession(
            api = TdlibTelegramApi(),
            role = TelegramRole.Tv,
            parameters = {
                TgParameters(
                    databaseDirectory = keys.databaseDirectory.path,
                    filesDirectory = keys.filesDirectory.path,
                    databaseKey = keys.databaseKey(),
                    apiId = BuildConfig.TELEGRAM_API_ID,
                    apiHash = BuildConfig.TELEGRAM_API_HASH,
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    systemVersion = "Android TV ${Build.VERSION.RELEASE}",
                    applicationVersion = "Cablegram ${BuildConfig.VERSION_NAME}",
                )
            },
            scope = scope,
        )
        session = created
        created.start()
        watchJob = scope.launch { created.state.collect { onState(created, it) } }
        // Keep the password step's busy flag and rejection message in the status the UI shows.
        scope.launch {
            kotlinx.coroutines.flow.combine(created.busy, created.lastError) { busy, error -> busy to error }.collect { (busy, error) ->
                val current = _status.value
                if (current is TvTelegramStatus.NeedsPassword) _status.value = current.copy(busy = busy, error = error)
            }
        }
    }

    private var postedLink: String? = null

    private suspend fun onState(current: TelegramSession, state: TelegramState) {
        PairLog.i("Telegram on TV: ${state.javaClass.simpleName}")
        when (state) {
            is TelegramState.WaitingForApproval -> {
                val waitingSince = (_status.value as? TvTelegramStatus.WaitingForPhone)?.since ?: System.currentTimeMillis()
                _status.value = TvTelegramStatus.WaitingForPhone(state.link, waitingSince, standalone)
                // TDLib refreshes the token about every 30 s; each new link goes to the phone again.
                // A standalone sign-in has no phone to ask: the owner scans the code on screen.
                if (!standalone && state.link != postedLink) {
                    postedLink = state.link
                    val currentToken = token ?: return
                    try {
                        api.postTelegramTvLogin(currentToken, state.link)
                    } catch (e: ApiException) {
                        // 409 telegram_not_linked: the household disconnected meanwhile; the QR fallback stays.
                        PairLog.i("Telegram TV login not offered: ${e.error ?: e.statusCode}")
                    }
                }
            }
            is TelegramState.Ready -> {
                val expected = expectedUserId
                if (expected != null && state.user.id != expected) {
                    // Scanned with a different account than the household's (US2 acceptance 3).
                    signOutAndWipe()
                    _status.value = TvTelegramStatus.Problem(
                        "This TV was signed in to a different Telegram account than your household's. It signed out again.",
                    )
                    return
                }
                if (standalone && !linkStandalone(current, state.user)) return
                // Only the household's channel, by id (FR-005); a TV never creates or searches for one,
                // except in the standalone sign-in handled above.
                val library = libraryChatId?.let { current.openLibrary(knownChatId = it, allowCreate = false) }
                _status.value = TvTelegramStatus.Connected(state.user.displayName, library?.chatId)
                library?.let { startSync(current, it.chatId) }
                startPhoneLoginWatch(current)
            }
            is TelegramState.NeedsPassword -> {
                _status.value = TvTelegramStatus.NeedsPassword(state.hint)
                // Someone who chose the phone isn't nagged with a notification; Settings and the play prompt still ask.
                if (!_viaPhone.value) askPhoneForPassword()
            }
            is TelegramState.Failed -> _status.value = TvTelegramStatus.Problem(state.message)
            TelegramState.SignedOut -> _status.value = TvTelegramStatus.Off
            else -> if (_status.value !is TvTelegramStatus.WaitingForPhone) _status.value = TvTelegramStatus.Connecting
        }
    }

    /**
     * The standalone sign-in reached Ready: find or create the library channel and link the household to it.
     * False when it failed; the TV has then signed out again and says why.
     */
    private suspend fun linkStandalone(current: TelegramSession, user: app.cablegram.telegram.TgUser): Boolean {
        val currentToken = token
        val chatId = runCatching { current.openLibrary(knownChatId = null, allowCreate = true)?.chatId }.getOrNull()
        val linked = if (currentToken != null && chatId != null) {
            runCatching { api.putTelegramLink(currentToken, user.id, user.displayName, chatId) }
        } else null
        if (linked == null || linked.isFailure) {
            val failure = linked?.exceptionOrNull() as? ApiException
            val taken = failure?.statusCode == 403
            // Only the server's error name or a fixed reason: nothing that could sign anyone in reaches the log.
            val reason = failure?.error ?: "not_ready"
            PairLog.i("Standalone Telegram link failed: $reason")
            signOutAndWipe()
            _status.value = TvTelegramStatus.Problem(
                if (taken) "Telegram is already connected for this household. Ask the owner to use the app that connected it."
                else "Couldn't set up your Telegram library. Check your connection and try again.",
            )
            return false
        }
        standalone = false
        expectedUserId = user.id
        libraryChatId = chatId
        return true
    }

    /** Channel videos become library titles (US3); a TV registers with the file name or caption as title. */
    private fun startSync(current: TelegramSession, chatId: Long) {
        if (syncJob?.isActive == true) return
        val sync = TelegramLibrarySync(
            api = current.api,
            chatId = chatId,
            register = { video -> token?.let { api.registerTelegramVideo(it, video) } },
            reconcile = { keys -> token?.let { api.reconcileTelegramVideos(it, keys) } },
            onChanged = { onLibraryChanged() },
        )
        syncJob = scope.launch {
            runCatching { sync.fullScan() }
                .onSuccess { PairLog.i("Telegram library scan: $it video(s)") }
                .onFailure { PairLog.e("Telegram library scan failed", it) }
            sync.watch(this)
        }
    }

    private var phoneLoginJob: Job? = null

    /** A household phone asks this TV to sign it in to Telegram; shown full screen until answered (review fix). */
    data class PhoneLoginPrompt(val requestId: String, val phoneName: String, internal val answer: kotlinx.coroutines.CompletableDeferred<Boolean>)

    private val _phoneLoginPrompt = MutableStateFlow<PhoneLoginPrompt?>(null)
    val phoneLoginPrompt: StateFlow<PhoneLoginPrompt?> = _phoneLoginPrompt.asStateFlow()

    /** The owner answered the prompt with the TV's own remote. */
    fun answerPhoneLogin(allow: Boolean) {
        _phoneLoginPrompt.value?.answer?.complete(allow)
    }

    /**
     * FR-023: a phone that cannot sign in by itself asks a Home TV that already holds a session to approve its QR
     * login. Only a Home TV is ever asked (the server refuses temporary TVs), and only while this one is signed in.
     */
    private fun startPhoneLoginWatch(current: TelegramSession) {
        if (phoneLoginJob?.isActive == true) return
        phoneLoginJob = scope.launch {
            while (true) {
                val currentToken = token
                if (currentToken != null) {
                    val pending = runCatching { api.pendingPhoneLogins(currentToken) }.getOrDefault(emptyList())
                    for (request in pending) {
                        // Approving gives that phone a full session on the owner's account, and the request
                        // only proves a household phone token asked. So the owner confirms on the TV itself.
                        val prompt = PhoneLoginPrompt(request.requestId, request.phoneName, kotlinx.coroutines.CompletableDeferred())
                        _phoneLoginPrompt.value = prompt
                        val allowed = kotlinx.coroutines.withTimeoutOrNull(request.expiresInMs.coerceIn(5_000, 60_000)) { prompt.answer.await() }
                        _phoneLoginPrompt.value = null
                        if (allowed != true) {
                            if (allowed == false) runCatching { api.postPhoneLoginResult(currentToken, request.requestId, "denied", null) }
                            continue // unanswered: the request expires on the server
                        }
                        // Still a Home TV right now? Trust can change while the prompt is up.
                        val stillHome = runCatching { api.telegramLink(currentToken) }.getOrNull()
                            ?.let { it.trustLevel == "home" && it.telegramDirect } == true
                        if (!stillHome) {
                            runCatching { api.postPhoneLoginResult(currentToken, request.requestId, "failed", "TV_NOT_HOME") }
                            continue
                        }
                        val outcome = current.approvePhoneLogin(request.loginLink, BuildConfig.TELEGRAM_API_ID)
                        val error = (outcome.exceptionOrNull() as? app.cablegram.telegram.TgException)?.name
                            ?.uppercase()?.replace(Regex("[^A-Z0-9_ ]"), " ")?.trim()?.take(80)?.ifBlank { "UNKNOWN" }
                        runCatching { api.postPhoneLoginResult(currentToken, request.requestId, if (outcome.isSuccess) "approved" else "failed", error) }
                        PairLog.i("Telegram phone login: ${if (outcome.isSuccess) "approved" else "failed $error"}")
                    }
                }
                kotlinx.coroutines.delay(PHONE_LOGIN_POLL_MS)
            }
        }
    }

    private var streamServer: TelegramStreamServer? = null

    /**
     * A local URL LibVLC can play for a catalog source `tg:<chat>:<message>` (spec 004 US4), or null
     * when this TV's Telegram isn't signed in or the message is gone.
     */
    suspend fun streamUrl(originIdentity: String): String? {
        val (chatId, messageId) = Regex("""^tg:(-?\d+):(\d+)$""").find(originIdentity)?.destructured
            ?.let { (c, m) -> c.toLong() to m.toLong() } ?: return null
        if (chatId != libraryChatId) return null // only the household's channel (FR-005)
        val current = session?.takeIf { it.state.value is TelegramState.Ready } ?: return null
        val video = current.api.message(chatId, messageId)?.video ?: return null
        val server = synchronized(this) {
            streamServer ?: TelegramStreamServer(current.api).also { it.start(NANOHTTPD_SOCKET_READ_TIMEOUT, true); streamServer = it }
        }
        return server.urlFor(video.file.id)
    }

    /** The player closed: free what the stream stored (FR-007). */
    fun releasePlayback() {
        val server = streamServer ?: return
        scope.launch { server.releaseCurrent() }
    }

    private companion object {
        const val PREF_TEMPORARY = "temporary"
        const val PREF_ACTIVE = "last_active"
        const val PREF_VIA_PHONE = "telegram_via_phone"
        const val PHONE_LOGIN_POLL_MS = 4_000L
        const val PASSWORD_POLL_MS = 2_000L
        /** How long an accepted password may take to move Telegram past the password step. */
        const val PASSWORD_SETTLE_MS = 3_000L
        /** The server keeps a password request open for 5 minutes; stop polling a little before. */
        const val PASSWORD_REQUEST_MS = 285_000L
        const val IDLE_WIPE_MS = 12L * 60 * 60 * 1000
        /** NanoHTTPD's default socket timeout; the player may pause for a while between reads. */
        const val NANOHTTPD_SOCKET_READ_TIMEOUT = 5 * 60 * 1000
    }
}

