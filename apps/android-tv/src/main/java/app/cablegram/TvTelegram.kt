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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The password step when Telegram asks for it: [checking] while a password is on its way, which keeps it busy instead of
 * flashing the keyboard choice (CAB-73). An open phone prompt's code and message carry over from [shown].
 */
internal fun passwordStatus(hint: String, shown: TvTelegramStatus, checking: Boolean): TvTelegramStatus.NeedsPassword {
    return when (shown) {
        is TvTelegramStatus.NeedsPassword -> TvTelegramStatus.NeedsPassword(hint, checking, null, shown.phoneRequestError, shown.phoneCode, shown.phoneSentAt)
        is TvTelegramStatus.NotSignedIn -> TvTelegramStatus.NeedsPassword(hint, checking, null, shown.phoneRequestError, shown.phoneCode, shown.phoneSentAt)
        else -> TvTelegramStatus.NeedsPassword(hint, busy = checking)
    }
}

/** "Ask my phone again" sends a new prompt only after this; sooner it keeps the open one (CAB-73). */
internal const val PHONE_REASK_MS = 30_000L

/** What the TV shows about Telegram in Settings (spec 004 US2). */
/** This TV has started signing in to the household's Telegram but can't stream from it yet. */
fun TvTelegramStatus.isSigningIn(): Boolean =
    this is TvTelegramStatus.NotSignedIn || this is TvTelegramStatus.Connecting || this is TvTelegramStatus.WaitingForPhone || this is TvTelegramStatus.NeedsPassword

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
     * The household linked Telegram, but this TV hasn't signed in and the viewer hasn't asked it to. Nothing goes to
     * Telegram's login until they do: with two-step verification ([twoStep] true, or null when unknown) the password is
     * collected first, so Telegram never reports an "incomplete login attempt" for a sign-in the viewer walked away from.
     */
    data class NotSignedIn(
        val twoStep: Boolean?,
        val phoneRequestError: String? = null,
        val phoneCode: String? = null,
        /** When the phone prompt reached the server; null while it is still being sent (CAB-73). */
        val phoneSentAt: Long? = null,
    ) : TvTelegramStatus
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
    data class NeedsPassword(
        val hint: String,
        val busy: Boolean = false,
        val error: String? = null,
        val phoneRequestError: String? = null,
        /** The check code of the key the phone should seal to ([app.cablegram.telegram.PasswordSeal.checkCode]). */
        val phoneCode: String? = null,
        /** When the phone prompt reached the server; null while it is still being sent (CAB-73). */
        val phoneSentAt: Long? = null,
    ) : TvTelegramStatus
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
    /** The viewer chose to sign this TV in: only then does its login link go to the phone (see [NotSignedIn]). */
    @Volatile private var armed = false
    /** The two-step password collected before signing in; handed to Telegram once it asks, then forgotten. */
    @Volatile private var heldPassword: String? = null
    @Volatile private var twoStep: Boolean? = null
    /** The newest login link TDLib made while nobody had asked to sign in yet. */
    @Volatile private var unpostedLink: String? = null
    /**
     * A password was given and Telegram hasn't answered yet (CAB-73): the password step stays "signing in" until Ready or
     * a rejection, even while TDLib is between states, and a second password or phone prompt can't start meanwhile.
     */
    @Volatile private var checkingPassword = false

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

    val configured: Boolean get() = true

    /** The TV account whose household this TV signs in to Telegram for; [ensureSession] records it. */
    @Volatile private var account: String? = null

    /** The session owner's token: the channel sync registers titles with it, never with another account's (CAB-66). */
    @Volatile private var ownerToken: String? = null

    /**
     * CAB-66: the Telegram session on this TV belongs to another account on it (a family or hotel TV). It is paused,
     * not signed out: its channel sync stops, its titles can't be streamed, and it resumes when its owner's profile
     * opens again. The open account plays its Telegram titles through its own phone.
     */
    @Volatile private var pausedForOther = false

    private fun heldByOther(accountId: String?): Boolean {
        val owner = prefs.getString(PREF_OWNER, null) ?: return false
        return accountId != null && owner != accountId && (session != null || TelegramDatabaseKey(context).hasLocalData())
    }

    private fun pauseForOtherAccount() {
        if (!pausedForOther) PairLog.i("Telegram session belongs to another account on this TV: paused")
        pausedForOther = true
        syncJob?.cancel()
        syncJob = null
        phoneLoginJob?.cancel()
        phoneLoginJob = null
        passwordJob?.cancel()
        passwordJob = null
        streamServer?.stop()
        streamServer = null
        libraryChatId = null
        expectedUserId = null
    }

    /** Called after the library loads (and on refreshes): follows the household link of [accountId]. */
    fun sync(currentToken: String, accountId: String?) {
        if (!configured) return
        token = currentToken
        account = accountId
        val other = heldByOther(accountId)
        // Stop the owner's channel sync before anything runs with this account's token.
        if (other) pauseForOtherAccount() else ownerToken = currentToken
        scope.launch {
            val link = runCatching { api.telegramLink(currentToken) }
                .onFailure { PairLog.e("Telegram link check failed", it) }
                .getOrNull() ?: return@launch
            linkedPhoneId = link.phoneDeviceId
            twoStep = link.twoStep
            if (other) {
                // Nothing this account's link says may sign out or wipe the owner's session (CAB-66).
                if (link.logoutRequired) runCatching { api.telegramLogoutAck(currentToken) }
                _status.value = if (link.linked) TvTelegramStatus.ThroughPhone else TvTelegramStatus.Off
                return@launch
            }
            pausedForOther = false
            prefs.edit()
                .putBoolean(PREF_TEMPORARY, link.trustLevel == "temporary")
                .putLong(PREF_ACTIVE, System.currentTimeMillis())
                .apply()
            (_status.value as? TvTelegramStatus.NotSignedIn)?.let { if (it.twoStep != link.twoStep) _status.value = it.copy(twoStep = link.twoStep) }
            if (link.telegramDirect && !link.logoutRequired) session?.retryStartup()
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
                    // Back from another account: the session is still signed in, so restart its sync and status.
                    val current = session
                    val ready = current?.state?.value as? TelegramState.Ready
                    if (current != null && ready != null) onState(current, ready)
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

    /**
     * The two-step verification password, typed on the TV or sealed from the phone; it goes to Telegram only, never to
     * Cablegram. Before signing in it is held and the sign-in starts now; Telegram gets it as soon as it asks.
     */
    fun submitPassword(password: String) {
        if (checkingPassword) return
        if (_status.value is TvTelegramStatus.NotSignedIn) {
            beginSignIn(password)
            return
        }
        val current = session ?: return
        checkingPassword = true
        (_status.value as? TvTelegramStatus.NeedsPassword)?.let { _status.value = it.copy(busy = true, error = null) }
        current.submitPassword(password)
    }

    /** The viewer chose to sign in, for an account without two-step verification (or one they say has none). */
    fun signInWithoutPassword() {
        if (_status.value is TvTelegramStatus.NotSignedIn) beginSignIn(null)
    }

    private fun beginSignIn(password: String?) {
        heldPassword = password
        checkingPassword = password != null
        armed = true
        val link = unpostedLink
        if (session != null && link != null) {
            unpostedLink = null
            scope.launch { offerLink(link) }
        } else {
            _status.value = TvTelegramStatus.Connecting
            ensureSession()
        }
    }

    private var passwordJob: Job? = null
    private var passwordRequestedAt = 0L

    /** Withdraw the phone prompt when the viewer changes keyboard or leaves the password screen. */
    fun cancelPhonePasswordRequest() {
        passwordJob?.cancel()
        passwordJob = null
        passwordRequestedAt = 0L
        setPhoneRequestError(null)
        setPhoneCode(null)
    }

    /** The phone prompt's problem, shown on whichever password step is up. */
    private fun setPhoneRequestError(message: String?) {
        when (val current = _status.value) {
            is TvTelegramStatus.NeedsPassword -> _status.value = current.copy(phoneRequestError = message)
            is TvTelegramStatus.NotSignedIn -> _status.value = current.copy(phoneRequestError = message)
            else -> Unit
        }
    }

    /**
     * Shows the check code of the key the phone is asked to seal to (null: no request open), and [sentAt], when the
     * prompt reached the server (null: still being sent).
     */
    private fun setPhoneCode(code: String?, sentAt: Long? = null) {
        when (val current = _status.value) {
            is TvTelegramStatus.NeedsPassword -> _status.value = current.copy(phoneCode = code, phoneSentAt = sentAt)
            is TvTelegramStatus.NotSignedIn -> _status.value = current.copy(phoneCode = code, phoneSentAt = sentAt)
            else -> Unit
        }
    }

    private fun waitingForPassword(status: TvTelegramStatus = _status.value) =
        status is TvTelegramStatus.NeedsPassword || status is TvTelegramStatus.NotSignedIn

    /**
     * Asks the household phone to type the Telegram password (a notification with a text field). The phone seals it to a
     * one-time key made here, so the control plane relays bytes it cannot read; the password reaches Telegram only.
     * Safe to call again: a request younger than [PHONE_REASK_MS] is kept, so a double press doesn't ask twice; an older,
     * expired or answered one is replaced by a fresh one, which the phone shows again.
     */
    fun askPhoneForPassword() {
        if (!waitingForPassword() || checkingPassword) return
        val hint = (_status.value as? TvTelegramStatus.NeedsPassword)?.hint.orEmpty()
        val currentToken = token ?: run {
            setPhoneRequestError("Pair your phone with this TV to use its keyboard, or choose the TV keyboard.")
            return
        }
        if (passwordJob?.isActive == true && System.currentTimeMillis() - passwordRequestedAt < PHONE_REASK_MS) return
        setPhoneRequestError(null)
        passwordJob?.cancel()
        passwordRequestedAt = System.currentTimeMillis()
        passwordJob = scope.launch {
            val keys = app.cablegram.telegram.PasswordSeal.newTvKeys()
            val code = app.cablegram.telegram.PasswordSeal.checkCode(keys.publicKey)
            setPhoneCode(code)
            val requestId = try {
                api.postTelegramPasswordRequest(currentToken, keys.publicKey, hint)
            } catch (e: ApiException) {
                // 409 not linked, 403 temporary TV: this TV is asked in Settings only.
                PairLog.i("Telegram password not asked from the phone: ${e.error ?: e.statusCode}")
                setPhoneRequestError(if (e.statusCode == 403 || e.statusCode == 409)
                    "The phone keyboard isn't available for this TV. Choose the TV keyboard instead."
                else "Couldn't send the phone prompt. Check your connection, send it again, or choose the TV keyboard.")
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                setPhoneRequestError("Couldn't send the phone prompt. Check your connection, send it again, or choose the TV keyboard.")
                return@launch
            }
            setPhoneCode(code, System.currentTimeMillis())
            PairLog.i("Telegram password asked from the phone")
            // Collected: the sealed bytes are gone from the control plane. Otherwise the request is withdrawn when this
            // stops waiting (signed in on the TV, timed out, replaced), so the phone doesn't keep asking.
            var collected = false
            try {
                while (System.currentTimeMillis() - passwordRequestedAt < PASSWORD_REQUEST_MS && waitingForPassword()) {
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
                    // Signed in, failed, or Telegram refused the password (the phone is then asked once more). Before
                    // signing in this spans the phone's approval too, so it may take a little while.
                    val outcome = kotlinx.coroutines.withTimeoutOrNull(PASSWORD_OUTCOME_MS) {
                        _status.first { it is TvTelegramStatus.Connected || it is TvTelegramStatus.Problem || (it is TvTelegramStatus.NeedsPassword && !it.busy && it.error != null) }
                    }
                    if (outcome is TvTelegramStatus.NeedsPassword) {
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

    /**
     * One account was revoked or removed from a TV that holds others (CAB-37). The Telegram session goes only if it is
     * that account's, or if this TV can't tell whose it is (signed in before owners were recorded): another
     * household's session is kept, and a wrongly dropped one only needs approving again from that household's phone.
     */
    fun signOutIfOwnedBy(accountId: String) {
        val owner = prefs.getString(PREF_OWNER, null)
        if (owner == accountId || (owner == null && (session != null || TelegramDatabaseKey(context).hasLocalData()))) {
            PairLog.i("Telegram on TV belonged to a removed account: signing out and wiping")
            signOutAndWipe()
        }
    }

    /**
     * The TV screen went away (its ViewModel was cleared) but the process lives on. Stops everything and releases
     * TDLib's database, keeping Telegram signed in: TDLib opens a database only once at a time, so the next
     * [TvTelegram] in this process waits for this before it starts (see [ensureSession]).
     */
    fun close() {
        val current = session
        session = null
        watchJob?.cancel()
        syncJob?.cancel()
        phoneLoginJob?.cancel()
        passwordJob?.cancel()
        streamServer?.stop()
        streamServer = null
        scope.cancel()
        val previous = closing
        closing = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            previous?.join()
            current?.api?.close()
        }
    }

    /** Unpair, revocation, household disconnect: log out and delete everything Telegram left here. */
    fun signOutAndWipe() {
        prefs.edit().remove(PREF_OWNER).apply()
        pausedForOther = false
        ownerToken = null
        val current = session
        session = null
        standalone = false
        armed = false
        heldPassword = null
        checkingPassword = false
        unpostedLink = null
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
        session?.let { it.retryStartup(); return }
        _status.value = TvTelegramStatus.Connecting
        val keys = TelegramDatabaseKey(context)
        // A database already on disk was signed in for its recorded owner; only a fresh one takes this account's.
        if (prefs.getString(PREF_OWNER, null) == null || !keys.hasLocalData()) {
            account?.let { prefs.edit().putString(PREF_OWNER, it).apply() }
        }
        val created = TelegramSession(
            api = TdlibTelegramApi(),
            role = TelegramRole.Tv,
            parameters = {
                closing?.join()
                val currentToken = token ?: throw app.cablegram.telegram.TelegramClientUnavailable()
                val credentials = api.telegramClientCredentials(currentToken)
                TgParameters(
                    databaseDirectory = keys.databaseDirectory.path,
                    filesDirectory = keys.filesDirectory.path,
                    databaseKey = keys.databaseKey(),
                    apiId = credentials.apiId,
                    apiHash = credentials.apiHash,
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
                // Telegram rejected the password: the viewer may try again. Cleared before the status changes, so
                // whoever waits for the rejection (the phone prompt) can ask again at once.
                if (error != null) checkingPassword = false
                val current = _status.value
                if (current is TvTelegramStatus.NeedsPassword) _status.value = current.copy(busy = busy || checkingPassword, error = error)
            }
        }
    }

    private var postedLink: String? = null

    /** Shows the login link and offers it to the household phone (or, standalone, as a QR code only). */
    private suspend fun offerLink(link: String) {
        val waitingSince = (_status.value as? TvTelegramStatus.WaitingForPhone)?.since ?: System.currentTimeMillis()
        _status.value = TvTelegramStatus.WaitingForPhone(link, waitingSince, standalone)
        // TDLib refreshes the token about every 30 s; each new link goes to the phone again.
        // A standalone sign-in has no phone to ask: the owner scans the code on screen.
        if (!standalone && link != postedLink) {
            postedLink = link
            val currentToken = token ?: return
            try {
                api.postTelegramTvLogin(currentToken, link)
            } catch (e: ApiException) {
                // 409 telegram_not_linked: the household disconnected meanwhile; the QR fallback stays.
                PairLog.i("Telegram TV login not offered: ${e.error ?: e.statusCode}")
            }
        }
    }

    private suspend fun onState(current: TelegramSession, state: TelegramState) {
        PairLog.i("Telegram on TV: ${state.javaClass.simpleName}")
        // Paused for another account (CAB-66): TDLib may still report states (a reconnect), but nothing may sync, ask a
        // phone or replace the open account's "through your phone" status. Its owner's next sync resumes it.
        if (pausedForOther) return
        when (state) {
            is TelegramState.WaitingForApproval -> {
                // Nobody asked this TV to sign in: keep the link here, so Telegram sees no login attempt (NotSignedIn).
                if (!standalone && !armed) {
                    checkingPassword = false
                    unpostedLink = state.link
                    val asked = _status.value as? TvTelegramStatus.NotSignedIn
                    _status.value = TvTelegramStatus.NotSignedIn(twoStep, asked?.phoneRequestError, asked?.phoneCode, asked?.phoneSentAt)
                    return
                }
                offerLink(state.link)
            }
            is TelegramState.Ready -> {
                checkingPassword = false
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
                val held = heldPassword
                heldPassword = null
                // TDLib may report the password step again while a password is on its way (CAB-73): stay busy, and keep
                // an open phone prompt's code and message on screen.
                _status.value = passwordStatus(state.hint, _status.value, checking = held != null || checkingPassword)
                // Collected before signing in: Telegram gets it at once. Otherwise the password UI asks for a keyboard.
                if (held != null) current.submitPassword(held)
            }
            is TelegramState.Failed -> {
                checkingPassword = false
                _status.value = TvTelegramStatus.Problem(state.message)
            }
            TelegramState.SignedOut -> {
                checkingPassword = false
                _status.value = TvTelegramStatus.Off
            }
            else -> {
                val shown = _status.value
                // A TV nobody asked to sign in stays "not signed in" while TDLib works in the background.
                if (shown !is TvTelegramStatus.WaitingForPhone && !(shown is TvTelegramStatus.NotSignedIn && !armed)) _status.value = TvTelegramStatus.Connecting
            }
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
        if (syncJob?.isActive == true || pausedForOther) return
        // The owner's token, fixed for this sync: another account opening on this TV must never receive these titles.
        val syncToken = ownerToken ?: token ?: return
        val sync = TelegramLibrarySync(
            api = current.api,
            chatId = chatId,
            register = { video -> api.registerTelegramVideo(syncToken, video) },
            reconcile = { keys -> api.reconcileTelegramVideos(syncToken, keys) },
            onChanged = { onLibraryChanged() },
            hints = { found ->
                api.postTelegramHints(syncToken, found)
                PairLog.i("Telegram channel: ${found.size} TMDB reference(s) sent")
            },
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
                        val outcome = current.approvePhoneLogin(request.loginLink, current.clientApiId)
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
        if (pausedForOther || chatId != libraryChatId) return null // only the open account's own channel (FR-005, CAB-66)
        val current = session?.takeIf { it.state.value is TelegramState.Ready } ?: return null
        val video = current.api.message(chatId, messageId)?.video ?: return null
        val server = synchronized(this) {
            streamServer ?: TelegramStreamServer(current.api).also { it.start(NANOHTTPD_SOCKET_READ_TIMEOUT, true); streamServer = it }
        }
        return server.register(video.file.id)
    }

    /**
     * The player closed: free what the stream stored (FR-007) and stop the server, so nothing listens on loopback
     * between plays. The next play starts a new one, on a new port with new stream ids.
     */
    fun releasePlayback() {
        val server = synchronized(this) { streamServer.also { streamServer = null } } ?: return
        scope.launch {
            server.releaseCurrent()
            server.stop()
        }
    }

    private companion object {
        /** The previous [TvTelegram] in this process releasing TDLib's database (see [close]). */
        @Volatile private var closing: Job? = null
        const val PREF_TEMPORARY = "temporary"
        const val PREF_ACTIVE = "last_active"
        const val PREF_VIA_PHONE = "telegram_via_phone"
        /** The TV account (pairing session) whose household this TV's Telegram session belongs to (CAB-37). */
        const val PREF_OWNER = "telegram_owner_account"
        const val PHONE_LOGIN_POLL_MS = 4_000L
        /** Polled only while a phone prompt is open, so the TV picks up the password within a second (CAB-73). */
        const val PASSWORD_POLL_MS = 1_000L
        /** Before signing in, a password from the phone also waits for the phone to approve this TV. */
        const val PASSWORD_OUTCOME_MS = 90_000L
        /** The server keeps a password request open for 5 minutes; stop polling a little before. */
        const val PASSWORD_REQUEST_MS = 285_000L
        const val IDLE_WIPE_MS = 12L * 60 * 60 * 1000
        /** NanoHTTPD's default socket timeout; the player may pause for a while between reads. */
        const val NANOHTTPD_SOCKET_READ_TIMEOUT = 5 * 60 * 1000
    }
}
