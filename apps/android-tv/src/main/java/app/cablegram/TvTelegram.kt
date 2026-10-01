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
            is TelegramState.NeedsPassword -> _status.value = TvTelegramStatus.NeedsPassword(state.hint)
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
            val taken = (linked?.exceptionOrNull() as? ApiException)?.statusCode == 403
            PairLog.i("Standalone Telegram link failed: ${(linked?.exceptionOrNull() as? ApiException)?.error ?: "no channel or token"}")
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
        const val PHONE_LOGIN_POLL_MS = 4_000L
        const val IDLE_WIPE_MS = 12L * 60 * 60 * 1000
        /** NanoHTTPD's default socket timeout; the player may pause for a while between reads. */
        const val NANOHTTPD_SOCKET_READ_TIMEOUT = 5 * 60 * 1000
    }
}

