package app.cablegram.phone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val store = LibraryStore(application)
    private val indexer = MediaIndexer(application)
    private fun catalog() = CatalogClient(pairing.apiBaseUrl, pairing)
    private val pairing = PairingStore(application)
    private val commands = CommandQueue(application)
    private val webTransferPolls = mutableSetOf<String>()

    var items by mutableStateOf<List<LibraryItem>>(emptyList())
        private set
    var collections by mutableStateOf<List<UserCollection>>(emptyList())
        private set
    var folders by mutableStateOf<List<IndexedFolder>>(emptyList())
        private set
    var ads by mutableStateOf<AdsResponse?>(null)
        private set
    var status by mutableStateOf<String?>(null)

    /** T075: pending private-playback approvals surfaced inside the app. */
    var pendingApprovals by mutableStateOf<List<PendingApproval>>(emptyList())
        private set
    var connectionDetail by mutableStateOf<String?>(null)
        private set
    var tvName by mutableStateOf(pairing.tvName)
        private set
    var householdName by mutableStateOf(pairing.displayName)
        private set
    // Decided by the server (see [restoreHouseholdName]): a blank local cache
    // alone must not send a signed-in household back to the naming screen.
    var needsName by mutableStateOf(false)
        private set
    var paired by mutableStateOf(pairing.tvs.isNotEmpty())
        private set
    var addingTv by mutableStateOf(false)
        private set
    var needsAccount by mutableStateOf(pairing.accountToken.isNullOrBlank())
        private set
    var tvs by mutableStateOf(pairing.tvs)
        private set
    var busy by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf<String?>(null)
        private set
    var browseSource by mutableStateOf(BrowseSource.None)
        private set
    var browseVolumeName by mutableStateOf<String?>(null)
        private set
    var browseRoots by mutableStateOf<List<StorageRoot>>(emptyList())
        private set
    var browseTreeUri by mutableStateOf<Uri?>(null)
        private set
    var browseCrumbs by mutableStateOf<List<BrowseCrumb>>(emptyList())
        private set
    var browseEntries by mutableStateOf<List<BrowseEntry>>(emptyList())
        private set
    var browseVideosOnly by mutableStateOf(true)
    var browseLoading by mutableStateOf(false)
        private set
    var selectedBrowseIds by mutableStateOf<Set<String>>(emptySet())
        private set
    private val pendingTitles = ArrayDeque<LibraryItem>()
    private val pendingPrivacyItems = ArrayDeque<LibraryItem>()
    var prepareStep by mutableStateOf<String?>(null)
        private set
    var prepareProgress by mutableStateOf(0f)
        private set
    var lanAddress by mutableStateOf<String?>(null)
        private set
    var phoneIps by mutableStateOf(localIpv4Addresses())
        private set
    var apiBaseUrl by mutableStateOf(pairing.apiBaseUrl)
        private set
    var tunnelUrl by mutableStateOf(pairing.tunnelUrl)
        private set
    var tab by mutableStateOf(PhoneTab.Library)
    /** Household profiles for Settings (owner first); empty until loaded. */
    var profiles by mutableStateOf<List<HouseholdProfile>>(emptyList())
        private set
    private var nowPlayingState by mutableStateOf<LibraryItem?>(null)
    /** Mirrored into [CastSession] so the ongoing notification can act as a remote. */
    var nowPlaying: LibraryItem?
        get() = nowPlayingState
        private set(value) {
            nowPlayingState = value
            publishCast()
        }
    var remoteStatus by mutableStateOf<String?>(null)
        private set
    private var pausedState by mutableStateOf(false)
    var paused: Boolean
        get() = pausedState
        private set(value) {
            pausedState = value
            publishCast()
        }
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(false)
    var pendingTitleItem by mutableStateOf<LibraryItem?>(null)
        private set
    var pendingPrivacyItem by mutableStateOf<LibraryItem?>(null)
        private set
    var titleDraft by mutableStateOf("")
    var titleSuggestions by mutableStateOf<List<TitleSuggestion>>(emptyList())
        private set
    var previewFrames by mutableStateOf<List<String>>(emptyList())
        private set
    var selectedPreviewFrame by mutableStateOf<String?>(null)
    var identifyingStills by mutableStateOf(false)
        private set
    var pendingAiMatch by mutableStateOf<CatalogMetadata?>(null)
        private set
    var askingSeriesDetails by mutableStateOf(false)
        private set
    var seasonDraft by mutableStateOf("")
    var episodeDraft by mutableStateOf("")
    /** Suggestion explicitly picked for episode continuation (Flow 3). */
    var pendingSuggestion by mutableStateOf<TitleSuggestion?>(null)
        private set
    /** Explicit notes requiring acknowledgment: duplicates, specials. */
    var seriesNotes by mutableStateOf<List<String>>(emptyList())
        private set
    /** T-UX04: explains a pre-filled S/E suggestion ("based on the highest episode…"). */
    var seasonEpisodeSuggestLabel by mutableStateOf<String?>(null)
        private set
    /** Flow 3 confirm guards: duplicates need an explicit dialog; specials need season-0 ack. */
    private var duplicateEpisodeConfirmed = false
    private var allowSpecialsConfirmed = false
    /** T-UX04: pending duplicate the user must explicitly accept or cancel in a dialog. */
    var duplicatePrompt by mutableStateOf<Pair<String, Pair<Int, Int>>?>(null)
        private set
    var storage by mutableStateOf<StorageStatusResponse?>(null)
        private set
    var phoneStorage by mutableStateOf(store.phoneStorage())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var searchQuery by mutableStateOf("")
    var editingMetadata by mutableStateOf(false)
    var deleteTarget by mutableStateOf<LibraryItem?>(null)
    /** The title whose copy in the household's own storage the owner is being asked to remove (spec 006). */
    var removeCloudCopyTarget by mutableStateOf<LibraryItem?>(null)
    // Declared before `init`, which starts the queued Telegram deletions.
    private val PHONE_LOGIN_WAIT_MS = 120_000L
    private val telegramDeletions by lazy { TelegramDeletionQueue.forContext(getApplication()) }
    private val telegramDeletionMutex = kotlinx.coroutines.sync.Mutex()
    var collectionDraft by mutableStateOf("")
    var wifiOnlyTransfers by mutableStateOf(pairing.wifiOnlyTransfers)
    var transferWhileCharging by mutableStateOf(pairing.transferWhileCharging)
    var syncLibraryToApi by mutableStateOf(pairing.syncLibraryToApi)
    var librarySyncState by mutableStateOf(LibrarySyncState.Idle)
    var importDestination by mutableStateOf(pairing.importDestination)
    var cloudSheet by mutableStateOf(CloudSheet.None)
    var saveTargetId by mutableStateOf<String?>(null)
    var freeUpTargetId by mutableStateOf<String?>(null)
    var actionMenuId by mutableStateOf<String?>(null)
    var transferCardDismissed by mutableStateOf(false)
    var cablegramCloudReady by mutableStateOf(pairing.cablegramCloudReady)
    private var suggestJob: Job? = null
    private val librarySyncMutex = kotlinx.coroutines.sync.Mutex()

    val selected: LibraryItem? get() = selectedId?.let { id -> items.firstOrNull { it.id == id } }

    /** The series whose episodes are open, by [seriesKey]; the episode list is rebuilt from the library each time. */
    var openSeriesKey by mutableStateOf<String?>(null)
    val shownSeries: LibraryEntry.Series?
        get() = openSeriesKey?.let { key -> groupIntoEntries(items).filterIsInstance<LibraryEntry.Series>().firstOrNull { it.key == key } }

    fun openSeries(series: LibraryEntry.Series) {
        openSeriesKey = series.key
    }

    fun closeSeries() {
        openSeriesKey = null
    }
    val saveTarget: LibraryItem? get() = saveTargetId?.let { id -> items.firstOrNull { it.id == id } }
    val freeUpTarget: LibraryItem? get() = freeUpTargetId?.let { id -> items.firstOrNull { it.id == id } }
    val cloudConnected: Boolean get() = storage?.connection?.status == "active"
    val cloudUnlimited: Boolean get() = cloudConnected
    val cloudAvailable: Long get() = cloudAvailableBytes(items, unlimited = cloudUnlimited)
    val activeSave: LibraryItem? get() = items.firstOrNull { it.transferStatus == TRANSFER_SAVING }

    fun updateApiBaseUrl(value: String) {
        if (!isValidServerUrl(value)) {
            status = "Enter a valid http:// or https:// server address."
            return
        }
        pairing.apiBaseUrl = value.trim()
        apiBaseUrl = pairing.apiBaseUrl
        PairLog.i("Phone API URL set to $apiBaseUrl")
    }

    fun updateTunnelUrl(value: String) {
        if (value.isNotBlank() && !isValidServerUrl(value)) {
            status = "Enter a valid tunnel address, or leave it empty."
            return
        }
        pairing.tunnelUrl = value.trim()
        tunnelUrl = pairing.tunnelUrl
        if (paired) viewModelScope.launch { pairing.lanToken?.let { publishLanAddress(it) } }
    }

    /** Spec 003: relay streams over mobile data (Ask / Always / Never). */
    var relayMobileData by mutableStateOf(pairing.relayMobileData)
        private set

    fun updateRelayMobileData(policy: RelayMobileDataPolicy) {
        pairing.relayMobileData = policy
        relayMobileData = policy
    }

    fun setWifiOnly(value: Boolean) {
        pairing.wifiOnlyTransfers = value
        wifiOnlyTransfers = value
    }

    fun updateChargingOnly(value: Boolean) {
        pairing.transferWhileCharging = value
        transferWhileCharging = value
    }

    fun updateSyncLibrary(value: Boolean) {
        pairing.syncLibraryToApi = value
        syncLibraryToApi = value
    }

    fun updateImportDestination(value: String) {
        pairing.importDestination = value
        importDestination = value
    }

    init {
        PairLog.i("Phone start api=${pairing.apiBaseUrl} ips=${localIpv4Addresses()} paired=$paired")
        refresh()
        restoreHouseholdName()
        refreshPendingApprovals()
        refreshPhoneIps()
        viewModelScope.launch { ads = catalog().ads() }
        refreshStorage()
        resumePendingWebImports()
        resumeWebTransfers()
        if (paired) {
            // Also watches for TV private-playback approvals while paired (T075 / R-5).
            LanLibraryService.start(application)
            viewModelScope.launch {
                pairing.tvs.forEach { publishLanAddress(it.pin) }
                syncLibrary()
            }
        }
        if (!pairing.accountToken.isNullOrBlank()) viewModelScope.launch { syncHouseholdTvs() }
        loadTelegramLink()
        processTelegramDeletions()
        // Follow pause/stop pressed on the notification remote; adopt a cast
        // started before this screen was recreated.
        viewModelScope.launch {
            CastSession.state.collect { cast ->
                when {
                    cast == null -> {
                        nowPlayingState = null
                        pausedState = false
                    }
                    cast.videoId == nowPlayingState?.id -> pausedState = cast.paused
                    nowPlayingState == null -> items.firstOrNull { it.id == cast.videoId }?.let {
                        nowPlayingState = it
                        pausedState = cast.paused
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (!needsAccount && pairing.accountToken.isNullOrBlank()) {
                    // The session could not be renewed (see AccountAuthenticator).
                    withContext(Dispatchers.Main) {
                        needsAccount = true
                        status = "Your session expired. Sign in again to keep this TV connected."
                    }
                }
                if (paired) refreshPendingApprovals()
                if (System.currentTimeMillis() - householdTvsCheckedAt > HOUSEHOLD_TVS_REFRESH_MS &&
                    !pairing.accountToken.isNullOrBlank()) {
                    householdTvsCheckedAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main) { runCatching { syncHouseholdTvs() } }
                }
                delay(PENDING_APPROVAL_POLL_MS)
                val saving = runCatching { store.list().any { it.transferStatus == TRANSFER_SAVING } }.getOrDefault(false)
                // One more refresh when a save has just ended: its last state (saved, or failed) is written by the
                // service, and without this the screen stays on the last progress it drew.
                if (saving || wasSaving) withContext(Dispatchers.Main) { runCatching { refresh() } }
                wasSaving = saving
                delay(800)
            }
        }
    }

    private fun restoreHouseholdName() {
        val token = pairing.accountToken
        if (token.isNullOrBlank() || pairing.displayName.isNotBlank()) return
        viewModelScope.launch {
            // Offline (null): keep the library open rather than block on naming.
            val name = catalog().householdName(token) ?: return@launch
            if (name.isBlank()) {
                needsName = true
            } else {
                pairing.displayName = name
                householdName = name
            }
        }
    }

    private var wasSaving = false

    private companion object {
        // T075: in-app approval card refresh cadence (needs to be snappy;
        // the TV giver timeout is ~2 minutes).
        private const val PENDING_APPROVAL_POLL_MS = 5_000L
        private const val HOUSEHOLD_TVS_REFRESH_MS = 60_000L
    }

    fun refresh() {
        items = store.list()
        collections = store.collections()
        folders = store.folders()
        phoneStorage = store.phoneStorage()
        refreshPhoneIps()
        selectedId?.let { id -> if (items.none { it.id == id }) selectedId = null }
    }

    /** T075: refresh pending private-playback approvals for the in-app card. */
    fun refreshPendingApprovals() {
        val token = pairing.accountToken ?: run {
            pendingApprovals = emptyList()
            return
        }
        viewModelScope.launch {
            pendingApprovals = runCatching { catalog().fetchPendingPrivateApprovals(token) }.getOrDefault(emptyList())
        }
    }

    /** T075: approve / deny a pending request from inside the phone app. */
    fun respondToApproval(approval: PendingApproval, approve: Boolean) {
        val token = pairing.accountToken ?: return
        val app = getApplication<Application>()
        PrivateApprovals.cancelNotification(app, approval.attemptId)
        pendingApprovals = pendingApprovals.filterNot { it.attemptId == approval.attemptId }
        viewModelScope.launch {
            runCatching {
                val accepted = if (approve) catalog().approvePrivatePlayback(token, approval.attemptId) != null
                else catalog().denyPrivatePlayback(token, approval.attemptId)
                check(accepted) { "The request was not accepted. Refresh and try again" }
            }.onSuccess {
                status = if (approve) "Playback allowed for this attempt." else "Playback denied."
            }.onFailure {
                status = "Couldn't ${if (approve) "allow" else "deny"} playback: ${it.message ?: "try again"}."
                refreshPendingApprovals()
            }
        }
    }

    fun refreshStorage() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { storage = catalog().storageStatus(token) }
    }

    /** Why the last "Connect your own storage" attempt failed; null while untried or after it worked. */
    var r2ConnectError by mutableStateOf<String?>(null)
        private set

    fun beginConnectR2() {
        r2ConnectError = null
        cloudSheet = CloudSheet.ConnectR2
        tab = PhoneTab.Storage
    }

    /** Sends the owner's R2 account ID, bucket and keys once; the server verifies them with a real write. */
    fun connectR2Storage(accountId: String, bucket: String, accessKeyId: String, secret: String) {
        val token = pairing.accountToken ?: run {
            r2ConnectError = "Pair a TV first so Cablegram can attach storage to this household."
            return
        }
        viewModelScope.launch {
            busy = true
            r2ConnectError = null
            try {
                val result = catalog().connectR2(token, r2AccountIdFrom(accountId) ?: accountId.trim().lowercase(), bucket.trim(), accessKeyId.trim(), secret.trim())
                if (result.error == null) {
                    status = "Connected to your R2 storage."
                    // Back to the Storage page, which now shows the bucket; the guide does not stay on top.
                    cloudSheet = CloudSheet.None
                    tab = PhoneTab.Storage
                    refreshStorage()
                } else {
                    r2ConnectError = r2ConnectMessage(result.error)
                }
            } finally {
                busy = false
            }
        }
    }

    /** Why the last "Sign in with Google" could not start; null while untried or after it worked. */
    var googleConnectError by mutableStateOf<String?>(null)
        private set

    /** Where a save goes, in the words on every save sheet: the connected provider, else Cablegram Cloud. */
    val saveDestinationName: String get() = saveDestination(storage)

    /** Asks the server for Google's sign-in URL and hands it to [open] (a Custom Tab); the link back lands in [applyStorageReturn]. */
    fun connectGoogle(open: (String) -> Unit) {
        val token = pairing.accountToken ?: run {
            googleConnectError = "Pair a TV first so Cablegram can attach storage to this household."
            return
        }
        viewModelScope.launch {
            busy = true
            googleConnectError = null
            try {
                val start = catalog().connectGoogle(token)
                val url = start.authorizeUrl
                if (url != null) {
                    status = "Finish signing in with Google, then come back."
                    open(url)
                } else {
                    googleConnectError = googleStartMessage(start.error ?: "failed")
                }
            } finally {
                busy = false
            }
        }
    }

    /** The Google sign-in ended and the browser sent the owner back with `cablegram://storage?provider=…&result=…`. */
    fun applyStorageReturn(uri: String) {
        val ret = parseStorageReturn(uri) ?: return
        status = storageReturnMessage(ret, storage)
        googleConnectError = null
        tab = PhoneTab.Storage
        cloudSheet = CloudSheet.Manage
        refreshStorage()
    }

    fun disconnectStorage() {
        val token = pairing.accountToken ?: return
        val provider = storage?.connection?.provider
        val named = storage
        viewModelScope.launch {
            busy = true
            try {
                if (catalog().disconnectStorage(token)) {
                    status = disconnectMessage(provider, named)
                    refreshStorage()
                } else {
                    status = "Could not disconnect storage."
                }
            } finally {
                busy = false
            }
        }
    }

    private fun refreshPhoneIps() {
        phoneIps = localIpv4Addresses()
        if (lanAddress == null) {
            localIpv4()?.let { lanAddress = "$it:${LanLibraryServer.PORT}" }
        }
    }

    fun registerAccount(email: String, password: String, accountName: String) =
        authenticateAccount(register = true, email, password, accountName)

    fun loginAccount(email: String, password: String) = authenticateAccount(register = false, email, password)

    private fun authenticateAccount(register: Boolean, email: String, password: String, accountName: String? = null) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            status = "Signing in…"
            try {
                val client = catalog()
                val result = if (register) {
                    client.registerAccount(email.trim(), password, accountName.orEmpty().trim())
                } else {
                    client.loginAccount(email.trim(), password)
                }
                if (result == null) {
                    // Every failure used to read "Check email, password, and API URL", including
                    // "email already registered" and a login lockout.
                    status = if (client.lastAuthFailureError == "disposable_email") {
                        "Temporary email addresses can't be used. Use an email address you keep."
                    } else when (client.lastAuthFailureCode) {
                        409 -> "An account with this email already exists. Sign in instead."
                        429 -> "Too many sign-in attempts. Wait 15 minutes and try again."
                        401 -> "That email and password don't match."
                        400 -> if (register) "Check the email and use a password of at least 8 characters."
                            else "Check the email and password."
                        null -> "Can't reach Cablegram. Check your connection or the API server in Settings."
                        else -> "Could not sign in right now. Try again in a moment."
                    }
                    return@launch
                }
                pairing.accountToken = result.token
                pairing.refreshToken = result.refreshToken
                // Sign-up mails a confirmation code; ask for it right away (it can be skipped).
                if (register) {
                    emailVerified = false
                    verifyingEmail = true
                } else {
                    refreshEmailStatus()
                }
                householdName = result.displayName.orEmpty()
                pairing.displayName = householdName
                resumePendingWebImports()
                resumeWebTransfers()
                needsAccount = false
                needsName = result.needsName
                // Another account's library is set aside (and restored if it signs in again), never
                // synced into this household.
                val setAside = result.householdId?.let { store.bindHousehold(it) } == true
                refresh()
                ensurePhoneDevice()
                // TVs already paired to this household (by another phone, or before a reinstall).
                syncHouseholdTvs()
                if (paired) {
                    // Signing in again after an expired session: the TVs stay
                    // paired, so reconnect instead of asking for a new PIN.
                    status = if (needsName) "Choose an account name to continue." else "Signed in again."
                    addingTv = false
                    pairing.tvs.forEach { publishLanAddress(it.pin) }
                    syncLibrary()
                } else {
                    status = if (needsName) "Choose an account name to continue." else "Signed in. Scan the TV PIN to pair."
                    addingTv = !needsName
                    // Bring back the household's titles even before a TV is paired.
                    syncLibrary()
                }
                if (setAside) status = "Signed in. The previous account's library is kept aside on this phone."
            } finally {
                busy = false
            }
        }
    }

    private suspend fun ensurePhoneDevice() {
        val token = pairing.accountToken ?: return
        val known = pairing.phoneDeviceId
        if (!known.isNullOrBlank()) {
            // The id may belong to another household (a different account was
            // signed in) or have been revoked; announce and sync then fail for
            // good. Re-register only when the server positively says so.
            if (catalog().phoneDeviceStatus(token, known) != PhoneDeviceStatus.Missing) return
            PairLog.w("Phone device ${known.takeLast(6)} unknown to the server; registering again")
            pairing.phoneDeviceId = null
        }
        val device = catalog().registerPhoneDevice(token, hardwareId()) ?: return
        pairing.phoneDeviceId = device.device_id
    }

    /** The code the owner just entered or scanned, waiting for "Is this your TV?" (spec 004 US8). */
    var pendingPair by mutableStateOf<Pair<String, String>?>(null)

    fun applyPairUri(uri: String) {
        if (busy) return
        val parsed = parsePairCode(uri) ?: run {
            PairLog.w("Phone PIN parse failed inputLen=${uri.length}")
            status = "Enter the 6-digit PIN shown on the TV, or tap Refresh code there."
            return
        }
        pendingPair = parsed
    }

    fun cancelPendingPair() {
        pendingPair = null
    }

    /** Pairs after the owner said whether this is their own TV or a temporary one. */
    fun confirmPair(trust: TvTrust) {
        val parsed = pendingPair ?: return
        pendingPair = null
        pairWithTrust(parsed, trust)
    }

    private fun pairWithTrust(parsed: Pair<String, String>, trust: TvTrust) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                val result = catalog().pairPhone(parsed.first, pairing.accountToken, trust)
                if (result == null) {
                    status = "That code didn't work. On the TV, tap Refresh code and scan the new QR — or type the new PIN."
                    return@launch
                }
                // result.token is the token this call was sent with; the store may
                // already hold a renewed one, so it is not written back here.
                pairing.addTv(parsed.first, result.tvName.ifBlank { parsed.second }, trust = trust)
                // Attach the per-device LAN capability (R-1). Older servers return
                // none; the PIN stays as a transition fallback in that case.
                result.lanCapability?.let { pairing.attachCapability(parsed.first, it) }
                result.tvDeviceId?.let { pairing.attachDeviceId(parsed.first, it) }
                tvs = pairing.tvs
                addingTv = false
                tvName = pairing.tvName
                householdName = result.displayName.orEmpty().ifBlank { pairing.displayName }
                pairing.displayName = householdName
                needsName = false
                paired = true
                PairLog.i("Phone paired ${PairLog.pinTail(parsed.first)} tvs=${pairing.tvs.size}")
                ensurePhoneDevice()
                LanLibraryService.start(getApplication())
                // T075 / R-5: pairing may happen mid-session; start (or refresh) the
                // private-playback approval watcher together with the LAN server.
                publishLanAddress(parsed.first)
                syncLibrary()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = "Could not connect. Check your connection and try the TV code again."
            } finally {
                busy = false
            }
        }
    }

    fun saveHouseholdName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val token = pairing.accountToken
            if (token.isNullOrBlank()) {
                status = "Sign in first, then name the household."
                return@launch
            }
            busy = true
            status = "Saving…"
            try {
                val saved = catalog().setDisplayName(token, trimmed)
                if (!saved) {
                    status = "Could not save that name. Check the API server in Settings."
                    return@launch
                }
                pairing.displayName = trimmed
                householdName = trimmed
                needsName = false
                status = null
                if (!paired) addingTv = true
            } finally {
                busy = false
            }
        }
    }

    /** "Sign out of Telegram" for one TV: the phone ends the session on Telegram's side, even if the TV is off. */
    fun signOutTvTelegram(tv: PairedTv) {
        val token = pairing.accountToken ?: return
        val id = tv.deviceId ?: return
        viewModelScope.launch {
            if (catalog().signOutTvTelegram(token, id)) {
                status = "Signing ${tv.name} out of Telegram…"
                LanLibraryService.start(getApplication())
            } else {
                status = "Couldn't reach Cablegram. Check your connection and try again."
            }
        }
    }

    /** Extends a temporary TV's end time by a day, or turns direct Telegram on or off for it. */
    fun updateTvTrust(tv: PairedTv, trust: TvTrust) {
        val token = pairing.accountToken ?: return
        val id = tv.deviceId ?: return
        viewModelScope.launch {
            if (catalog().setTvTrust(token, id, trust)) syncHouseholdTvs()
            else status = "Couldn't update ${tv.name}. Direct Telegram needs a link older than 24 hours."
        }
    }

    fun startAddTv() {
        addingTv = true
        status = null
    }

    fun cancelAddTv() {
        addingTv = false
        status = null
    }

    private var householdTvsCheckedAt = 0L

    /**
     * The control plane is the source of truth for the household's TVs: TVs paired by another phone
     * or before a reinstall appear here, revoked ones disappear. Offline keeps the local list.
     */
    private suspend fun syncHouseholdTvs() {
        val token = pairing.accountToken ?: return
        val server = catalog().householdTvs(token) ?: return
        householdTvsCheckedAt = System.currentTimeMillis()
        val activeIds = server.map { it.id }.toSet()
        val local = pairing.tvs
        val kept = local.filter { it.deviceId == null || it.deviceId in activeIds }
        val known = kept.mapNotNull { it.deviceId }.toSet()
        val adopted = server.filter { it.id !in known }.map { tv ->
            // The "pin" of an adopted TV is a random value no TV knows, so it can never act as a LAN
            // credential; the TV's capability is verified through the control plane instead.
            PairedTv(
                pin = "household-${java.util.UUID.randomUUID()}",
                name = tv.displayName?.takeIf { it.isNotBlank() } ?: "TV",
                deviceId = tv.id,
                trust = tv.toTrust(),
            )
        }
        // Trust follows the server: the end time may have been extended from another phone.
        val trustById = server.associate { it.id to it.toTrust() }
        val refreshed = kept.map { tv -> tv.deviceId?.let(trustById::get)?.let { tv.copy(trust = it) } ?: tv }
        val merged = adopted + refreshed // the selected (last) TV stays selected
        if (merged == local) return
        val wasPaired = paired
        pairing.tvs = merged
        tvs = merged
        tvName = pairing.tvName
        paired = merged.isNotEmpty()
        PairLog.i("Household TVs synced adopted=${adopted.size} dropped=${local.size - kept.size}")
        if (paired && !wasPaired) {
            LanLibraryService.start(getApplication())
            merged.lastOrNull()?.let { publishLanAddress(it.pin) }
        } else if (!paired && wasPaired) {
            LanLibraryService.stop(getApplication())
            nowPlaying = null
        }
    }

    /** Everything a signed-in phone holds in memory and in its stores, back to a fresh install's sign-in screen. */
    private fun clearSignedInState() {
        LanLibraryService.stop(getApplication())
        pairing.accountToken = null
        pairing.refreshToken = null
        pairing.phoneDeviceId = null
        pairing.clearTVs()
        pairing.displayName = ""
        tvs = emptyList()
        paired = false
        nowPlaying = null
        paused = false
        lanAddress = null
        connectionDetail = null
        householdName = ""
        profiles = emptyList()
        selectedId = null
        tab = PhoneTab.Library
        addingTv = false
        needsName = false
        needsAccount = true
    }

    fun signOut() {
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val client = catalog()
                val token = pairing.accountToken
                val phoneId = pairing.phoneDeviceId
                // End the session and unregister this phone on the server; best effort offline.
                if (!token.isNullOrBlank() && !phoneId.isNullOrBlank()) runCatching { client.revokeDevice(phoneId, token) }
                pairing.refreshToken?.let { runCatching { client.logout(it) } }
            } finally {
                clearSignedInState()
                busy = false
                status = "Signed out. Your videos stay on this phone."
                PairLog.i("Phone signed out")
            }
        }
    }

    /**
     * ANDROID_ID: stable across reinstalls (per signing key and user), reset by a factory reset. The
     * server stores only a keyed hash, used to keep the free relay allowance per device (spec 003).
     */
    private fun hardwareId(): String? = runCatching {
        android.provider.Settings.Secure.getString(getApplication<Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    var relayUsage by mutableStateOf<RelayUsage?>(null)
        private set

    // ---- Delete account ----

    /** The "Delete account" screens are open. */
    var deletingAccount by mutableStateOf(false)
        private set

    val accountDeletion = AccountDeletionController(
        token = { pairing.accountToken },
        delete = { token, password -> catalog().deleteAccount(token, password) },
        wipeLocal = ::wipeAfterAccountDeletion,
    )

    fun openDeleteAccount() {
        accountDeletion.reset()
        deletingAccount = true
    }

    fun closeDeleteAccount() {
        if (accountDeletion.running) return
        accountDeletion.reset()
        deletingAccount = false
    }

    fun deleteAccount(password: String) {
        viewModelScope.launch { accountDeletion.submit(password) }
    }

    /** The server deleted the account: sign out completely and forget everything tied to it on this phone. */
    private suspend fun wipeAfterAccountDeletion() {
        val app = getApplication<Application>()
        withContext(Dispatchers.IO) {
            runCatching { app.stopService(Intent(app, CloudTransferService::class.java)) }
            // Same local path as "Disconnect Telegram", minus the server call: the link died with the account.
            if (PhoneTelegram.existing() == null && PhoneTelegram.wasLinked(app)) PhoneTelegram.session(app)
            wipeTelegramLocally()
            TelegramDeletionQueue.forContext(app).let { queue -> queue.all().forEach { queue.remove(it.stableSourceKey) } }
            CommandQueue(app).drain()
            store.forgetHousehold()
        }
        clearSignedInState()
        items = store.list()
        collections = store.collections()
        pendingApprovals = emptyList()
        relayUsage = null
        emailVerified = null
        verifyingEmail = false
        resettingPassword = false
        deletingAccount = false
        busy = false
        status = app.getString(R.string.account_deleted_message)
        PairLog.i("Account deleted; phone signed out")
    }

    // ---- Email confirmation and password reset ----
    /** Shows the "enter the code we emailed" screen (after sign-up, or from Settings). */
    var verifyingEmail by mutableStateOf(false)
        private set
    var emailVerified by mutableStateOf<Boolean?>(null)
        private set
    var resettingPassword by mutableStateOf(false)
        private set
    /** Email the reset code was sent to; null until requested. */
    var resetEmail by mutableStateOf<String?>(null)
        private set

    fun refreshEmailStatus() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { catalog().emailVerified(token)?.let { emailVerified = it } }
    }

    /**
     * Opens the code screen without mailing a new code: a fresh code would invalidate one the user
     * already has in their inbox. "Send a new code" on that screen asks for another.
     */
    fun startEmailVerification() {
        verifyingEmail = true
        status = "Enter the code from your sign-up email, or tap Send a new code."
    }

    fun sendEmailCode() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            val (code, body) = catalog().sendEmailCode(token)
            status = when (code) {
                202 -> "We sent a new code to your email."
                409 -> { emailVerified = true; verifyingEmail = false; "Your email is already confirmed." }
                429 -> "Wait ${body?.get("retry_after")?.jsonPrimitive?.intOrNull ?: 60} seconds before asking for another code."
                null -> "Can't reach Cablegram. Check your connection."
                else -> "Couldn't send a code right now. Try again in a moment."
            }
        }
    }

    fun submitEmailCode(code: String) {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            busy = true
            val (status, body) = catalog().verifyEmail(token, code.trim())
            busy = false
            if (status == 200) {
                emailVerified = true
                verifyingEmail = false
                this@PhoneViewModel.status = "Email confirmed. Free relay is on for this account."
                loadRelayUsage()
            } else {
                this@PhoneViewModel.status = when (body?.get("error")?.jsonPrimitive?.contentOrNull) {
                    "code_expired" -> "That code expired or was tried too often. Tap Send a new code."
                    "invalid_code" -> "That code isn't right. Check the email and try again."
                    else -> if (status == null) "Can't reach Cablegram. Check your connection." else "Couldn't check the code. Try again."
                }
            }
        }
    }

    fun skipEmailVerification() {
        verifyingEmail = false
        status = "You can confirm your email later in Settings. Free relay needs a confirmed email."
    }

    fun startPasswordReset() {
        resettingPassword = true
        resetEmail = null
        status = null
    }

    fun cancelPasswordReset() {
        resettingPassword = false
        resetEmail = null
        status = null
    }

    fun requestPasswordReset(email: String) {
        viewModelScope.launch {
            busy = true
            val (code, _) = catalog().forgotPassword(email.trim())
            busy = false
            if (code == null) {
                status = "Can't reach Cablegram. Check your connection."
            } else {
                resetEmail = email.trim()
                status = "If there's an account for ${email.trim()}, we sent it a code."
            }
        }
    }

    fun completePasswordReset(code: String, password: String) {
        val email = resetEmail ?: return
        viewModelScope.launch {
            busy = true
            val (status, body) = catalog().resetPassword(email, code.trim(), password)
            busy = false
            if (status == 204) {
                resettingPassword = false
                resetEmail = null
                this@PhoneViewModel.status = "Password changed. Signing you in…"
                loginAccount(email, password)
            } else {
                this@PhoneViewModel.status = when (body?.get("error")?.jsonPrimitive?.contentOrNull) {
                    "code_expired" -> "That code expired. Ask for a new one."
                    "invalid_code" -> "That code isn't right. Check the email and try again."
                    else -> if (status == null) "Can't reach Cablegram. Check your connection." else "Use a password with at least 8 characters."
                }
            }
        }
    }

    // ---- Telegram as cloud storage (spec 004 US1) ----

    /** The household's Telegram link as the control plane knows it (null until loaded). */
    var telegramLink by mutableStateOf<TelegramLinkInfo?>(null)
        private set
    /** The connect sheet on the Import tab is open. */
    var telegramSheetOpen by mutableStateOf(false)
    /** Shown in the sheet when linking fails after Telegram signed in (e.g. another account is linked). */
    var telegramMessage by mutableStateOf<String?>(null)
        private set
    var telegramFinishing by mutableStateOf(false)
        private set
    /** Whether the link works from this phone; see [checkTelegramHealth]. */
    var telegramHealth by mutableStateOf(TelegramHealth.Unknown)
        private set
    /** Whether linking created "Cablegram library" (true) or found an existing one (false); null before. */
    var telegramChannelCreated by mutableStateOf<Boolean?>(null)
        private set
    /** Continue was chosen on "Before you connect" in this app run (or Telegram was already running). */
    var telegramStarted by mutableStateOf(false)
        private set
    private var telegramFinishJob: kotlinx.coroutines.Job? = null

    fun loadTelegramLink() {
        if (!PhoneTelegram.configured) return
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            telegramLink = catalog().telegramLink(token) ?: telegramLink
            checkTelegramHealth()
        }
    }

    /**
     * Is the link still usable from this phone? The owner may have left the channel, deleted it, or
     * ended Cablegram's session in Telegram → Devices. Rechecked each time Import is opened.
     */
    fun checkTelegramHealth() {
        if (!PhoneTelegram.configured || telegramLink?.linked != true) return
        if (!PhoneTelegram.wasLinked(getApplication())) {
            // Linked from another phone: this one has no session to check with.
            telegramHealth = TelegramHealth.Ok
            return
        }
        viewModelScope.launch {
            // Keep the session warm: it also approves TV logins (T005).
            val session = PhoneTelegram.session(getApplication())
            val settled = kotlinx.coroutines.withTimeoutOrNull(30_000) {
                session.state.first {
                    it is app.cablegram.telegram.TelegramState.Ready || it is app.cablegram.telegram.TelegramState.NeedsPhoneNumber ||
                        it is app.cablegram.telegram.TelegramState.SignedOut || it is app.cablegram.telegram.TelegramState.Failed
                }
            } ?: return@launch
            telegramHealth = when {
                settled !is app.cablegram.telegram.TelegramState.Ready -> TelegramHealth.SignedOut
                telegramLink?.chatId?.let { session.isLibraryAvailable(it) } != true -> TelegramHealth.ChannelLost
                else -> {
                    // A channel linked before the logo existed gets it if it has no photo.
                    session.openLibrary(telegramLink?.chatId, allowCreate = false, logoPath = PhoneTelegram.logoPath(getApplication()))
                    TelegramHealth.Ok
                }
            }
        }
    }

    /** The owner left or deleted the channel: make a new "Cablegram library" and point the household at it. */
    fun recreateTelegramChannel() {
        val token = pairing.accountToken ?: return
        val session = PhoneTelegram.existing() ?: return
        viewModelScope.launch {
            val ready = session.state.value as? app.cablegram.telegram.TelegramState.Ready ?: run {
                telegramHealth = TelegramHealth.SignedOut
                return@launch
            }
            try {
                val library = session.openLibrary(null, allowCreate = true, logoPath = PhoneTelegram.logoPath(getApplication()))
                    ?: error("Telegram didn't create the channel")
                val result = catalog().putTelegramLink(token, ready.user.id, ready.user.displayName, library.chatId, pairing.phoneDeviceId)
                result.link?.let { telegramLink = it }
                telegramChannelCreated = library.created
                telegramHealth = TelegramHealth.Ok
                status = if (library.created) "Created a new \"Cablegram library\" channel in Telegram."
                else "Found your \"Cablegram library\" channel in Telegram again."
            } catch (e: Exception) {
                status = "Couldn't create the channel: ${e.message ?: "unknown error"}"
            }
        }
    }

    /** Telegram ended this phone's session: sign in again without disconnecting the household. */
    fun signInToTelegramAgain() {
        telegramHealth = TelegramHealth.Unknown
        // The old session is dead; start clean, then finish like a first link (same account → same link).
        PhoneTelegram.forget(getApplication())
        connectTelegram()
    }

    /** Opens the sheet on "Before you connect"; nothing reaches Telegram until [connectTelegram] (FR-021). */
    fun openTelegramSheet() {
        if (!PhoneTelegram.configured) return
        telegramMessage = null
        telegramSheetOpen = true
    }

    /** The owner chose Continue on "Before you connect": start TDLib and the sign-in steps. */
    fun connectTelegram() {
        if (!PhoneTelegram.configured) return
        telegramMessage = null
        telegramSheetOpen = true
        telegramStarted = true
        val session = PhoneTelegram.session(getApplication())
        telegramFinishJob?.cancel()
        telegramFinishJob = viewModelScope.launch {
            // Once per attempt: the link PUT is idempotent for the same account and 409 for another.
            val ready = session.state.first { it is app.cablegram.telegram.TelegramState.Ready } as app.cablegram.telegram.TelegramState.Ready
            finishTelegramLink(session, ready.user)
        }
    }

    /** True while this sign-in waits for a Home TV to approve it (FR-023). */
    var telegramViaTv by mutableStateOf(false)

    /** A Home TV that can approve this phone's Telegram login: paired, known to the server, and not temporary. */
    fun homeTvForTelegram(): PairedTv? = pairing.tvs.lastOrNull { it.deviceId != null && !it.trust.temporary }

    /**
     * "Use the QR code on your TV instead" (FR-023): the phone asks Telegram for a QR login and hands its link to a
     * Home TV that is already signed in, which approves it. Nothing but the login link goes through Cablegram, and
     * only to a TV the owner controls.
     */
    fun connectTelegramViaTv() {
        val tv = homeTvForTelegram() ?: return
        val tvId = tv.deviceId ?: return
        val token = pairing.accountToken ?: return
        if (!PhoneTelegram.configured) return
        val phoneId = pairing.phoneDeviceId ?: run {
            telegramSheetOpen = true
            telegramMessage = "This phone isn't registered with your household yet. Connect with your phone number instead."
            return
        }
        telegramMessage = null
        telegramSheetOpen = true
        telegramStarted = true
        telegramViaTv = true
        val session = PhoneTelegram.session(getApplication())
        telegramFinishJob?.cancel()
        telegramFinishJob = viewModelScope.launch {
            // Review fix: never wait forever. A session already asking for a code, a password or failing
            // can't switch to a QR login; the sheet shows that step instead.
            val started = kotlinx.coroutines.withTimeoutOrNull(20_000) {
                session.state.first { it !is app.cablegram.telegram.TelegramState.Starting }
            }
            when (started) {
                is app.cablegram.telegram.TelegramState.NeedsPhoneNumber -> session.startQrLogin()
                is app.cablegram.telegram.TelegramState.WaitingForApproval, is app.cablegram.telegram.TelegramState.Ready -> Unit
                else -> {
                    telegramViaTv = false
                    if (started == null) telegramMessage = "Telegram didn't start. Check your connection and try again."
                    return@launch
                }
            }
            // TDLib replaces the login token about every 30 s; each new link goes to the TV.
            val poster = launch {
                var last: String? = null
                session.state.collect { s ->
                    if (s is app.cablegram.telegram.TelegramState.WaitingForApproval && s.link != last) {
                        last = s.link
                        catalog().postPhoneLogin(token, s.link, tvId, phoneId)
                    }
                }
            }
            val progressed = kotlinx.coroutines.withTimeoutOrNull(PHONE_LOGIN_WAIT_MS) {
                session.state.first {
                    it is app.cablegram.telegram.TelegramState.Ready || it is app.cablegram.telegram.TelegramState.NeedsPassword ||
                        it is app.cablegram.telegram.TelegramState.Failed
                }
            }
            if (progressed == null) {
                poster.cancel()
                telegramViaTv = false
                telegramMessage = "${tv.name} didn't answer. Open Cablegram on the TV and check Settings → Telegram shows it signed in, " +
                    "or connect with your phone number instead."
                return@launch
            }
            poster.cancel()
            telegramViaTv = false
            val ready = when (progressed) {
                is app.cablegram.telegram.TelegramState.Ready -> progressed
                // Two-step verification: the sheet shows the password step; wait for the owner, not forever.
                is app.cablegram.telegram.TelegramState.NeedsPassword -> kotlinx.coroutines.withTimeoutOrNull(10 * 60_000) {
                    session.state.first { it is app.cablegram.telegram.TelegramState.Ready }
                } as? app.cablegram.telegram.TelegramState.Ready
                else -> null // Failed: the sheet shows Telegram's message
            } ?: return@launch
            finishTelegramLink(session, ready.user)
        }
    }

    /** Telegram is signed in: open or create the library channel and record the link (no secrets). */
    private suspend fun finishTelegramLink(session: app.cablegram.telegram.TelegramSession, user: app.cablegram.telegram.TgUser) {
        val token = pairing.accountToken ?: return
        telegramFinishing = true
        try {
            val library = session.openLibrary(
                knownChatId = telegramLink?.chatId,
                allowCreate = true,
                logoPath = PhoneTelegram.logoPath(getApplication()),
            ) ?: error("Could not open the Cablegram library channel")
            telegramChannelCreated = library.created
            val result = catalog().putTelegramLink(token, user.id, user.displayName, library.chatId, pairing.phoneDeviceId)
            if (result.conflictName != null) {
                telegramMessage = "This household already uses the Telegram account of ${result.conflictName}. " +
                    "Disconnect it first, or sign in with that account."
                session.logOut()
                PhoneTelegram.forget(getApplication())
            } else {
                telegramLink = result.link
                telegramHealth = TelegramHealth.Ok
                PhoneTelegram.setLinked(getApplication(), true)
                status = "Telegram connected. Videos you share into \"Cablegram library\" appear on your TV."
            }
        } catch (e: Exception) {
            telegramMessage = "Signed in to Telegram, but setting up the library failed: ${e.message ?: "unknown error"}. Try again."
        } finally {
            telegramFinishing = false
        }
    }

    /** Closing half-way keeps the step: reopening continues where the user left off. */
    fun closeTelegramSheet() {
        telegramSheetOpen = false
        telegramMessage = null
    }

    /** Logs this phone out of Telegram and deletes the local TDLib database, files and key. */
    private fun wipeTelegramLocally() {
        PhoneTelegram.existing()?.logOut()
        PhoneTelegram.forget(getApplication())
        telegramLink = TelegramLinkInfo(linked = false)
        telegramHealth = TelegramHealth.Unknown
        telegramStarted = false
    }

    fun disconnectTelegram() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            if (!catalog().deleteTelegramLink(token)) {
                status = "Couldn't disconnect Telegram. Check your connection and try again."
                return@launch
            }
            // End every TV's Telegram session while this phone can still do it: signing the phone out first
            // would leave TVs that are off or unplugged signed in to the account (US7, FR-010).
            val unended = withContext(Dispatchers.IO) {
                runCatching { TelegramTvApprovalWatcher.endDueSessions(getApplication(), catalog(), token) }
                catalog().dueTvTelegramSessions(token)?.size ?: -1
            }
            wipeTelegramLocally()
            status = if (unended == 0) "Telegram disconnected. Your TVs are signed out of Telegram too."
            else "Telegram disconnected. Some TVs may still be signed in: open Telegram → Settings → Devices and end any \"Cablegram\" sessions you don't recognise."
        }
    }

    fun loadRelayUsage() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { catalog().relayUsage(token, pairing.phoneDeviceId)?.let { relayUsage = it } }
    }

    fun loadProfiles() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { catalog().profiles(token)?.let { profiles = it } }
    }

    fun deleteProfile(profile: HouseholdProfile) {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            busy = true
            val code = catalog().deleteProfile(token, profile.id)
            busy = false
            status = when (code) {
                204 -> "Deleted ${profile.name}."
                409 -> "The account owner's profile can't be deleted."
                null -> "Couldn't reach Cablegram. Check your connection and try again."
                else -> "Couldn't delete ${profile.name} right now."
            }
            loadProfiles()
        }
    }

    fun unpair(tv: PairedTv? = null) {
        val current = tv?.takeIf { it in pairing.tvs } ?: pairing.tvs.lastOrNull() ?: return
        val deviceId = current.deviceId
        val token = pairing.accountToken
        if (deviceId.isNullOrBlank() || token.isNullOrBlank()) {
            // Legacy pairing without a device id: nothing to revoke server-side.
            forgetTv(current)
            return
        }
        viewModelScope.launch {
            busy = true
            // Revoke on the server first: forgetting the TV only locally left it signed in with full
            // library and LAN streaming access.
            val revoked = catalog().revokeDevice(deviceId, token)
            busy = false
            if (revoked) {
                forgetTv(current)
                // The LAN service stops with the last TV, so end this TV's Telegram session right away.
                if (PhoneTelegram.wasLinked(getApplication())) {
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { TelegramTvApprovalWatcher.endDueSessions(getApplication(), catalog(), token) }
                    }
                }
            } else status = "Couldn't reach Cablegram to remove ${current.name}. Check your connection and try again."
        }
    }

    private fun forgetTv(tv: PairedTv) {
        // removeTv expects the pairing PIN, not the subsequently issued capability.
        pairing.removeTv(tv.pin)
        tvs = pairing.tvs
        if (pairing.tvs.isEmpty()) {
            paired = false
            lanAddress = null
            connectionDetail = null
            nowPlaying = null
            paused = false
            LanLibraryService.stop(getApplication())
            status = "TV removed. Scan a PIN when you want one again."
        } else {
            tvName = pairing.tvName
            status = "Removed this TV. ${pairing.tvs.size} still paired."
        }
        PairLog.i("Phone unpaired one TV remaining=${pairing.tvs.size}")
    }

    private suspend fun enrichImported(item: LibraryItem, query: String) {
        val metadata = pairing.accountToken?.let { catalog().enrich(query, it, item.id) }
            ?: catalog().match(query)
            ?: return
        val poster = metadata.posterUrl?.let { url ->
            catalog().downloadBytes(url)?.let { store.writeCatalogPoster(item.id, it) }
        }
        store.applyCatalog(item.id, metadata, poster)
    }

    fun syncNow() {
        viewModelScope.launch {
            syncHouseholdTvs()
            syncLibrary(force = true)
        }
    }

    /**
     * Flow 5: fingerprint the sources first, then reconcile + push each item
     * separately with a checkpoint. A failure mid-sync keeps every already
     * synced item done — the next run resumes from the remaining ones.
     * Browsing/playback never blocks (everything runs on background dispatch).
     */
    private suspend fun syncLibrary(force: Boolean = false) {
        if (!force && !pairing.syncLibraryToApi) return
        val token = pairing.accountToken ?: return
        // Queue changes behind an active sync instead of silently dropping the
        // newest import or edit.
        librarySyncMutex.lock()
        runCatching { ensurePhoneDevice() }
        val client = catalog()
        librarySyncState = LibrarySyncState.Running
        try {
            withContext(Dispatchers.IO) {
                val before = store.list()
                // Attach fingerprints (cheap: local stat or pfd stat); reuse
                // previously computed values so a re-run never recomputes
                // unchanged items.
                val fingerprinted = before.map { item ->
                    if (item.fingerprint == null) item.copy(fingerprint = store.sourceFingerprint(item)) else item
                }
                fingerprinted.forEach { item ->
                    val prior = before.firstOrNull { b -> b.id == item.id }
                    if (item.fingerprint != null && prior?.fingerprint != item.fingerprint) {
                        store.update(item)
                    }
                }
                val phoneItems = fingerprinted.filter { it.sourceKind == "phone_local" }
                val present = phoneItems.filter { store.hasSource(it) }
                val presentIds = present.map { it.id }.toSet()
                // Loss of a file grant, disconnected storage, or an offline source
                // changes availability; it must never delete the user's catalog.
                phoneItems.forEach { item ->
                    val available = item.id in presentIds
                    if (item.sourceAvailable != available) store.update(item.copy(sourceAvailable = available))
                }
                check(client.reconcileSources(token, present, pairing.phoneDeviceId) { it.fingerprint }) {
                    "Could not sync source availability"
                }
                // Only reachable phone sources may be reattached by an import.
                // Unavailable titles stay cached and archived on the server.
                val items = present
                for ((index, item) in items.withIndex()) {
                    prepareProgress = (index + 1f) / items.size
                    prepareStep = "Syncing ${item.title}"
                    val ok = runCatching {
                        client.pushLibraryItem(token, item, pairing.phoneDeviceId) { _ ->
                            store.posterFile(item)?.readBytes()
                        }
                    }.getOrDefault(false)
                    if (!ok) {
                        error("Could not sync ${item.title}. Try again when connected")
                    }
                }
                prepareStep = null
                prepareProgress = 0f
                val remotes = client.fetchCatalog(token, failOnError = true)
                // Restore household titles this phone doesn't list (reinstall, new phone): re-link the
                // ones whose file is still here by fingerprint, keep the rest as "not on this phone".
                val deviceVideos by lazy { store.deviceVideosByFingerprint() }
                val dismissed = store.dismissedRemoteIds()
                val relinked = mutableListOf<LibraryItem>()
                // New Telegram titles the server could not match by itself: ask the owner once (T008).
                val alreadyAsked = store.promptedTelegramIds()
                val telegramAskIds = mutableListOf<String>()
                remotes.forEach { remote ->
                    if (remote.sources.none { it.kind == "telegram" && it.archiveState != "archived" }) return@forEach
                    val created = remote.createdAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                    if (needsTelegramMatchPrompt(remote.matchStatus, created, java.time.Instant.now(), remote.id in alreadyAsked)) {
                        val phoneCopy = store.get(remote.id)?.takeIf { it.sourceKind != "telegram" }
                        if (phoneCopy == null) telegramAskIds += remote.id
                    }
                }
                store.pruneTelegram(remotes.filter { r -> r.sources.any { it.kind == "telegram" && it.archiveState != "archived" } }.map { it.id }.toSet())
                remotes.forEach { remote ->
                    // A title that also has (or had) a file on this phone stays a phone title: it must not
                    // appear a second time as a Telegram title, nor after its phone copy was freed up.
                    val phoneCopy = store.get(remote.id)?.takeIf { it.sourceKind != "telegram" }
                        ?: remote.sources.firstNotNullOfOrNull { s ->
                            if (s.kind == "telegram" || s.kind == "own_cloud") null else s.originIdentity?.let(store::get)?.takeIf { it.sourceKind != "telegram" }
                        }
                    val livePhoneSource = remote.sources.any { it.kind == "phone_local" && it.archiveState != "archived" }
                    if (phoneCopy == null && !livePhoneSource) {
                        remote.sources.firstOrNull { it.kind == "telegram" && it.archiveState != "archived" }?.let { telegram ->
                            store.registerTelegram(remote, telegram)
                            return@forEach
                        }
                    }
                    val hasCloudObject = remote.sources.any { it.kind == "cloud_object" && it.archiveState != "archived" }
                    // A Telegram copy's identity (`tg:…`) is not a phone file id.
                    val source = remote.sources.firstOrNull { it.kind != "telegram" && it.kind != "own_cloud" && !it.originIdentity.isNullOrBlank() }
                    // A verified copy in the user's own R2 bucket. Dynamic: it clears when the bucket is disconnected.
                    val hasOwnCloudCopy = remote.sources.any { it.kind == "own_cloud" && it.archiveState != "archived" && it.availability != "unavailable" }
                    val hasTelegramCopy = remote.sources.any { it.kind == "telegram" && it.archiveState != "archived" && it.availability != "unavailable" }
                    val local = source?.originIdentity?.let(store::get)
                        ?: remote.sources.firstNotNullOfOrNull { remoteSource ->
                            remoteSource.phoneLocation?.let { location -> store.list().firstOrNull { it.sourceUri == location } }
                        }
                        ?: store.get(remote.id)
                    if (local != null && local.id != remote.id) {
                        // A re-imported file now represents this title; its placeholder can go.
                        store.dropPlaceholder(remote.id)
                    }
                    if (local != null && local.householdOnly) {
                        local.fingerprint?.let { deviceVideos[it] }?.let { video ->
                            store.relink(local.id, video)?.let { linked ->
                                relinked += linked.copy(fingerprint = store.sourceFingerprint(linked))
                            }
                        }
                    }
                    if (local != null) {
                        store.update((store.get(local.id) ?: local).copy(
                            title = if ("title" in local.userMetadataFields) local.title else remote.title?.takeIf { it.isNotBlank() } ?: local.title,
                            durationSeconds = remote.durationSeconds ?: local.durationSeconds,
                            // What groups a series' episodes under one poster comes from the household catalog.
                            tmdbId = remote.tmdbId ?: local.tmdbId,
                            seasonNumber = remote.seasonNumber ?: local.seasonNumber,
                            episodeNumber = remote.episodeNumber ?: local.episodeNumber,
                            mediaType = if ("mediaType" in local.userMetadataFields) local.mediaType else remote.mediaType ?: local.mediaType,
                            posterUrl = if (catalogMayReplaceArtwork(local)) remote.posterUrl ?: local.posterUrl else local.posterUrl,
                            cloudObjectPresent = hasCloudObject || local.cloudObjectPresent,
                            // Saved to Telegram (here or on another phone): the server knows the verified copy.
                            telegramCopy = hasTelegramCopy && local.sourceKind == "phone_local",
                            ownCloudCopy = hasOwnCloudCopy && local.sourceKind == "phone_local",
                            ownCloudSourceId = if (hasOwnCloudCopy && local.sourceKind == "phone_local") ownCloudSourceIdOf(remote.sources) else null,
                            storageState = if (hasCloudObject) STORAGE_CLOUD else local.storageState,
                            transferStatus = if (hasCloudObject) TRANSFER_IDLE else local.transferStatus,
                        ))
                    } else {
                        val web = remote.sources.firstOrNull { it.kind == "web" && !it.canonicalUrl.isNullOrBlank() }
                        if (web != null) {
                            val added = store.registerWeb(WebImportResponse(
                            id = remote.id,
                            title = remote.title ?: "Web video",
                            posterUrl = remote.posterUrl,
                            durationSeconds = remote.durationSeconds,
                            canonicalUrl = web.canonicalUrl!!,
                            ))
                            if (hasCloudObject) store.update(added.copy(cloudObjectPresent = true, storageState = STORAGE_CLOUD))
                        } else {
                            val phoneSource = remote.sources.firstOrNull { it.kind == "phone_local" && it.archiveState != "archived" }
                            if (phoneSource != null && remote.id !in dismissed) {
                                val restored = store.restoreFromHousehold(remote, phoneSource)
                                if (hasCloudObject) store.update(restored.copy(cloudObjectPresent = true))
                                phoneSource.sourceFingerprint?.let { deviceVideos[it] }?.let { video ->
                                    store.relink(restored.id, video)?.let { linked ->
                                        relinked += linked.copy(fingerprint = store.sourceFingerprint(linked))
                                    }
                                }
                            }
                        }
                    }
                }
                // The Telegram titles were registered above; queue the ones to ask about.
                telegramAskIds.mapNotNull(store::get).filter { it.sourceKind == "telegram" }.forEach { item ->
                    store.markTelegramPrompted(item.id)
                    pendingTitles.add(item)
                }
                // Restored titles carry only the catalog poster URL; the library draws local files.
                store.list().filter { it.posterPath == null && it.posterUrl?.startsWith("https://") == true }.forEach { item ->
                    client.downloadBytes(item.posterUrl!!)?.let { bytes ->
                        store.writeCatalogPoster(item.id, bytes)?.let { file ->
                            store.get(item.id)?.let { store.update(it.copy(posterPath = file.absolutePath)) }
                        }
                    }
                }
                // Re-linked files become this phone's sources right away, so the TV can play them.
                relinked.forEach { item ->
                    store.update(item)
                    runCatching {
                        client.pushLibraryItem(token, item, pairing.phoneDeviceId) { _ -> store.posterFile(item)?.readBytes() }
                    }
                }
            }
            refresh()
            // Never interrupt a dialog that is already open; the queued titles follow it.
            if (pendingTitleItem == null && pendingPrivacyItem == null && pendingTitles.isNotEmpty()) promptNextTitle()
            librarySyncState = LibrarySyncState.Completed
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            librarySyncState = LibrarySyncState.Idle
            throw cancelled
        } catch (error: Exception) {
            refresh()
            PairLog.e("Library sync failed", error)
            librarySyncState = LibrarySyncState.Failed
        } finally {
            prepareStep = null
            prepareProgress = 0f
            librarySyncMutex.unlock()
        }
    }

    private suspend fun publishLanAddress(pin: String) {
        ensurePhoneDevice()
        val host = BuildConfig.LAN_HOST_OVERRIDE.ifBlank { null } ?: localIpv4()
        if (host == null) {
            PairLog.w("Phone has no IPv4 after pair ${PairLog.pinTail(pin)}")
            status = "Paired, but this phone has no Wi‑Fi address yet."
            return
        }
        lanAddress = "$host:${LanLibraryServer.PORT}"
        val tunnel = pairing.tunnelUrl.takeIf { it.startsWith("https://") }
        val announced = catalog().announce(
            pin,
            host,
            LanLibraryServer.PORT,
            tunnel,
            pairing.phoneDeviceId,
            pairing.accountToken,
            hardwareId(),
        )
        PairLog.i("Phone announce ok=$announced ${PairLog.pinTail(pin)} $host:${LanLibraryServer.PORT} api=${pairing.apiBaseUrl}")
        connectionDetail = when {
            announced && tunnel != null -> "TV can use $tunnel if LAN does not match."
            announced -> "This phone is $lanAddress on the same Wi‑Fi as the TV."
            else -> "API announce failed. Check the API server in Settings."
        }
        status = null
    }

    fun importShared(uri: Uri, displayName: String, playNow: Boolean, caption: String? = null) {
        viewModelScope.launch {
            busy = true
            status = "Copying $displayName…"
            try {
                persistRead(uri)
                val item = withContext(Dispatchers.IO) { store.importUri(uri, displayName, null) }
                refresh()
                if (importDestination == STORAGE_BOTH && (cablegramCloudReady || cloudConnected)) {
                    beginSaveToCloud(item)
                }
                // A parseable filename is only a draft. Every new video gets
                // explicit title and artwork confirmation.
                showTitlePrompt(store.get(item.id) ?: item)
            } catch (error: Exception) {
                status = error.message ?: "Could not import that file"
            } finally {
                busy = false
            }
        }
    }

    fun importWeb(url: String, caption: String? = null) {
        val pending = store.queueWebImport(url, caption)
        submitPendingWebImport(pending, announce = true)
    }

    private fun resumePendingWebImports() {
        if (pairing.accountToken.isNullOrBlank()) return
        store.snapshot().pendingWebImports.forEach { submitPendingWebImport(it, announce = false) }
    }

    private fun submitPendingWebImport(pending: PendingWebImport, announce: Boolean) {
        viewModelScope.launch {
            val token = pairing.accountToken
            if (token.isNullOrBlank()) {
                if (announce) status = "Link saved. Sign in to finish adding it."
                return@launch
            }
            if (announce) {
                busy = true
                status = "Analyzing link…"
            }
            try {
                val response = catalog().importWeb(pending.url, pending.caption, token, pending.id)
                val item = withContext(Dispatchers.IO) { store.registerWeb(response) }
                withContext(Dispatchers.IO) { store.completeWebImport(pending.id) }
                refresh()
                selectedId = item.id
                status = "Added ${item.title} to your library"
            } catch (error: Exception) {
                PairLog.e("Web import failed", error)
                withContext(Dispatchers.IO) { store.failWebImport(pending.id, error.message ?: "web_import_failed") }
                status = when (error.message) {
                    "unsupported_site" -> "Cablegram cannot extract video from this site yet."
                    "no_video" -> "No playable video was found at that link."
                    "unsafe_url", "invalid_url" -> "That is not a supported public web link."
                    else -> error.message ?: "Could not add that link"
                }
            } finally {
                if (announce) busy = false
            }
        }
    }

    fun ensureBrowseRoot() {
        browseSource = BrowseSource.Roots
        browseTreeUri = null
        browseVolumeName = null
        browseCrumbs = emptyList()
        browseEntries = emptyList()
        selectedBrowseIds = emptySet()
        refreshStorage()
        viewModelScope.launch {
            browseLoading = true
            try {
                val volumes = withContext(Dispatchers.IO) { indexer.listStorageVolumes() }
                    .ifEmpty {
                        listOf(
                            StorageRoot(
                                id = "vol:primary",
                                name = "Internal storage",
                                kind = StorageRootKind.Internal,
                                detail = "This phone",
                                volumeName = if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY
                                } else {
                                    null
                                },
                            ),
                        )
                    }
                browseRoots = volumes + cloudStorageRoots()
            } finally {
                browseLoading = false
            }
        }
    }

    fun openStorageRoot(root: StorageRoot) {
        if (root.kind != StorageRootKind.Cloud) return
        browseSource = BrowseSource.Cloud
        browseCrumbs = listOf(BrowseCrumb(root.name))
        browseEntries = cloudFiles(store.list()).map { item ->
            BrowseEntry(
                id = "lib:${item.id}",
                name = item.title,
                isDirectory = false,
                isVideo = true,
                sizeBytes = item.fileSizeBytes,
                uri = item.sourceUri?.let(Uri::parse),
            )
        }
    }

    fun applyBrowseFilter(videosOnly: Boolean) {
        browseVideosOnly = videosOnly
        if (browseSource == BrowseSource.Phone || browseSource == BrowseSource.Folder) reloadBrowse()
    }

    fun openBrowseFolder(treeUri: Uri, folderName: String) {
        persistRead(treeUri)
        store.rememberFolder(treeUri.toString(), folderName)
        refresh()
        browseSource = BrowseSource.Folder
        browseTreeUri = treeUri
        browseCrumbs = listOf(BrowseCrumb(folderName, documentId = DocumentsContract.getTreeDocumentId(treeUri)))
        reloadBrowse()
    }

    fun openBrowsePhone() {
        ensureBrowseRoot()
    }

    fun openBrowseEntry(entry: BrowseEntry) {
        if (entry.id.startsWith("lib:")) {
            store.get(entry.id.removePrefix("lib:"))?.let(::openItem)
            return
        }
        if (entry.isDirectory) {
            selectedBrowseIds = emptySet()
            val tree = entry.treeUri ?: browseTreeUri
            if (tree != null && entry.documentId != null) {
                browseSource = BrowseSource.Folder
                browseTreeUri = tree
                browseCrumbs = browseCrumbs + BrowseCrumb(entry.name, documentId = entry.documentId)
                reloadBrowse()
                return
            }
            browseSource = BrowseSource.Phone
            browseCrumbs = browseCrumbs + BrowseCrumb(entry.name, mediaPath = entry.mediaPath)
            reloadBrowse()
            return
        }
        toggleBrowseFile(entry)
    }

    fun toggleBrowseFile(entry: BrowseEntry) {
        if (entry.isDirectory) return
        selectedBrowseIds = if (entry.id in selectedBrowseIds) {
            selectedBrowseIds - entry.id
        } else {
            selectedBrowseIds + entry.id
        }
    }

    fun selectAllBrowseFiles() {
        val ids = visibleBrowseEntries(browseEntries, browseVideosOnly).filter { !it.isDirectory }.map { it.id }.toSet()
        selectedBrowseIds = if (selectedBrowseIds.containsAll(ids) && ids.isNotEmpty()) emptySet() else ids
    }

    fun addPickedFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    persistRead(uri)
                    val name = indexer.displayName(uri)
                    BrowseEntry(
                        id = uri.toString(),
                        name = name,
                        isDirectory = false,
                        isVideo = MediaIndexer.isVideoFile(name, ""),
                        sizeBytes = indexer.sizeBytes(uri),
                        uri = uri,
                    )
                }
            }
            addBrowseFileEntries(files.filter { it.isVideo })
        }
    }

    fun addSelectedBrowseFiles() {
        val files = visibleBrowseEntries(browseEntries, browseVideosOnly)
            .filter { it.id in selectedBrowseIds && !it.isDirectory && it.uri != null }
        if (files.isEmpty()) return
        selectedBrowseIds = emptySet()
        viewModelScope.launch { addBrowseFileEntries(files) }
    }

    private suspend fun addBrowseFileEntries(files: List<BrowseEntry>) {
        if (files.isEmpty()) {
            status = "Pick video files to add"
            return
        }
        var added = 0
        val imported = mutableListOf<LibraryItem>()
        files.forEachIndexed { index, entry ->
            prepareProgress = (index + 1f) / files.size
            prepareStep = "Adding ${index + 1} of ${files.size} files"
            try {
                val created = withContext(Dispatchers.IO) {
                    store.indexExternal(entry.uri!!, entry.name, entry.sizeBytes, null)
                }
                refresh()
                if (created == null) return@forEachIndexed
                added++
                imported += created
            } catch (error: Exception) {
                PairLog.e("Add file failed ${entry.name}", error)
            }
        }
        prepareStep = null
        prepareProgress = 0f
        status = if (added == 0) "Those files are already in your library" else "Added $added files"
        pendingPrivacyItems.addAll(imported)
        showNextPrivacyPrompt()
    }

    private fun promptNextTitle() {
        val next = pendingTitles.removeFirstOrNull() ?: return
        showTitlePrompt(next)
    }

    private fun showTitlePrompt(item: LibraryItem) {
        pendingTitleItem = item
        titleDraft = item.title
        titleSuggestions = localTitleSuggestions("")
        previewFrames = emptyList()
        selectedPreviewFrame = null
        identifyingStills = false
        pendingAiMatch = null
        askingSeriesDetails = false
        seasonDraft = ""
        episodeDraft = ""
        status = "Name this video"
        viewModelScope.launch {
            val frames = withContext(Dispatchers.IO) { store.extractPreviewFrames(item, count = 4) }
            if (pendingTitleItem?.id != item.id) return@launch
            previewFrames = frames
            // A still is only a draft choice. Do not mutate artwork merely by
            // opening the editor: Cancel must leave the existing poster intact.
            val pick = frames.getOrNull(1) ?: frames.firstOrNull() ?: return@launch
            selectedPreviewFrame = pick
        }
    }

    private fun showNextPrivacyPrompt() {
        pendingPrivacyItem = pendingPrivacyItems.removeFirstOrNull()
        if (pendingPrivacyItem != null) status = "Should this require phone approval?"
    }

    fun choosePrivacy(isPrivate: Boolean) {
        val item = pendingPrivacyItem ?: return
        pendingPrivacyItem = null
        val updated = item.copy(isPrivate = isPrivate)
        store.update(updated)
        refresh()
        if (importDestination == STORAGE_BOTH && (cablegramCloudReady || cloudConnected)) {
            beginSaveToCloud(updated)
        }
        // Long release/episode filenames may be parseable but are rarely good
        // display titles, so filename quality never bypasses this dialog.
        viewModelScope.launch { syncLibrary() }
        showTitlePrompt(updated)
    }

    fun choosePreviewFrame(path: String) {
        selectedPreviewFrame = path
    }

    fun resolveTitleFromText() {
        val query = titleDraft.trim()
        if (query.isEmpty() || identifyingStills) return
        identifyingStills = true
        status = "Looking up “$query”…"
        viewModelScope.launch {
            try {
                val api = pairing.apiBaseUrl.trim().trimEnd('/')
                val unreachable = catalog().pingHealth()
                if (unreachable != null) {
                    status = "Can't reach $api ($unreachable). Set API server in Settings."
                    return@launch
                }
                val result = catalog().resolveTitle(query, pairing.accountToken)
                val match = result.metadata
                if (!result.found || match == null || match.title.isBlank() || match.title.equals("Unknown", ignoreCase = true)) {
                    status = "Couldn't find a confident match. Continue with your title and the selected still."
                    pendingAiMatch = null
                    return@launch
                }
                pendingAiMatch = match
                askingSeriesDetails = false
                status = null
            } catch (error: Exception) {
                PairLog.e("Title resolve failed", error)
                status = "Couldn't look that up (${error.message ?: "error"})"
                pendingAiMatch = null
            } finally {
                identifyingStills = false
            }
        }
    }

    fun dismissAiMatch() {
        pendingAiMatch = null
        askingSeriesDetails = false
        seasonDraft = ""
        episodeDraft = ""
        seasonEpisodeSuggestLabel = null
        status = "Continue with your title, or try another search."
    }

    fun acceptAiMatch() {
        val match = pendingAiMatch ?: return
        if (match.mediaType.equals("tv", ignoreCase = true)) {
            // Flow 3: a picked local suggestion knows the highest numbered
            // episode — propose its successor instead of the TMDB parse.
            val picked = pendingSuggestion?.takeIf { it.title.equals(match.title, ignoreCase = true) }
            // The lookup reports 0/0 when the file name carries no S/E; treat that as unknown rather
            // than pre-filling "Season 0" (specials). A parsed S01E01 must pre-fill both fields.
            val parsedEpisode = match.episodeNumber?.takeIf { it > 0 }
            val parsedSeason = match.seasonNumber?.takeIf { parsedEpisode != null && it >= 0 }
            allowSpecialsConfirmed = false
            seasonDraft = (picked?.maxSeason ?: parsedSeason)?.toString().orEmpty()
            episodeDraft = (picked?.maxEpisode?.plus(1) ?: parsedEpisode)?.toString().orEmpty()
            seasonEpisodeSuggestLabel = picked?.maxSeason?.let { maxSeason ->
                val maxEpisode = picked.maxEpisode ?: -1
                "Suggested: S%02dE%02d — based on the highest episode in this series.".format(maxSeason, maxEpisode + 1)
            } ?: seasonEpisodeSuggestLabel
            askingSeriesDetails = true
            return
        }
        applyAiMatch(match, season = null, episode = null)
    }

    fun confirmSeriesDetails() {
        val match = pendingAiMatch ?: return
        val season = seasonDraft.trim().toIntOrNull() ?: run {
            status = "Enter season and episode numbers."
            return
        }
        val episode = episodeDraft.trim().toIntOrNull() ?: run {
            status = "Enter season and episode numbers."
            return
        }
        // Flow 3: specials are allowed with an explicit season 0; regular
        // episodes must be >= 1. Duplicates require explicit override.
        if (season < 0 || episode < 1) {
            status = "Enter valid season/episode numbers (season 0 = specials)."
            return
        }
        if (season == 0 && !allowSpecialsConfirmed) {
            allowSpecialsConfirmed = true
            status = "Season 0 adds this as a special. Tap Fetch artwork again to confirm."
            return
        }
        // The user confirmed match.title; variants are only alternative spellings and can differ
        // per lookup ("Breaking-Bad"), which split episodes into separate series.
        val seriesTitle = match.title
        val existing = store.list().filter { it.title.equals(seriesTitle, ignoreCase = true) }
            .mapNotNull { extractSeasonEpisode(it.title) }
        val candidate = season to episode
        if (candidate in existing && !duplicateEpisodeConfirmed) {
            duplicateEpisodeConfirmed = true
            duplicatePrompt = seriesTitle to candidate
            status = null
            return
        }
        duplicateEpisodeConfirmed = false
        applyAiMatch(match, season = season, episode = episode)
    }

    /** T-UX04: user explicitly chose to add a duplicate copy of an existing episode. */
    fun confirmDuplicateEpisode() {
        val (seriesTitle, candidate) = duplicatePrompt ?: return
        duplicatePrompt = null
        val match = pendingAiMatch ?: return
        duplicateEpisodeConfirmed = true
        status = null
        applyAiMatch(match, season = candidate.first, episode = candidate.second)
    }

    fun cancelDuplicateEpisode() {
        duplicatePrompt = null
        duplicateEpisodeConfirmed = false
        status = "Pick a different season or episode."
    }

    private fun applyAiMatch(match: CatalogMetadata, season: Int?, episode: Int?) {
        val item = pendingTitleItem ?: return
        val selectedFrame = selectedPreviewFrame
        // Search what the user confirmed. Searching a variant ("TheMatrix") matched a junk TMDB
        // upload instead of The Matrix (1999).
        val search = match.title
        // Flow 3: TMDB fetch happens only after the user has confirmed season
        // and episode (TV) or accepted the movie identity — so the poster is
        // fetched for the exact episode, using the exact IMDb identity.
        val query = buildString {
            append(search)
            if (match.year != null) append(" ${match.year}")
            if (season != null && episode != null && season > 0) {
                append(" S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}")
            }
        }
        clearTitlePromptUi()
        viewModelScope.launch {
            busy = true
            status = "Fetching artwork for ${match.title}…"
            try {
            val metadata = pairing.accountToken?.let {
                catalog().enrich(
                    query = query,
                    token = it,
                    itemId = item.id,
                    mediaType = match.mediaType,
                    seasonNumber = season,
                    episodeNumber = episode,
                    year = match.year,
                    imdbId = match.imdbId,
                )
            }
            if (metadata != null && metadata.matchStatus == "matched") {
                // Flow 2: TMDb artwork has highest poster priority. Download and
                // overwrite the extracted still; posterVersion bump makes the UI reload.
                val poster = metadata.posterUrl?.let { url ->
                    catalog().downloadBytes(url)?.let { store.writeCatalogPoster(item.id, it) }
                }
                store.applyCatalog(item.id, metadata, poster)
                // A metadata match is not necessarily an artwork match. If TMDB
                // supplied no usable image, keep the user's selected video frame.
                if (poster == null) {
                    selectedFrame?.let { store.setPosterFromFrame(item.id, File(it)) }
                }
            } else {
                    editMetadataAndSync(item.id, match.title, match.year, match.mediaType)
                    // Keep the selected still only when catalog artwork was not
                    // confirmed. The selection is committed here, not on tap.
                    selectedFrame?.let { store.setPosterFromFrame(item.id, File(it)) }
                }
                refresh()
                syncLibrary()
                status = "Added ${store.get(item.id)?.title ?: match.title}"
                showNextPrivacyPrompt()
                promptNextTitle()
            } catch (error: Exception) {
                PairLog.e("Apply AI match failed", error)
                editMetadataAndSync(item.id, match.title, match.year, match.mediaType)
                // Network/provider failure must not discard the local fallback
                // that was already selected in the artwork dialog.
                selectedFrame?.let { withContext(Dispatchers.IO) { store.setPosterFromFrame(item.id, File(it)) } }
                refresh()
                syncLibrary()
                status = "Saved ${match.title} without catalog artwork"
                showNextPrivacyPrompt()
                promptNextTitle()
            } finally {
                busy = false
            }
        }
    }

    /** Saves a title edit on the phone; for a Telegram title (no phone file to push) the new title goes to the household too. */
    private fun editMetadataAndSync(id: String, title: String, year: Int?, mediaType: String) {
        store.editMetadata(id, title, year, mediaType)
        val item = store.get(id) ?: return
        val token = pairing.accountToken ?: return
        if (item.sourceKind == "telegram") viewModelScope.launch { catalog().patchTitle(token, id, item.title) }
    }

    private fun clearTitlePromptUi() {
        pendingTitleItem = null
        titleSuggestions = emptyList()
        previewFrames = emptyList()
        selectedPreviewFrame = null
        identifyingStills = false
        pendingAiMatch = null
        askingSeriesDetails = false
        seasonDraft = ""
        episodeDraft = ""
        pendingSuggestion = null
        seriesNotes = emptyList()
        seasonEpisodeSuggestLabel = null
        duplicatePrompt = null
        duplicateEpisodeConfirmed = false
    }

    fun browseUp() {
        selectedBrowseIds = emptySet()
        if (browseSource == BrowseSource.Cloud || browseCrumbs.size <= 1) {
            ensureBrowseRoot()
            return
        }
        browseCrumbs = browseCrumbs.dropLast(1)
        reloadBrowse()
    }

    private fun cloudStorageRoots(): List<StorageRoot> {
        val roots = mutableListOf<StorageRoot>()
        if (cablegramCloudReady) {
            roots += StorageRoot(
                id = "cloud:cablegram",
                name = "Cablegram Cloud",
                kind = StorageRootKind.Cloud,
                detail = "${formatBytes(cloudAvailable)} available",
                cloudKey = "cablegram",
            )
        }
        val connections = buildList {
            addAll(storage?.connections.orEmpty())
            storage?.connection?.let(::add)
        }.distinctBy { it.id ?: it.displayLabel ?: it.bucketName }
        connections.filter { it.status == "active" }.forEach { conn ->
            val key = conn.id ?: conn.bucketName ?: conn.displayLabel ?: return@forEach
            if (roots.any { it.cloudKey == key }) return@forEach
            roots += StorageRoot(
                id = "cloud:$key",
                name = conn.displayLabel ?: conn.bucketName ?: "Connected cloud",
                kind = StorageRootKind.Cloud,
                detail = conn.provider ?: "Connected",
                cloudKey = key,
            )
        }
        return roots
    }

    private fun reloadBrowse() {
        selectedBrowseIds = emptySet()
        viewModelScope.launch {
            browseLoading = true
            try {
                val crumb = browseCrumbs.lastOrNull()
                browseEntries = withContext(Dispatchers.IO) {
                    when (browseSource) {
                        BrowseSource.Folder -> {
                            val tree = browseTreeUri
                            val doc = crumb?.documentId
                            if (tree == null || doc == null) emptyList()
                            else indexer.listSafChildren(tree, doc)
                        }
                        BrowseSource.Phone -> {
                            indexer.listMediaStoreBrowse(
                                crumb?.mediaPath.orEmpty(),
                                browseVideosOnly,
                                browseVolumeName,
                            )
                        }
                        BrowseSource.Cloud, BrowseSource.Roots, BrowseSource.None -> emptyList()
                    }
                }
            } catch (error: Exception) {
                status = error.message ?: "Could not open that folder"
                browseEntries = emptyList()
            } finally {
                browseLoading = false
            }
        }
    }

    private suspend fun addFromBrowse(uri: Uri, displayName: String, sizeBytes: Long?) {
        persistRead(uri)
        val created = withContext(Dispatchers.IO) {
            store.indexExternal(uri, displayName, sizeBytes, null)
        }
        refresh()
        if (created == null) {
            status = "$displayName is already in your library"
            return
        }
        pendingPrivacyItems.addLast(created)
        showNextPrivacyPrompt()
    }

    fun openItem(item: LibraryItem) {
        selectedId = item.id
    }

    fun closeItem() {
        selectedId = null
        editingMetadata = false
        deleteTarget = null
    }

    fun updateTitleDraft(value: String) {
        titleDraft = value.take(120)
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            val local = localTitleSuggestions(titleDraft)
            titleSuggestions = local
            delay(250)
            if (titleDraft.trim().length < 2) return@launch
            val remote = catalog().suggestTitles(titleDraft, pairing.accountToken)
            val seen = local.map { it.title.lowercase() }.toMutableSet()
            titleSuggestions = local + remote.filter { seen.add(it.title.lowercase()) }
        }
    }

    fun confirmPendingTitle(playNow: Boolean = false) {
        val item = pendingTitleItem ?: return
        val query = titleDraft.trim()
        if (query.isEmpty()) return
        val selectedFrame = selectedPreviewFrame
        // Keep user text + selected still; do not force TMDb unless AI match was confirmed.
        clearTitlePromptUi()
        viewModelScope.launch {
            editMetadataAndSync(item.id, query, item.year, item.mediaType)
            selectedFrame?.let { withContext(Dispatchers.IO) { store.setPosterFromFrame(item.id, File(it)) } }
            refresh()
            syncLibrary()
            if (playNow) {
                playOnTv(store.get(item.id) ?: item)
            } else {
                status = "Added $query"
            }
            showNextPrivacyPrompt()
            promptNextTitle()
        }
    }

    fun skipPendingTitle() {
        clearTitlePromptUi()
        showNextPrivacyPrompt()
        promptNextTitle()
    }

    fun saveMetadata(title: String, year: Int?, mediaType: String) {
        val id = selectedId ?: return
        editMetadataAndSync(id, title, year, mediaType)
        editingMetadata = false
        refresh()
        viewModelScope.launch { syncLibrary() }
    }

    /** Opens the same title/artwork editor for every library item. */
    fun findDetailsAndArtwork(item: LibraryItem) {
        showTitlePrompt(item)
    }

    fun createCollection() {
        val name = collectionDraft.trim()
        if (name.isEmpty()) return
        store.addCollection(name)
        collectionDraft = ""
        refresh()
    }

    fun toggleCollection(item: LibraryItem, collectionId: String) {
        val next = if (collectionId in item.collectionIds) item.collectionIds - collectionId else item.collectionIds + collectionId
        store.setItemCollections(item.id, next)
        refresh()
    }

    fun deleteCollection(id: String) {
        store.deleteCollection(id)
        refresh()
    }

    private fun localTitleSuggestions(query: String): List<TitleSuggestion> {
        val needle = query.trim().lowercase()
        // Flow 3: local-library-first autocomplete enriched with the highest
        // known numbered episode per series, so the UI can propose the next S/E.
        val candidates = store.list().filter { !isWeakCatalogLabel(it.title) }
        val enriched = candidates.groupBy { it.title.lowercase() }.map { (_, group) ->
            val best = group.first()
            val numbered = group.mapNotNull { extractSeasonEpisode(it.title) }
            val maxSeason = numbered.maxOfOrNull { it.first }
            val maxEpisode = numbered.filter { it.first == maxSeason }.maxOfOrNull { it.second }
            TitleSuggestion(
                best.title,
                best.year,
                best.mediaType,
                "library",
                maxSeason,
                maxEpisode,
            )
        }
        return enriched
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) }
            .sortedBy { it.title.lowercase() }
            .take(8)
    }

    /**
     * Flow 3: user picked a series from local-library-first suggestions.
     * Proposes the next season/episode from the highest known numbered
     * episode; flags duplicates within the same series and S00 specials for
     * explicit confirmation (never silently overwrites).
     */
    fun pickTitleSuggestion(suggestion: TitleSuggestion) {
        titleDraft = suggestion.title
        pendingSuggestion = suggestion
        val seriesItems = store.list().filter {
            it.title.equals(suggestion.title, ignoreCase = true)
        }
        val existing = seriesItems.mapNotNull { extractSeasonEpisode(it.title) }.toSet()
        val next = when {
            suggestion.maxSeason == null -> null
            else -> {
                // Continue within the same season until its cap is unknown; propose
                // maxSeason/maxEpisode + 1 as the primary suggestion.
                suggestion.maxSeason!! to ((suggestion.maxEpisode ?: 0) + 1)
            }
        }
        seasonDraft = next?.first?.toString() ?: ""
        episodeDraft = next?.second?.toString() ?: ""
        // T-UX04: tell the user where the pre-filled numbers come from.
        seasonEpisodeSuggestLabel = next?.let {
            "Suggested: S%02dE%02d — based on the highest episode in this series.".format(it.first, it.second)
        }
        if (suggestion.mediaType == "tv") {
            askingSeriesDetails = true
            val notes = buildList {
                if (next != null && next in existing) {
                    add("S%02dE%02d already exists — pick a different episode, or confirm to add a duplicate copy (the existing episode is kept).".format(next.first, next.second))
                }
                if (existing.any { it.first == 0 }) {
                    add("This series has specials. Enter 0 as the season to add another special.")
                }
            }
            seriesNotes = notes
        } else {
            seriesNotes = emptyList()
        }
        status = notesOrNull(seriesNotes)
    }

    private fun notesOrNull(notes: List<String>): String? = notes.firstOrNull()

    fun dismissSeriesNotes() {
        seriesNotes = emptyList()
    }

    private fun extractSeasonEpisode(title: String): Pair<Int, Int>? {
        val m = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})|\b(\d{1,2})x(\d{1,3})\b""").find(title) ?: return null
        val s = (m.groupValues[1].ifBlank { m.groupValues[3] }).toIntOrNull() ?: return null
        val e = (m.groupValues[2].ifBlank { m.groupValues[4] }).toIntOrNull() ?: return null
        return s to e
    }

    private suspend fun finishImported(item: LibraryItem, query: String, playNow: Boolean) {
        busy = true
        prepareProgress = 0.15f
        prepareStep = "Preparing ${item.title}"
        status = prepareStep
        try {
            prepareProgress = 0.4f
            prepareStep = "Looking up artwork"
            enrichImported(item, query)
            prepareProgress = 0.75f
            prepareStep = "Saving library details"
            refresh()
            syncLibrary()
            val ready = store.get(item.id) ?: item
            if (needsArtworkChoice(ready)) {
                // A good filename can bypass title correction, but it must not
                // bypass artwork recovery. Try several positions and let the
                // user choose; the UI's movie tile remains the final fallback.
                showTitlePrompt(ready)
                return
            }
            prepareProgress = 1f
            prepareStep = "Ready"
            status = if (playNow) {
                playOnTv(ready)
                "Playing ${ready.title} on $tvName"
            } else {
                "Added ${ready.title}"
            }
            delay(500)
        } finally {
            busy = false
            prepareStep = null
            prepareProgress = 0f
        }
    }

    fun enhance(item: LibraryItem) {
        viewModelScope.launch {
            busy = true
            status = "Enhancing ${item.title} (keeping primary audio)…"
            try {
                withContext(Dispatchers.IO) {
                    val local = if (!store.hasLocalBytes(item)) store.materialize(item) else item
                    val input = store.videoFile(local)
                    val output = File(input.parentFile, "${local.id}.enhanced.mp4")
                    EnhanceAudio.remuxPrimaryTracks(input, output)
                    store.markEnhanced(local.id, output)
                    if (output.exists() && output.absolutePath != store.videoFile(store.get(local.id)!!).absolutePath) {
                        output.delete()
                    }
                }
                refresh()
                status = "Enhanced ${item.title}"
            } catch (error: Exception) {
                status = error.message ?: "Enhance failed for this file"
            } finally {
                busy = false
            }
        }
    }

    // ---- Removing Telegram titles (spec 004 US5) ----

    /** Titles hidden from the library, for Import → Telegram → Hidden videos; null until opened. */
    var hiddenTelegramTitles by mutableStateOf<List<TelegramTombstone>?>(null)
    var showHiddenVideos by mutableStateOf(false)

    /**
     * Hides the title everywhere ([permanently] false; it can be restored) or deletes the video, and
     * every channel message with that file, from Telegram. The control plane removes it from all TVs.
     */
    fun removeTelegramTitle(item: LibraryItem, permanently: Boolean) {
        deleteTarget = null
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            val result = catalog().removeTelegramTitle(token, item.id, if (permanently) "delete" else "hide")
            if (result == null) {
                status = "Couldn't reach Cablegram to remove ${item.title}. Check your connection and try again."
                return@launch
            }
            if (nowPlaying?.id == item.id) nowPlaying = null
            if (selectedId == item.id) selectedId = null
            store.removeFromLibrary(item.id)
            refresh()
            result.telegramDeletions.forEach {
                telegramDeletions.add(PendingTelegramDeletion(it.stableSourceKey, it.chatId, it.messageIds, item.title, System.currentTimeMillis()))
            }
            status = if (permanently) "Deleting ${item.title} from Telegram…" else "Removed ${item.title}. Restore it from Import → Telegram → Hidden videos."
            processTelegramDeletions()
        }
    }

    /** Runs the queued channel deletions; whatever fails stays queued and is retried on the next start or removal. */
    fun processTelegramDeletions() {
        if (!PhoneTelegram.configured || telegramDeletions.all().isEmpty()) return
        val token = pairing.accountToken ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!telegramDeletionMutex.tryLock()) return@launch
            try {
                if (!PhoneTelegram.wasLinked(getApplication())) return@launch
                val session = PhoneTelegram.session(getApplication())
                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    session.state.first { it is app.cablegram.telegram.TelegramState.Ready }
                } ?: return@launch
                val jobs = telegramDeletions.all()
                // Review fix 10: one walk of each channel for the whole batch, not one per queued deletion.
                val inChannel = jobs.filter { !it.deletedInTelegram }.groupBy { it.chatId }.mapValues { (chatId, group) ->
                    runCatching { TelegramRemoval.channelMessageIds(session.api, chatId, group.map { it.stableSourceKey }.toSet()) }
                }
                for (job in jobs) {
                    val outcome = if (job.deletedInTelegram) Result.success(Unit) else runCatching {
                        val found = inChannel.getValue(job.chatId).getOrThrow()[job.stableSourceKey].orEmpty()
                        val ids = (found + job.messageIds).sorted()
                        if (ids.isNotEmpty()) session.api.deleteMessages(job.chatId, ids.toLongArray())
                    }
                    if (outcome.isSuccess) {
                        telegramDeletions.markDeletedInTelegram(job.stableSourceKey)
                        // Review fix 3: the entry goes only once the control plane knows; otherwise its tombstone
                        // would stay "deleting" forever. The retry only reports, it never deletes again.
                        if (catalog().reportTelegramDeletion(token, job.stableSourceKey)) {
                            telegramDeletions.remove(job.stableSourceKey)
                            status = "Deleted ${job.title} from Telegram."
                        }
                    } else {
                        val error = outcome.exceptionOrNull()?.message ?: "unknown error"
                        telegramDeletions.failed(job.stableSourceKey, error)
                        catalog().reportTelegramDeletion(token, job.stableSourceKey, error)
                        if (System.currentTimeMillis() - job.createdAt > TelegramDeletionQueue.GIVE_UP_AFTER_MS) {
                            status = "Couldn't delete ${job.title} from Telegram. Open Telegram and delete it from the channel."
                        }
                    }
                }
            } finally {
                telegramDeletionMutex.unlock()
            }
        }
    }

    fun openHiddenVideos() {
        showHiddenVideos = true
        loadHiddenVideos()
    }

    fun loadHiddenVideos() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { hiddenTelegramTitles = catalog().telegramTombstones(token) }
    }

    fun restoreHiddenVideo(title: TelegramTombstone) {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            if (catalog().restoreTelegramTitle(token, title.stableSourceKey)) {
                status = "Restored ${title.title}."
                loadHiddenVideos()
                syncLibrary(force = true)
            } else {
                status = "Couldn't restore ${title.title}. Check your connection and try again."
            }
        }
    }

    fun askDelete(item: LibraryItem) {
        deleteTarget = item
    }

    fun removeFromLibrary(item: LibraryItem) {
        if (nowPlaying?.id == item.id) nowPlaying = null
        store.removeFromLibrary(item.id)
        deleteTarget = null
        if (selectedId == item.id) selectedId = null
        refresh()
        viewModelScope.launch { syncLibrary() }
        status = "Removed ${item.title} from the library. The file is still on the phone."
    }

    fun deleteFilePermanently(item: LibraryItem) {
        if (nowPlaying?.id == item.id) nowPlaying = null
        store.deleteFile(item.id)
        deleteTarget = null
        if (selectedId == item.id) selectedId = null
        refresh()
        viewModelScope.launch { syncLibrary() }
        status = "Deleted ${item.title} from this phone."
    }

    fun playOnTv(item: LibraryItem) {
        if (!paired) {
            selectedId = item.id
            startAddTv()
            status = "Connect a TV to play ${item.title}."
            return
        }
        viewModelScope.launch {
            val ready = ensurePlayable(item) ?: return@launch
            // Only show "now playing" once the control service accepted it.
            val target = sendCommand("play", ready.id) ?: run {
                status = remoteStatus
                return@launch
            }
            nowPlaying = ready
            paused = false
            store.setWatchProgress(ready.id, ready.positionSeconds.coerceAtLeast(1))
            refresh()
            status = "Playing ${ready.title} on $target"
        }
    }

    fun playLocallyHint(item: LibraryItem) {
        viewModelScope.launch {
            ensurePlayable(item)
            status = "Ready on this phone. Use Play on TV when a TV is nearby."
        }
    }

    fun skipSeconds(delta: Int) {
        enqueue("seek", arguments = buildJsonObject { put("seconds", delta) })
    }

    fun togglePlayPause() {
        paused = !paused
        // A play command without a video ID resumes the current player.
        enqueue(if (paused) "pause" else "play")
    }

    fun stopCast() {
        enqueue("stop")
        nowPlaying = null
        paused = false
    }

    fun selectTv(tv: PairedTv) {
        if (tv == pairing.tvs.lastOrNull()) return
        if (tv !in pairing.tvs) return
        pairing.tvs = pairing.tvs.filterNot { it.pin == tv.pin } + tv
        tvs = pairing.tvs
        tvName = pairing.tvName
        nowPlaying = null
        paused = false
        remoteStatus = null
    }

    fun remoteNavigate(direction: String) {
        if (direction !in setOf("up", "down", "left", "right")) return
        enqueue("move", arguments = buildJsonObject { put("direction", direction) })
    }

    fun remoteSelect() = enqueue("select")

    fun playNext() {
        val next = neighbor(1) ?: return
        playOnTv(next)
    }

    fun playPrevious() {
        val previous = neighbor(-1) ?: return
        playOnTv(previous)
    }

    private fun neighbor(step: Int): LibraryItem? {
        if (items.isEmpty()) return null
        val current = nowPlaying?.id
        val index = items.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: 0
        val nextIndex = if (shuffle) items.indices.random() else Math.floorMod(index + step, items.size)
        return items[nextIndex]
    }

    /** T078 / R-8: remote-tab actions (public wrappers around enqueue). */
    fun remoteVolume(level: Int) {
        enqueue("volume", arguments = buildJsonObject { put("level", level.coerceIn(0, 100)) })
    }

    fun remoteStop() {
        stopCast()
    }

    fun download(item: LibraryItem) {
        viewModelScope.launch {
            if (!transfersAllowed()) {
                status = transferBlockReason()
                return@launch
            }
            busy = true
            status = "Downloading ${item.title}…"
            try {
                // A title freed up from the phone comes back from the user's own storage (R2, or Google Drive with its header).
                val remote = if (item.ownCloudCopy && !store.hasSource(item)) {
                    pairing.accountToken?.let { catalog().ownCloudReadUrl(it, item.id) }
                        ?: throw IllegalStateException("Couldn't reach your cloud storage. Check that it is still connected in Storage.")
                } else null
                val ready = withContext(Dispatchers.IO) {
                    store.update(item.copy(downloadBytes = 0, downloadTotal = item.fileSizeBytes ?: 0))
                    store.materialize(item, remote) { copied, total ->
                        store.update(item.copy(downloadBytes = copied, downloadTotal = total))
                    }
                }
                refresh()
                status = "Downloaded ${ready.title}"
            } catch (error: Exception) {
                status = error.message ?: "Download failed"
            } finally {
                busy = false
            }
        }
    }

    // ---- Save to Telegram (spec 004 T011) ----

    private val telegramSaves = mutableSetOf<String>()

    /**
     * Uploads this title's video into the household's Telegram channel, verifies it by size and file id, and adds
     * it as a second source of the same title. Free up space is only offered after that (T012). A failed or
     * interrupted upload ends as "Save failed"; saving again retries.
     */
    fun saveToTelegram(item: LibraryItem) {
        if (!canSaveToTelegram(item) || !telegramSaves.add(item.id)) return
        val token = pairing.accountToken
        val chatId = telegramLink?.chatId
        if (token == null || chatId == null || telegramLink?.linked != true || !PhoneTelegram.wasLinked(getApplication())) {
            telegramSaves.remove(item.id)
            status = "Connect Telegram on this phone first: Import → Telegram."
            return
        }
        viewModelScope.launch {
            var upload: LibraryStore.UploadFile? = null
            try {
                val size = withContext(Dispatchers.IO) { store.videoLength(item) }
                store.update(item.copy(transferStatus = TRANSFER_SAVING, uploadBytes = 0, uploadTotal = size))
                refresh()
                val session = PhoneTelegram.session(getApplication())
                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    session.state.first { it is app.cablegram.telegram.TelegramState.Ready }
                } ?: error("Telegram isn't ready on this phone. Try again in a moment.")
                // Refused before any upload: the account's file size limit (2 GB, 4 GB with Premium).
                telegramUploadRefusal(size, session.api.uploadLimitBytes())?.let { error(it) }
                // An earlier attempt may have reached the channel and failed afterwards: reuse that upload.
                val earlier = TelegramRemoval.findUpload(session.api, chatId, size, store.telegramUploadNames(item))
                val sent = earlier ?: run {
                    val prepared = withContext(Dispatchers.IO) { store.telegramUploadFile(item) }
                    upload = prepared
                    status = "Saving ${item.title} to Telegram…"
                    var lastWrite = 0L
                    session.api.uploadVideo(chatId, prepared.file.path, item.title) { done, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastWrite >= 1_000) {
                            lastWrite = now
                            store.updateItem(item.id) { it.copy(uploadBytes = done, uploadTotal = total) }
                            refresh()
                        }
                    }
                }
                // Verify what Telegram stored before anything else relies on it.
                val stored = session.api.message(chatId, sent.messageId)?.video
                check(stored != null && stored.file.uniqueId == sent.file.uniqueId && stored.file.size == size) {
                    "Telegram didn't confirm the upload. Try saving again."
                }
                check(catalog().attachTelegramCopy(token, item.id, sent)) { "Couldn't add the Telegram copy to your library. Try saving again." }
                store.updateItem(item.id) { it.copy(telegramCopy = true, transferStatus = TRANSFER_IDLE, webTransferError = null, uploadBytes = null, uploadTotal = null) }
                status = "Saved ${item.title} to Telegram. You can now free up phone space."
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                store.updateItem(item.id) { it.copy(transferStatus = TRANSFER_FAILED, webTransferError = TELEGRAM_SAVE_FAILED) }
                throw cancelled
            } catch (error: Exception) {
                // Our own messages (size, verification, registration) say which step failed; no secrets in them.
                PairLog.w("Save to Telegram failed: ${error.javaClass.simpleName}: ${error.message}")
                store.updateItem(item.id) { it.copy(transferStatus = TRANSFER_FAILED, webTransferError = TELEGRAM_SAVE_FAILED) }
                status = (error as? app.cablegram.telegram.TgException)?.let(app.cablegram.telegram.TelegramSession::describe)
                    ?: error.message ?: "Couldn't save ${item.title} to Telegram."
            } finally {
                upload?.cleanUp()
                telegramSaves.remove(item.id)
                refresh()
            }
        }
    }

    fun beginSaveToCloud(item: LibraryItem) {
        runCatching {
            saveTargetId = item.id
            transferCardDismissed = false
            cloudSheet = when {
                !cablegramCloudReady && !cloudConnected -> CloudSheet.Setup
                !fitsInCloud(item, cloudAvailable) && !cloudUnlimited -> CloudSheet.Oversize
                else -> CloudSheet.Confirm
            }
        }.onFailure {
            PairLog.e("Save to Cloud screen failed", it)
            status = it.message ?: "Could not open Save to Cloud"
        }
    }

    fun acceptCablegramCloud() {
        pairing.cablegramCloudReady = true
        cablegramCloudReady = true
        val item = saveTarget ?: return
        cloudSheet = if (!fitsInCloud(item, cloudAvailable)) CloudSheet.Oversize else CloudSheet.Confirm
    }

    fun openOwnCloudSetup() {
        cloudSheet = CloudSheet.Manage
        tab = PhoneTab.Storage
    }

    fun confirmSaveToCloud() {
        val item = saveTarget ?: return
        cloudSheet = CloudSheet.None
        enqueueSave(item)
    }

    fun chooseAnotherStorage() {
        cloudSheet = CloudSheet.Setup
    }

    fun dismissCloudSheet() {
        cloudSheet = CloudSheet.None
        freeUpTargetId = null
    }

    fun retrySave(item: LibraryItem) {
        store.update(item.copy(transferStatus = TRANSFER_IDLE, webTransferJobId = null, webTransferError = null))
        refresh()
        beginSaveToCloud(store.get(item.id) ?: item)
    }

    fun enqueueSave(item: LibraryItem) {
        store.update(
            item.copy(
                transferStatus = TRANSFER_SAVING,
                uploadBytes = 0,
                uploadTotal = item.fileSizeBytes,
            ),
        )
        refresh()
        if (item.sourceKind == "web") {
            pollWebTransfer(item.id)
            return
        }
        runCatching { CloudTransferService.start(getApplication(), item.id, ownR2 = cloudConnected, destination = saveDestinationName) }
            .onFailure {
                PairLog.e("Could not start cloud save service", it)
                status = it.message ?: "Could not start cloud save"
            }
    }

    private fun resumeWebTransfers() {
        store.list().filter { it.sourceKind == "web" && it.transferStatus == TRANSFER_SAVING }
            .forEach { pollWebTransfer(it.id) }
    }

    private fun pollWebTransfer(itemId: String) {
        if (!webTransferPolls.add(itemId)) return
        viewModelScope.launch {
            try {
                while (isActive) {
                    val item = store.get(itemId) ?: return@launch
                    if (item.transferStatus != TRANSFER_SAVING) return@launch
                    val token = pairing.accountToken ?: return@launch
                    try {
                        var jobId = item.webTransferJobId
                        if (jobId == null) {
                            check(cablegramCloudReady) { "Cablegram managed storage is not configured." }
                            jobId = catalog().saveWebToStorage(itemId, token).jobId
                            store.updateItem(itemId) { it.copy(webTransferJobId = jobId, webTransferError = null) }
                        }
                        val job = catalog().libraryJob(jobId, token)
                        if (job.status == "completed") {
                            store.updateItem(itemId) { it.copy(
                                transferStatus = TRANSFER_IDLE, webTransferError = null,
                                cloudObjectPresent = true, storageState = STORAGE_CLOUD,
                            ) }
                            refresh()
                            return@launch
                        }
                        if (job.status == "failed") {
                            store.updateItem(itemId) { it.copy(transferStatus = TRANSFER_FAILED, webTransferError = job.errorCode) }
                            status = job.errorCode ?: "Could not save that video"
                            refresh()
                            return@launch
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: java.io.IOException) {
                        // The server keeps working; keep the job so polling resumes after reconnect or restart.
                        delay(8_000)
                    } catch (error: Exception) {
                        if (store.get(itemId)?.webTransferJobId == null) {
                            store.updateItem(itemId) { it.copy(transferStatus = TRANSFER_FAILED, webTransferError = error.message) }
                            status = error.message
                            refresh()
                            return@launch
                        }
                        delay(8_000)
                    }
                    delay(2_000)
                }
            } finally {
                webTransferPolls.remove(itemId)
            }
        }
    }

    fun runSaveInBackground() {
        transferCardDismissed = true
    }

    fun askRemoveCloudCopy(item: LibraryItem) {
        if (canRemoveOwnCloudCopy(item)) removeCloudCopyTarget = item
    }

    /** Deletes just the cloud copy; the video stays on the phone and the title keeps its other sources. */
    fun removeCloudCopy(item: LibraryItem) {
        removeCloudCopyTarget = null
        val sourceId = item.ownCloudSourceId ?: return
        val token = pairing.accountToken ?: return
        val destination = saveDestinationName
        viewModelScope.launch {
            busy = true
            try {
                val result = catalog().removeOwnCloudSource(token, sourceId)
                if (result.error == null || result.error == "not_found") {
                    store.updateItem(item.id) { it.copy(ownCloudCopy = false, ownCloudSourceId = null) }
                    refresh()
                    refreshStorage()
                }
                status = removeCopyMessage(result, destination)
            } finally {
                busy = false
            }
        }
    }

    fun openFreeUp() {
        cloudSheet = CloudSheet.FreeUp
    }

    fun askFreeUp(item: LibraryItem) {
        freeUpTargetId = item.id
        cloudSheet = CloudSheet.FreeUpConfirm
    }

    fun confirmFreeUp(item: LibraryItem) {
        removeLocalCopy(item)
        freeUpTargetId = null
        cloudSheet = CloudSheet.None
    }

    fun downloadFromCloud(item: LibraryItem) {
        download(item)
    }

    fun removeLocalCopy(item: LibraryItem) {
        if (!canRemoveLocalCopy(item)) {
            status = "Keep a cloud copy before removing the phone file."
            return
        }
        store.removeLocalCopy(item.id)
        // Only in Telegram now: the phone file is gone, so this phone no longer serves it.
        val elsewhereOnly = !item.cloudObjectPresent && (item.telegramCopy || item.ownCloudCopy)
        if (elsewhereOnly) store.updateItem(item.id) { it.copy(sourceAvailable = false) }
        refresh()
        status = when {
            elsewhereOnly && item.ownCloudCopy -> "Removed the phone copy. ${item.title} stays in your Cloudflare storage."
            elsewhereOnly -> "Removed the phone copy. ${item.title} stays in Telegram."
            else -> "Removed the phone copy. ${item.title} stays in the cloud."
        }
    }

    fun keepOnPhone(item: LibraryItem) {
        if (!item.copied) download(item) else status = "${item.title} is already on this phone."
        cloudSheet = CloudSheet.None
        freeUpTargetId = null
    }

    fun clearLocalMetadata() {
        store.clearPosters()
        refresh()
        status = "Cleared local posters and artwork cache."
    }

    private suspend fun ensurePlayable(item: LibraryItem): LibraryItem? {
        if (item.sourceKind == "web") return store.get(item.id) ?: item
        if (store.hasLocalBytes(item) || store.openPfd(item) != null) return store.get(item.id) ?: item
        if (!transfersAllowed()) {
            status = transferBlockReason()
            return null
        }
        return try {
            withContext(Dispatchers.IO) { store.materialize(item) }
        } catch (error: Exception) {
            status = error.message ?: "Could not open that video"
            null
        }
    }

    private fun transfersAllowed(): Boolean {
        val app = getApplication<Application>()
        if (pairing.transferWhileCharging && !isCharging(app)) return false
        if (!pairing.wifiOnlyTransfers) return true
        return isWifi(app)
    }

    private fun transferBlockReason(): String = when {
        pairing.transferWhileCharging && !isCharging(getApplication()) -> "Transfers wait until this phone is charging."
        pairing.wifiOnlyTransfers && !isWifi(getApplication()) -> "Transfers wait for Wi‑Fi."
        else -> "Transfer blocked."
    }

    fun hideAds() {
        pairing.adsHidden = true
        ads = ads?.copy(enabled = false)
    }

    val showAds: Boolean
        get() = ads?.enabled == true && !pairing.adsHidden

    /** Send to the selected TV only; report delivery failures instead of silently broadcasting. */
    private fun enqueue(command: String, videoId: String? = null, arguments: JsonObject = buildJsonObject {}) {
        viewModelScope.launch { sendCommand(command, videoId, arguments) }
    }

    /** Returns the TV name, or null on failure. */
    private suspend fun sendCommand(command: String, videoId: String? = null, arguments: JsonObject = buildJsonObject {}): String? {
        remoteStatus = "Sending…"
        return when (val result = CastSession.send(pairing, catalog(), command, videoId, arguments)) {
            is CastSession.Result.Sent -> result.tvName.also { remoteStatus = "Sent to $it" }
            is CastSession.Result.Failed -> null.also { remoteStatus = result.message }
        }
    }

    private fun publishCast() {
        CastSession.update(nowPlayingState?.let { Cast(it.id, it.title, tvName, pausedState) })
    }

    private fun persistRead(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}

enum class PhoneTab { Library, Browse, Remote, Storage, Settings }

enum class TelegramHealth { Unknown, Ok, ChannelLost, SignedOut }

enum class CloudSheet { None, Setup, Confirm, Oversize, Manage, ConnectR2, FreeUp, FreeUpConfirm }

private typealias File = java.io.File

private fun isWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private fun isCharging(context: Context): Boolean {
    val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    return battery.isCharging
}
