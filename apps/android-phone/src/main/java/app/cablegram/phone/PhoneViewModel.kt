package app.cablegram.phone

import app.cablegram.phone.cast.*
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.withTimeoutOrNull
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

    var castConnect by mutableStateOf(pairing.castConnect)
        private set
    var legacyRemote by mutableStateOf(pairing.legacyRemote)
        private set
    var castRoutes by mutableStateOf<List<CastRoute>>(emptyList())
        private set
    var castPickerOpen by mutableStateOf(false)
        private set
    private var castSender: CastConnectSender? = null
    private var castRoutesJob: Job? = null
    private var castVisible = false
    private var pickerTitle: LibraryItem? = null
    private var pickerDiscoveryJob: Job? = null
    private var castPlaybackOwned = false
    private var lastCastDeviceStatus: CastDeviceStatus? = null

    fun setCastVisible(visible: Boolean) {
        castVisible = visible
        if (castConnect && castSender == null) {
            castSender = CastConnectSender(getApplication()).also { sender ->
                sender.onDevice = { status, routeId ->
                    lastCastDeviceStatus = status
                    // The server's household list and existing pairing bound this untrusted hint.
                    if (householdTvsCache?.any { it.id == status.deviceId } == true) {
                        pairing.attachCastDeviceId(status.deviceId, routeId)
                        tvs = pairing.tvs
                    }
                }
                sender.onPlayback = { isPaused, terminal ->
                    if (castPlaybackOwned) {
                        if (terminal) { nowPlaying = null; paused = false; castPlaybackOwned = false }
                        else isPaused?.let { paused = it }
                    }
                }
                castRoutesJob = viewModelScope.launch { sender.routes.collect { castRoutes = it } }
            }
        }
        castSender?.setVisible(visible)
        if (!visible) dismissCastPicker()
    }

    fun updateCastConnect(value: Boolean) {
        pairing.castConnect = value
        castConnect = value
        if (!value) {
            dismissCastPicker(); castRoutesJob?.cancel(); castSender?.release()
            castSender = null; castRoutes = emptyList(); castPlaybackOwned = false
        } else setCastVisible(castVisible)
    }

    fun updateLegacyRemote(value: Boolean) {
        pairing.legacyRemote = value
        legacyRemote = value
        if (!value && tab == PhoneTab.Remote) tab = PhoneTab.Library
    }

    fun dismissCastPicker() {
        pickerDiscoveryJob?.cancel(); pickerDiscoveryJob = null
        castPickerOpen = false; pickerTitle = null
        castSender?.setVisible(castVisible)
    }

    fun chooseCastRoute(route: CastRoute) {
        if (route !in castRoutes) return
        val title = pickerTitle ?: return
        dismissCastPicker()
        startTitleOnTv(title, route)
    }

    val artworkEditor = ArtworkEditorController(store, { catalog() }, { pairing.accountToken }, viewModelScope) { saved ->
        refresh()
        status = "Changes saved"
        viewModelScope.launch { syncLibrary() }
    }
    var recentlyAddedIds by mutableStateOf<List<String>>(emptyList())
        private set
    fun dismissAddedVideos() { recentlyAddedIds = emptyList() }

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
    /** The title a TV was asked to start and has not answered for yet; null otherwise. */
    var startingTitle by mutableStateOf<String?>(null)
        private set
    private var titleStartToken = 0L
    private var pausedState by mutableStateOf(false)
    var paused: Boolean
        get() = pausedState
        private set(value) {
            pausedState = value
            publishCast()
        }
    var shuffle by mutableStateOf(false)
    var repeat by mutableStateOf(false)
    var pendingPrivacyItem by mutableStateOf<LibraryItem?>(null)
        private set
    var storage by mutableStateOf<StorageStatusResponse?>(null)
        private set
    var phoneStorage by mutableStateOf(store.phoneStorage())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var searchQuery by mutableStateOf("")
    var editingMetadata by mutableStateOf(false)
    // Smart subtitles: the saved subtitle (if any) for the open title, and whether the Find Subtitles flow is showing.
    var subtitleFlowOpen by mutableStateOf(false)
    var savedSubtitle by mutableStateOf<String?>(null)
        private set
    fun accountTokenOrNull(): String? = pairing.accountToken?.takeIf { it.isNotBlank() }
    suspend fun subtitleRequest(path: String, token: String, method: String = "GET", body: String? = null): String = catalog().subtitleRequest(path, token, method, body)
    // Analysis reads short sections far apart: a small download window keeps what Telegram fetches close to what the
    // analysis budget counts (the default 48 MB window would pull far more than the cap at every section).
    private val telegramMedia by lazy {
        PhoneTelegramMedia(
            getApplication(),
            isRemoved = RemovedTelegramSources(catalog(), pairing)::contains,
            chatId = { pairing.accountToken?.let { catalog().telegramLink(it)?.chatId } },
            window = 4L * app.cablegram.telegram.TelegramFileReader.MB,
            refillAt = 1L * app.cablegram.telegram.TelegramFileReader.MB,
        )
    }
    /** The title's bytes for analysis and preview: the local file, or the Telegram channel copy. Blocking; call off the main thread. */
    fun openVideo(item: LibraryItem): SubtitleMediaInput {
        store.openPfd(item)?.let { return FileMediaInput(it) }
        if (item.sourceKind != "telegram") throw VideoUnavailable("This video isn't stored on this phone, so it can't be checked or previewed here.")
        val key = item.telegramFileKey?.removePrefix("tgfile:")
            ?: throw VideoUnavailable("Pull to refresh your library once so this Telegram video can be linked, then try again.")
        if (!PhoneTelegram.configured) throw VideoUnavailable("Telegram isn't set up in this version of the app.")
        val file = runCatching { telegramMedia.resolve(key) }.getOrNull()
            ?: throw VideoUnavailable("Telegram didn't provide this video. Check that Telegram is connected in Settings, then try again.")
        return TelegramMediaInput(telegramMedia, file)
    }
    /** Reads what the TV will use for this title, so the detail screen can say which subtitle is saved. */
    fun refreshSubtitleStatus(item: LibraryItem) {
        val token = accountTokenOrNull() ?: return
        viewModelScope.launch {
            val label = runCatching {
                val tracks = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<SavedSubtitleList>(catalog().subtitleRequest("playback/item/${item.id}", token)).subtitles
                tracks.firstOrNull()?.let { languageName(it.language) }
            }.getOrNull()
            // A late answer for a title that was closed meanwhile must not label the next one.
            if (selectedId == item.id) savedSubtitle = label
        }
    }
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
        if (paired) viewModelScope.launch { pairing.tvs.lastOrNull()?.let { publishLanAddress(it.pin) } }
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
        // One poller serves the notification and this list: ApprovalWatcher, in LanLibraryService.
        viewModelScope.launch { PrivateApprovals.pending.collect { pendingApprovals = it } }
        refreshPhoneIps()
        viewModelScope.launch { ads = catalog().ads() }
        refreshStorage()
        recoverSharedImports()
        resumePendingWebImports()
        clearRetiredWebSaves()
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
        /** How long a finished command's result stays in [remoteStatus] before it clears. */
        private const val REMOTE_STATUS_LINGER_MS = 6_000L
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
            PrivateApprovals.pending.value = emptyList()
            return
        }
        viewModelScope.launch {
            PrivateApprovals.pending.value = runCatching { catalog().fetchPendingPrivateApprovals(token) }.getOrDefault(emptyList())
        }
    }

    /** T075: approve / deny a pending request from inside the phone app. */
    fun respondToApproval(approval: PendingApproval, approve: Boolean) {
        val token = pairing.accountToken ?: return
        val app = getApplication<Application>()
        PrivateApprovals.cancelNotification(app, approval.attemptId)
        PrivateApprovals.pending.value = PrivateApprovals.pending.value.filterNot { it.attemptId == approval.attemptId }
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

    /** True while the "tick the box" note is on screen, before Google's sign-in opens. */
    var googleSignInNoteOpen by mutableStateOf(false)

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
        tab = PhoneTab.Storage
        cloudSheet = CloudSheet.Manage
        // Any app or page can open this link, so "connected" is said only once the server confirms it.
        if (ret.result != "connected") {
            val message = storageReturnMessage(ret, storage)
            status = message
            // A missed permission box is shown on the sheet itself, beside the button to try again.
            googleConnectError = if (signInNeedsAnotherTry(ret)) message else null
            refreshStorage()
            return
        }
        googleConnectError = null
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            val confirmed = catalog().storageStatus(token)
            storage = confirmed
            val connection = confirmed?.connection
            if (connection?.provider == ret.provider && connection.status == "active") status = storageReturnMessage(ret, confirmed)
        }
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
                clearRetiredWebSaves()
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
                // Attach the per-device LAN capability (R-1), the TV's only LAN credential.
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
    /** The household's TVs as of the last refresh; routing uses it instead of a request per press. */
    private var householdTvsCache: List<MeDevice>? = null

    /**
     * The control plane is the source of truth for the household's TVs: TVs paired by another phone
     * or before a reinstall appear here, revoked ones disappear. Offline keeps the local list.
     */
    private suspend fun syncHouseholdTvs() {
        val token = pairing.accountToken ?: return
        val server = catalog().householdTvs(token) ?: return
        householdTvsCheckedAt = System.currentTimeMillis()
        householdTvsCache = server
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
        } else if (paired && kept.size < local.size) {
            // A TV removed from another phone: the running LAN service refuses it now, not after a restart (CAB-37).
            LanLibraryService.start(getApplication())
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
        PrivateApprovals.pending.value = emptyList()
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
            // The LAN service keeps serving the other TVs; this makes it refuse the removed one now (CAB-37).
            LanLibraryService.start(getApplication())
            tvName = pairing.tvName
            status = "Removed this TV. ${pairing.tvs.size} still paired."
        }
        PairLog.i("Phone unpaired one TV remaining=${pairing.tvs.size}")
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
                        store.updateItem(item.id) { it.copy(fingerprint = item.fingerprint) }
                    }
                }
                val phoneItems = fingerprinted.filter { it.sourceKind == "phone_local" }
                val present = phoneItems.filter { store.hasSource(it) }
                val presentIds = present.map { it.id }.toSet()
                // Loss of a file grant, disconnected storage, or an offline source
                // changes availability; it must never delete the user's catalog.
                phoneItems.forEach { item ->
                    val available = item.id in presentIds
                    if (item.sourceAvailable != available) store.updateItem(item.id) { it.copy(sourceAvailable = available) }
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
                        client.pushLibraryItem(token, item, pairing.phoneDeviceId, onImported = { store.setCatalogIdentity(item.id, it) }) &&
                            syncHouseholdArtwork(client, token, item.id)
                    }.getOrDefault(false)
                    if (!ok) {
                        error("Could not sync ${item.title}. Try again when connected")
                    }
                }
                prepareStep = null
                prepareProgress = 0f
                var metadataFailed = false
                // Edits are persisted independently of source availability and survive process death.
                for (item in store.list().filter { it.pendingMetadataFields.isNotEmpty() }) {
                    if (item.metadataConflict) { metadataFailed = true; continue }
                    val result = client.patchMetadata(token, item)
                    store.acceptMetadata(item, result)
                    if (result !is MetadataSyncResult.Saved) metadataFailed = true
                }
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
                        store.updateItem(local.id) { current -> mergeManualMetadata(current.copy(
                            durationSeconds = remote.durationSeconds ?: local.durationSeconds,
                            // What groups a series' episodes under one poster comes from the household catalog.
                            tmdbId = if (current.catalogIdentityUserSelected) current.tmdbId else remote.tmdbId ?: remote.seriesIdentity?.removePrefix("tmdb:")?.toIntOrNull() ?: current.tmdbId,
                            seasonNumber = if (current.catalogIdentityUserSelected) current.seasonNumber else remote.seasonNumber ?: current.seasonNumber,
                            episodeNumber = if (current.catalogIdentityUserSelected) current.episodeNumber else remote.episodeNumber ?: current.episodeNumber,
                            // A household-saved cover comes back as the server's copy; this phone keeps its own file.
                            posterUrl = if (catalogMayReplaceArtwork(local)) remote.posterUrl?.takeUnless(::isHouseholdPosterUrl) ?: local.posterUrl else local.posterUrl,
                            cloudObjectPresent = hasCloudObject || local.cloudObjectPresent,
                            // Saved to Telegram (here or on another phone): the server knows the verified copy.
                            telegramCopy = hasTelegramCopy && local.sourceKind == "phone_local",
                            ownCloudCopy = hasOwnCloudCopy && local.sourceKind == "phone_local",
                            ownCloudSourceId = if (hasOwnCloudCopy && local.sourceKind == "phone_local") ownCloudSourceIdOf(remote.sources) else null,
                            storageState = if (hasCloudObject) STORAGE_CLOUD else local.storageState,
                            transferStatus = if (hasCloudObject) TRANSFER_IDLE else local.transferStatus,
                        ), remote) }
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
                            store.mergeRemoteMetadata(added.id, remote)
                            if (hasCloudObject) store.updateItem(added.id) { it.copy(cloudObjectPresent = true, storageState = STORAGE_CLOUD) }
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
                    // Matching is available from item details; sync never interrupts browsing.
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
                    store.updateItem(item.id) { it.copy(fingerprint = item.fingerprint) }
                    runCatching {
                        client.pushLibraryItem(token, store.get(item.id) ?: item, pairing.phoneDeviceId, onImported = { store.setCatalogIdentity(item.id, it) })
                        syncHouseholdArtwork(client, token, item.id)
                    }
                }
                check(!metadataFailed) { "Your detail edits are saved on this phone. Review any conflicting edits, or retry sync when connected." }
            }
            refresh()
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
            // CAB-48: TVs accept this phone's LAN server only with this certificate.
            certSha256 = withContext(Dispatchers.IO) {
                runCatching { LanTlsIdentity.load().fingerprint }.onFailure { PairLog.e("LAN TLS identity unavailable", it) }.getOrNull()
            },
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
                recentlyAddedIds = listOf(item.id)
                tab = PhoneTab.Library
                status = "Video added"
                syncLibrary()
                if (playNow) playOnTv(store.get(item.id) ?: item)
            } catch (error: Exception) {
                status = error.message ?: "Could not import that file"
            } finally {
                busy = false
            }
        }
    }

    /** Cleans up after a process death mid-import, then finishes any shared import it left unfinished. */
    private fun recoverSharedImports() {
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                store.sweepVideos()
                store.resumeSharedImports()
            }
            if (results.isEmpty()) return@launch
            refresh()
            val dropped = results.filterIsInstance<LibraryStore.SharedImportResult.Dropped>()
            status = if (dropped.isNotEmpty()) {
                "Could not finish importing ${dropped.joinToString { it.displayName }}: the file is no longer available."
            } else {
                "Finished importing ${results.filterIsInstance<LibraryStore.SharedImportResult.Imported>().joinToString { it.item.title }}."
            }
        }
    }

    fun importWeb(url: String, caption: String? = null) {
        val pending = store.queueWebImport(url, caption)
        submitPendingWebImport(pending, announce = true)
    }

    private fun resumePendingWebImports() {
        if (pairing.accountToken.isNullOrBlank()) return
        // A link the server rejected for good (kept by older versions) is dropped, not sent again.
        val (rejected, retryable) = store.snapshot().pendingWebImports.partition { isPermanentWebImportError(it.lastError) }
        rejected.forEach { store.completeWebImport(it.id) }
        retryable.forEach { submitPendingWebImport(it, announce = false) }
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
                withContext(Dispatchers.IO) {
                    // Only a transient failure stays queued for the next launch.
                    if (isPermanentWebImportError(error.message)) store.completeWebImport(pending.id)
                    else store.failWebImport(pending.id, error.message ?: "web_import_failed")
                }
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
        recentlyAddedIds = imported.map { it.id }
        tab = PhoneTab.Library
        pendingPrivacyItems.addAll(imported)
        showNextPrivacyPrompt()
        viewModelScope.launch { syncLibrary() }
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
        viewModelScope.launch { syncLibrary() }
        showNextPrivacyPrompt()
        if (pendingPrivacyItem == null) status = "Videos added"
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
        subtitleFlowOpen = false
        savedSubtitle = null
        editingMetadata = false
        deleteTarget = null
    }

    fun saveMetadata(base: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?) {
        if (selectedId != base.id) return
        store.editMetadataDraft(base, title, year, mediaType, overview)
        editingMetadata = false
        refresh()
        viewModelScope.launch { syncLibrary() }
    }

    /** Optional editing never blocks import or playback. */
    fun findDetailsAndArtwork(item: LibraryItem) {
        artworkEditor.open(item)
    }

    /**
     * CAB-29: brings the household's copy of a cover in line with the user's choice. Frames stay on this
     * phone unless the user saved that exact cover; a changed or withdrawn cover is removed from the server.
     */
    private suspend fun syncHouseholdArtwork(client: CatalogClient, token: String, id: String): Boolean {
        val item = store.get(id) ?: return true
        val catalogId = item.catalogItemId ?: return true
        return when (householdArtworkStep(item)) {
            HouseholdArtworkStep.None -> true
            HouseholdArtworkStep.Upload -> {
                val bytes = withContext(Dispatchers.IO) { store.posterFile(item)?.readBytes() } ?: return true
                client.uploadPoster(token, catalogId, bytes).also { uploaded ->
                    if (uploaded) store.updateItem(id) { it.copy(householdArtworkUploadedVersion = item.posterVersion) }
                }
            }
            HouseholdArtworkStep.Remove -> client.removePoster(token, catalogId).also { removed ->
                if (removed) store.updateItem(id) { it.copy(householdArtworkUploadedVersion = null) }
            }
        }
    }

    /** "Save artwork to household", after the user confirmed the explanation. */
    fun saveArtworkToHousehold(item: LibraryItem) {
        val current = store.get(item.id) ?: return
        if (!canSaveArtworkToHousehold(current)) return
        store.updateItem(item.id) { it.copy(householdArtworkVersion = it.posterVersion) }
        refresh()
        applyHouseholdArtwork(item.id, done = "Cover saved to your household", pending = "The cover will be saved to your household on the next sync")
    }

    fun keepArtworkOnPhone(item: LibraryItem) {
        store.updateItem(item.id) { it.copy(householdArtworkVersion = null) }
        refresh()
        applyHouseholdArtwork(item.id, done = "Cover removed from your household", pending = "The cover will be removed from your household on the next sync")
    }

    private fun applyHouseholdArtwork(id: String, done: String, pending: String) {
        val token = pairing.accountToken
        viewModelScope.launch {
            val ok = token != null && store.get(id)?.catalogItemId != null &&
                runCatching { syncHouseholdArtwork(catalog(), token, id) }.getOrDefault(false)
            status = if (ok) done else pending
            refresh()
        }
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
        pickerDiscoveryJob?.cancel()
        val sender = castSender?.takeIf { castConnect && it.available }
        if (!paired || sender == null) { startTitleOnTv(item, null); return }
        matchingCastRoute(pairing.tvs.lastOrNull(), castRoutes)?.let { startTitleOnTv(item, it); return }
        pickerDiscoveryJob = viewModelScope.launch {
            sender.scanForPicker()
            // Give discovery a bounded chance; no route keeps the existing relay behavior.
            val routes = sender.routes.value.takeIf { it.isNotEmpty() }
                ?: withTimeoutOrNull(3_000) { sender.routes.first { it.isNotEmpty() } }
            if (!useCastForTitle(castConnect, sender.available, !routes.isNullOrEmpty())) {
                sender.setVisible(castVisible)
                startTitleOnTv(item, null)
            } else {
                val matching = matchingCastRoute(pairing.tvs.lastOrNull(), routes.orEmpty())
                if (matching != null) { sender.setVisible(castVisible); startTitleOnTv(item, matching) }
                else { pickerTitle = item; castPickerOpen = true }
            }
        }
    }

    private fun startTitleOnTv(item: LibraryItem, route: CastRoute?) {
        if (!paired) {
            selectedId = item.id
            startAddTv()
            status = "Connect a TV to play ${item.title}."
            return
        }
        viewModelScope.launch {
            val ready = ensurePlayable(item) ?: return@launch
            val effectiveRoute = route?.takeIf { castConnect && castSender?.available == true }
            // Only show "now playing" once the TV confirmed that playback started.
            val token = ++titleStartToken
            castPlaybackOwned = false
            val sent = try {
                sendCommand("play", ready.id, castRoute = effectiveRoute, castTitle = ready, wait = ConfirmationWait.TitleStart, onAccepted = { tv ->
                    startingTitle = ready.title
                    status = "Starting ${ready.title} on $tv…"
                })
            } finally {
                // A newer title start owns the field; this one only clears its own.
                if (titleStartToken == token) startingTitle = null
            } ?: run {
                status = remoteStatus
                return@launch
            }
            // A later command owns the status line; this one only applies what the TV confirmed.
            if (sent.latest) status = remoteStatus
            if (!sent.latest || titleStartToken != token || !sent.outcome.applies()) return@launch
            if (effectiveRoute != null && castSender?.playbackTerminated != false) return@launch
            castPlaybackOwned = effectiveRoute != null
            nowPlaying = ready
            paused = effectiveRoute != null && castSender?.playbackPaused == true
            store.setWatchProgress(ready.id, ready.positionSeconds.coerceAtLeast(1))
            refresh()
            if (sent.latest) status = if (sent.outcome == Outcome.Confirmed) "Playing ${ready.title} on ${sent.tvName}"
                else "Sent ${ready.title} to ${sent.tvName} (not confirmed by TV)"
        }
    }

    fun playLocallyHint(item: LibraryItem) {
        viewModelScope.launch {
            ensurePlayable(item)
            status = "Ready on this phone. Use Play on TV when a TV is nearby."
        }
    }

    /** Shifts the subtitle on the TV that is playing this title, relative to the timing it loaded (positive shows it later). */
    fun nudgeSubtitleOnTv(delayMs: Long) {
        // The TV takes at most ten minutes either way; a larger difference would be refused without a word.
        enqueue("subtitle_delay", arguments = buildJsonObject { put("delay_ms", delayMs.coerceIn(-600_000L, 600_000L)) })
    }

    fun skipSeconds(delta: Int) {
        enqueue("seek", arguments = buildJsonObject { put("seconds", delta) })
    }

    /** True while a play/pause waits for the TV, so a second tap cannot resend the same command. */
    private var toggleInFlight = false

    fun togglePlayPause() {
        if (toggleInFlight) return
        toggleInFlight = true
        // A play command without a video ID resumes the current player.
        val command = if (paused) "play" else "pause"
        viewModelScope.launch {
            try {
                val outcome = sendCommand(command)?.outcome ?: return@launch
                if (outcome.applies()) paused = command == "pause"
            } finally {
                toggleInFlight = false
            }
        }
    }

    fun stopCast() {
        viewModelScope.launch {
            val outcome = sendCommand("stop")?.outcome ?: return@launch
            if (!outcome.applies()) return@launch
            nowPlaying = null
            paused = false
        }
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
            // Not offered (see canSaveToCloud); kept as a guard so a stale screen can't start one.
            store.update(item.copy(transferStatus = TRANSFER_IDLE))
            refresh()
            return
        }
        runCatching { CloudTransferService.start(getApplication(), item.id, ownR2 = cloudConnected, destination = saveDestinationName) }
            .onFailure {
                PairLog.e("Could not start cloud save service", it)
                status = it.message ?: "Could not start cloud save"
            }
    }

    /**
     * Cablegram no longer saves web videos on its server (CAB-27): a web title left "saving" or "failed" by that retired
     * path goes back to idle, so it shows no progress and offers no retry.
     */
    private fun clearRetiredWebSaves() {
        store.list().filter { it.sourceKind == "web" && (it.transferStatus == TRANSFER_SAVING || it.transferStatus == TRANSFER_FAILED) }
            .forEach { item -> store.updateItem(item.id) { it.copy(transferStatus = TRANSFER_IDLE, webTransferJobId = null, webTransferError = null) } }
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

    /** Whether the TV's result (or the lack of a way to read it) lets the phone update its state. */
    private fun Outcome.applies() = this == Outcome.Confirmed || this == Outcome.Unconfirmable

    /** A command the control service accepted. [latest] is false once a newer command was sent. */
    private data class SentCommand(val tvName: String, val outcome: Outcome, val latest: Boolean)

    /** Counts sends, so only the newest command narrates [remoteStatus]. */
    private var commandGeneration = 0L

    /**
     * Sends a command and waits for the TV's outcome, narrating it in [remoteStatus] while it is the
     * newest command. Returns null when the control service did not accept the command. [onAccepted]
     * runs once the service accepted it, before the wait, and only while it is still the newest.
     */
    private suspend fun sendCommand(
        command: String,
        videoId: String? = null,
        arguments: JsonObject = buildJsonObject {},
        wait: ConfirmationWait = ConfirmationWait.Control(),
        onAccepted: ((tvName: String) -> Unit)? = null,
        castRoute: CastRoute? = null,
        castTitle: LibraryItem? = null,
    ): SentCommand? {
        val generation = ++commandGeneration
        fun report(text: String) { if (generation == commandGeneration) remoteStatus = text }
        // A finished command's result stays long enough to read, then clears if nothing newer replaced it.
        fun reportFinal(text: String) {
            report(text)
            viewModelScope.launch {
                delay(REMOTE_STATUS_LINGER_MS)
                if (generation == commandGeneration && remoteStatus == text) remoteStatus = null
            }
        }
        report("Sending…")
        val client = catalog()
        val castTarget = if (castRoute != null) pairing.tvs.lastOrNull()?.deviceId else null
        if (castRoute != null && castTarget == null) return null.also { reportFinal("Choose a paired TV in Settings.") }
        val payload = if (castRoute != null && castTitle != null) buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("castLaunch", true)
            put("title", castTitle.title.take(160))
            put("senderName", pairing.displayName.take(80).ifBlank { "Your phone" })
        } else arguments
        var sent = when (val result = CastSession.send(pairing, client, command, videoId, payload, householdTvsCache, castTarget)) {
            is CastSession.Result.Sent -> result
            is CastSession.Result.Failed -> return null.also { reportFinal(result.message) }
        }
        if (sent.switchedFrom != null) {
            // The command went to the TV that is on, which is now the current TV.
            tvs = pairing.tvs
            tvName = pairing.tvName
        }
        report(when {
            sent.switchedFrom != null -> "${sent.switchedFrom} isn't on, so ${if (command == "play" && videoId != null) "starting" else "sending"} on ${sent.tvName}…"
            command == "play" && videoId != null -> "Starting on ${sent.tvName}…"
            else -> "Sent to ${sent.tvName}…"
        })
        // Only the newest command may touch what the screens show.
        if (generation == commandGeneration) onAccepted?.invoke(sent.tvName)
        if (castRoute != null && castTitle != null && castTarget != null) {
            try {
                lastCastDeviceStatus = null
                val status = castSender?.load(castRoute.deviceId, sent.commandId, castTarget,
                    castTitle.id, castTitle.title, castTitle.posterUrl) ?: lastCastDeviceStatus
                val household = client.householdTvs(sent.token)
                if (household != null) householdTvsCache = household
                // A normal first LOAD must also persist its route mapping when discovery began
                // before the household cache was populated. Fresh membership still bounds the hint.
                if (status != null && household?.any { it.id == status.deviceId } == true) {
                    pairing.attachCastDeviceId(status.deviceId, castRoute.deviceId)
                    tvs = pairing.tvs
                }
                val retry = wrongTvRetry(status, pairing.tvs, household.orEmpty().map { it.id }.toSet(), false)
                if (status?.wrongTv == true) {
                    if (retry == null) return null.also { reportFinal("That Cast device isn't your selected TV. Choose a paired TV.") }
                    sent = when (val result = CastSession.send(pairing, client, command, videoId, payload, household, retry.deviceId)) {
                        is CastSession.Result.Sent -> result
                        is CastSession.Result.Failed -> return null.also { reportFinal(result.message) }
                    }
                    val retried = castSender?.load(castRoute.deviceId, sent.commandId, retry.deviceId!!,
                        castTitle.id, castTitle.title, castTitle.posterUrl)
                    if (retried?.wrongTv == true) return null.also { reportFinal("TV changed. Choose a TV and try again.") }
                    selectTv(retry)
                    pairing.attachCastDeviceId(retry.deviceId!!, castRoute.deviceId)
                    tvs = pairing.tvs
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return null.also { reportFinal(error.message ?: "Could not open the TV. Try again.") }
            }
        }
        val outcome = CastSession.confirm(client, sent, wait)
        reportFinal(when (outcome) {
            Outcome.Confirmed -> "Done on ${sent.tvName}"
            is Outcome.Rejected -> rejectionMessage(outcome.reason, sent.tvName)
            Outcome.TimedOut -> "${sent.tvName} didn't confirm. Check the TV."
            Outcome.Unconfirmable -> "Sent (not confirmed by TV)"
        })
        return SentCommand(sent.tvName, outcome, latest = generation == commandGeneration)
    }

    private fun publishCast() {
        CastSession.update(nowPlayingState?.let { Cast(it.id, it.title, tvName, pausedState) })
    }

    override fun onCleared() {
        castRoutesJob?.cancel()
        castSender?.release()
        super.onCleared()
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
