package app.cablegram.phone

import android.os.SystemClock
import app.cablegram.phone.cast.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class PhoneViewModel(application: Application, private val savedState: SavedStateHandle) : AndroidViewModel(application) {
    private val supportedLanguages: Set<String> by lazy {
        getApplication<Application>().resources.getStringArray(R.array.ui_languages).toSet()
    }
    /** One configuration context per language: status text and composition getters ask for it often. */
    @Volatile private var uiCache: Pair<String, android.content.res.Resources>? = null

    private fun uiResources(): android.content.res.Resources {
        val app = getApplication<Application>()
        val language = phoneUiLanguage(appLanguageTags(PhoneLanguageOverride.tag, app.resources.configuration.languageTags()), supportedLanguages)
        uiCache?.let { (cached, resources) -> if (cached == language) return resources }
        val configuration = android.content.res.Configuration(app.resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(language))
        }
        return app.createConfigurationContext(configuration).resources.also { uiCache = language to it }
    }

    private fun uiText(id: Int, vararg args: Any?): String = uiResources().getString(id, *args)
    private val store = LibraryStore(application)
    private val indexer = MediaIndexer(application)
    private fun catalog() = CatalogClient(pairing.apiBaseUrl, pairing)
    private val pairing = PairingStore(application)
    private val commands = CommandQueue(application)
    private var languageSyncJob: Job? = null
    private var languageSyncTarget: Pair<String, String>? = null
    private var syncedLanguage: Pair<String, String>? = null

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
        if (visible) followTvPlayback()
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

    /** The first-run welcome tour shows once per install, before the library; Settings can bring it back. */
    var welcomeTourOpen by mutableStateOf(!pairing.welcomeTourSeen)
        private set

    /** Settings' "Subtitle accounts" page; hoisted so the welcome tour can open it. */
    var subtitleAccountsOpen by mutableStateOf(false)

    fun closeWelcomeTour() {
        pairing.welcomeTourSeen = true
        welcomeTourOpen = false
    }

    fun replayWelcomeTour() {
        welcomeTourOpen = true
    }

    /** A tour page's setup button: leave the tour and land on the screen that does the setup. */
    fun startFromTour(step: TourStep) {
        closeWelcomeTour()
        when (step) {
            TourStep.Tv -> startAddTv()
            TourStep.Telegram -> { tab = PhoneTab.Browse; openTelegramSheet() }
            TourStep.Drive -> { tab = PhoneTab.Storage; cloudSheet = CloudSheet.Manage }
            TourStep.Subtitles -> { tab = PhoneTab.Settings; subtitleAccountsOpen = true }
            TourStep.Welcome, TourStep.Ready -> tab = PhoneTab.Browse
        }
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
        status = uiText(R.string.ui_changes_saved_16bf14)
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
    // Declared before init, which loads the sign-in options when the app starts signed out.
    val signIn = SignInController(client = ::catalog, signedIn = ::completeSignIn, resources = ::uiResources)
    /** The account must accept new Terms before going on (spec 007 US4). */
    var termsRequired by mutableStateOf(false)
        private set
    var tvs by mutableStateOf(pairing.tvs)
        private set
    var busy by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf<String?>(null)
        private set
    var browseSource by mutableStateOf(BrowseSource.None)
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
            if (value != null) followTvPlayback()
            // CAB-57: the "Playing … on TV" line belongs to the title; it goes when nothing plays any more.
            else if (status != null && status == nowPlayingStatus) status = null
        }
    /** The status line written when the current title started, so it can be cleared with the title. */
    private var nowPlayingStatus: String? = null
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
    private val subtitleCredentialStore = SubtitleCredentialStore(getApplication())
    private val subtitleSessions = mutableSetOf<LocalSubtitleDiscovery>()
    var subtitleCredentialRevision by mutableStateOf(0)
        private set
    private fun subtitleUser(): String? = pairing.accountToken?.let { token -> runCatching {
        val payload = android.util.Base64.decode(token.split('.')[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        org.json.JSONObject(payload.toString(Charsets.UTF_8)).getString("sub").takeIf { it.isNotBlank() }
    }.getOrNull() }
    fun subtitleProviderConfigured(provider: String): Boolean = subtitleUser()?.let { subtitleCredentialStore.read(it, provider) != null } ?: false
    private fun cancelSubtitleSessions() {
        subtitleSessions.toList().forEach { it.close() }; subtitleSessions.clear()
        subtitleCredentialRevision++
    }
    fun saveSubtitleProvider(provider: String, credentials: PersonalSubtitleCredentials) {
        val user = subtitleUser() ?: throw LocalSubtitleError("sign_in_required")
        cancelSubtitleSessions(); subtitleCredentialStore.save(user, provider, credentials)
        subtitleCredentialRevision++
    }
    fun removeSubtitleProvider(provider: String) {
        val user = subtitleUser() ?: return
        cancelSubtitleSessions(); subtitleCredentialStore.remove(user, provider)
        subtitleCredentialRevision++
    }
    fun newSubtitleSession(): LocalSubtitleDiscovery {
        val user = subtitleUser() ?: return LocalSubtitleDiscovery(emptyMap())
        val configured = listOf("subdl", "opensubtitles").mapNotNull { provider -> subtitleCredentialStore.read(user, provider)?.let { provider to it } }.toMap()
        lateinit var session: LocalSubtitleDiscovery
        session = LocalSubtitleDiscovery(configured, onClose = { subtitleSessions.remove(session) })
        subtitleSessions += session
        return session
    }
    fun removeSavedSubtitle(item: LibraryItem) {
        val token = accountTokenOrNull() ?: return
        viewModelScope.launch {
            try {
                val tracks = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<SavedSubtitleList>(subtitleRequest("playback/item/${item.id}", token)).subtitles
                tracks.forEach { if (it.trackId.isNotEmpty()) subtitleRequest(it.trackId, token, "DELETE") }
                refreshSubtitleStatus(item)
            } catch (_: Exception) { status = uiText(R.string.ui_subtitle_couldn_t_be_removed_try_again_e1a4f7) }
        }
    }
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
        if (item.sourceKind != "telegram") throw VideoUnavailable(uiText(R.string.ui_this_video_isn_t_stored_on_this_phone_so_it_can_t_be_c_2ce4b8))
        val key = item.telegramFileKey?.removePrefix("tgfile:")
            ?: throw VideoUnavailable(uiText(R.string.ui_pull_to_refresh_your_library_once_so_this_telegram_vid_c06d0f))
        if (!PhoneTelegram.configured) throw VideoUnavailable(uiText(R.string.ui_telegram_isn_t_set_up_in_this_version_of_the_app_4d3c36))
        val file = runCatching { telegramMedia.resolve(key) }.getOrNull()
            ?: throw VideoUnavailable(uiText(R.string.ui_telegram_didn_t_provide_this_video_check_that_telegram_4bfa84))
        return TelegramMediaInput(telegramMedia, file)
    }
    /** Reads what the TV will use for this title, so the detail screen can say which subtitle is saved. */
    fun refreshSubtitleStatus(item: LibraryItem) {
        val token = accountTokenOrNull() ?: return
        viewModelScope.launch {
            val label = runCatching {
                val tracks = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<SavedSubtitleList>(catalog().subtitleRequest("playback/item/${item.id}", token)).subtitles
                tracks.firstOrNull()?.let { languageName(it.language, resources = uiResources()) }
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
    var collectionsDialogOpen by mutableStateOf(false)
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
            status = uiText(R.string.ui_enter_a_valid_http_or_https_server_address_cbfe30)
            return
        }
        pairing.apiBaseUrl = value.trim()
        apiBaseUrl = pairing.apiBaseUrl
        PairLog.i("Phone API URL set to $apiBaseUrl")
    }

    fun updateTunnelUrl(value: String) {
        if (value.isNotBlank() && !isValidServerUrl(value)) {
            status = uiText(R.string.ui_enter_a_valid_tunnel_address_or_leave_it_empty_773859)
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
        if (needsAccount) loadSignInOptions() else checkTerms()
        refreshPendingApprovals()
        // One poller serves the notification and this list: ApprovalWatcher, in LanLibraryService.
        viewModelScope.launch { PrivateApprovals.pending.collect { pendingApprovals = it } }
        // CAB-51: redraw when anything writes the library, e.g. a background save that started and finished between
        // two polls. collectLatest with a short pause folds the progress writes of a running save into one redraw.
        viewModelScope.launch {
            store.revision.drop(1).collectLatest {
                delay(250)
                refresh()
            }
        }
        refreshPhoneIps()
        viewModelScope.launch { ads = catalog().ads() }
        refreshStorage()
        recoverSharedImports()
        viewModelScope.launch(Dispatchers.IO) { store.discardPendingWebImports() }
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
                        status = uiText(R.string.ui_your_session_expired_sign_in_again_to_keep_this_tv_con_46a834)
                        loadSignInOptions()
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
        private const val SAVED_DELETING_ACCOUNT = "deleting_account"
        private const val SAVED_DELETION_CODE_SENT_TO = "deletion_code_sent_to"
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
            PrivateApprovals.pending.value = runCatching { catalog().fetchAllPendingApprovals(token) }.getOrDefault(emptyList())
        }
    }

    /** T075: approve / deny a pending request from inside the phone app. */
    fun respondToApproval(approval: PendingApproval, approve: Boolean) {
        val token = pairing.accountToken ?: return
        val app = getApplication<Application>()
        PrivateApprovals.cancelNotification(app, approval.attemptId)
        PrivateApprovals.pending.value = PrivateApprovals.pending.value.filterNot { it.attemptId == approval.attemptId }
        viewModelScope.launch {
            val profile = approval.kind == ApprovalKind.Profile
            val what = approval.title ?: uiText(R.string.ui_the_profile_31580c)
            runCatching {
                check(catalog().answerApproval(token, approval, approve)) { uiText(R.string.ui_the_request_was_not_accepted_refresh_and_try_again_35aa7e) }
            }.onSuccess {
                status = when {
                    profile && approve -> uiText(R.string.ui_can_open_3f1941, approval.tvName ?: uiText(R.string.ui_the_tv_adfd45), what)
                    profile -> uiText(R.string.ui_won_t_open_b1de63, approval.tvName ?: uiText(R.string.ui_the_tv_adfd45), what)
                    approve -> uiText(R.string.ui_playback_allowed_for_this_attempt_2fcc98)
                    else -> uiText(R.string.ui_playback_denied_270488)
                }
            }.onFailure {
                val reason = it.message ?: uiText(R.string.ui_try_again_5b5ded)
                status = when {
                    profile && approve -> uiText(R.string.ui_couldnt_allow_profile, what, reason)
                    profile -> uiText(R.string.ui_couldnt_deny_profile, what, reason)
                    approve -> uiText(R.string.ui_couldnt_allow_playback, reason)
                    else -> uiText(R.string.ui_couldnt_deny_playback, reason)
                }
                refreshPendingApprovals()
            }
        }
    }

    fun refreshStorage() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { storage = catalog().storageStatus(token) }
        syncDeviceLanguage()
        loadProfiles()
    }

    /** Retry failures on the next foreground refresh, including after account changes. */
    fun syncDeviceLanguage() {
        val token = pairing.accountToken ?: return
        val tag = phoneLanguageTag(getApplication<Application>().resources.configuration.languageTags(), supportedLanguages)
        val target = token to tag
        if (target == syncedLanguage || (target == languageSyncTarget && languageSyncJob?.isActive == true)) return
        languageSyncJob?.cancel()
        languageSyncTarget = target
        languageSyncJob = viewModelScope.launch {
            if (catalog().syncDeviceLanguage(token, tag)) syncedLanguage = target
        }
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
            r2ConnectError = uiText(R.string.ui_pair_a_tv_first_so_cablegram_can_attach_storage_to_thi_cf1a07)
            return
        }
        viewModelScope.launch {
            busy = true
            r2ConnectError = null
            try {
                val result = catalog().connectR2(token, r2AccountIdFrom(accountId) ?: accountId.trim().lowercase(), bucket.trim(), accessKeyId.trim(), secret.trim())
                if (result.error == null) {
                    status = uiText(R.string.ui_connected_to_your_r2_storage_eb0a33)
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

    /** Where a save goes, in the words on every save sheet: the connected provider (saves need one, CAB-38). */
    val saveDestinationName: String get() = saveDestination(storage, resources = uiResources())

    /** True while the "tick the box" note is on screen, before Google's sign-in opens. */
    var googleSignInNoteOpen by mutableStateOf(false)

    /** Asks the server for Google's sign-in URL and hands it to [open] (a Custom Tab); the link back lands in [applyStorageReturn]. */
    fun connectGoogle(open: (String) -> Unit) {
        val token = pairing.accountToken ?: run {
            googleConnectError = uiText(R.string.ui_pair_a_tv_first_so_cablegram_can_attach_storage_to_thi_cf1a07)
            return
        }
        viewModelScope.launch {
            busy = true
            googleConnectError = null
            try {
                val start = catalog().connectGoogle(token)
                val url = start.authorizeUrl
                if (url != null) {
                    status = uiText(R.string.ui_finish_signing_in_with_google_then_come_back_ecbd97)
                    open(url)
                } else {
                    googleConnectError = googleStartMessage(start.error ?: "failed", resources = uiResources())
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
            val message = storageReturnMessage(ret, storage, resources = uiResources())
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
            if (connection?.provider == ret.provider && connection.status == "active") status = storageReturnMessage(ret, confirmed, resources = uiResources())
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
                    status = disconnectMessage(provider, named, resources = uiResources())
                    refreshStorage()
                } else {
                    status = uiText(R.string.ui_could_not_disconnect_storage_a23494)
                }
            } finally {
                busy = false
            }
        }
    }

    private fun refreshPhoneIps() {
        phoneIps = localIpv4Addresses()
        if (lanAddress == null) {
            localIpv4()?.let { lanAddress = uiText(R.string.ui_message_069c89, it, LanLibraryServer.PORT) }
        }
    }

    // ---- Sign-in (spec 007): Google or an email code, Create your account with the Terms ----

    fun loadSignInOptions() {
        viewModelScope.launch { signIn.load() }
    }

    fun requestSignInCode(email: String) {
        // "Your account was deleted." has done its job once the person starts signing in again.
        status = null
        viewModelScope.launch { signIn.requestCode(email) }
    }

    fun resendSignInCode() {
        viewModelScope.launch { signIn.resendCode() }
    }

    fun verifySignInCode(code: String) {
        viewModelScope.launch { signIn.verifyCode(code) }
    }

    fun createAccount(name: String) {
        viewModelScope.launch { signIn.createAccount(name) }
    }

    /** Shows Google's sign-in sheet; [activity] must be the Activity, which Credential Manager needs. */
    fun signInWithGoogle(activity: android.content.Context) {
        val clientId = signIn.methods?.google?.clientId ?: return
        status = null
        viewModelScope.launch {
            when (val result = googleIdToken(activity, clientId)) {
                is GoogleIdResult.Token -> signIn.google(result.idToken, result.nonce)
                is GoogleIdResult.Failed -> signIn.message = result.message
                GoogleIdResult.Cancelled -> Unit
            }
        }
    }

    fun acceptTerms() {
        val token = pairing.accountToken ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val docs = catalog().legalDocuments()
                if (docs == null) {
                    status = uiText(R.string.ui_can_t_reach_cablegram_check_your_connection_0f7f38)
                    return@launch
                }
                val failed = catalog().acceptTerms(token, docs)
                if (failed == null) {
                    termsRequired = false
                    status = null
                } else {
                    status = failed.text(uiResources())
                    if (failed.error == "terms_outdated") signIn.load()
                }
            } finally {
                busy = false
            }
        }
    }

    /** New Terms are checked on start and after each sign-in; offline (null) never blocks the app. */
    private fun checkTerms() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            if (catalog().termsAcceptanceRequired(token) == true) {
                signIn.load()
                termsRequired = true
            }
        }
    }

    /** The server returned an account (Google, an email code, or Create your account). */
    private suspend fun completeSignIn(result: PhonePairResponse) {
        pairing.accountToken = result.token
        syncDeviceLanguage()
        pairing.refreshToken = result.refreshToken
        householdName = result.displayName.orEmpty()
        pairing.displayName = householdName
        viewModelScope.launch(Dispatchers.IO) { store.discardPendingWebImports() }
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
            status = if (needsName) uiText(R.string.ui_choose_an_account_name_to_continue_8eba6d) else uiText(R.string.ui_signed_in_again_00397a)
            addingTv = false
            pairing.tvs.forEach { publishLanAddress(it.pin) }
            syncLibrary()
        } else {
            status = if (needsName) uiText(R.string.ui_choose_an_account_name_to_continue_8eba6d) else uiText(R.string.ui_signed_in_scan_the_tv_pin_to_pair_14c21b)
            // A TV scanned before signing in goes straight to "Is this your TV?" (CAB-62); otherwise pairing comes next.
            addingTv = !needsName && pendingPair == null
            // Bring back the household's titles even before a TV is paired.
            syncLibrary()
        }
        if (setAside) status = uiText(R.string.ui_signed_in_the_previous_account_s_library_is_kept_aside_45bf31)
        checkTerms()
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
            status = uiText(R.string.ui_enter_the_6_digit_pin_shown_on_the_tv_or_tap_refresh_c_52ae0e)
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
                val client = catalog()
                val result = client.pairPhone(parsed.first, pairing.accountToken, trust)
                if (result == null) {
                    status = when (client.lastPairError) {
                        // CAB-62: the code often runs out while a new person creates their account.
                        "pin_expired" -> uiText(R.string.ui_that_code_expired_scan_the_new_code_on_the_tv_or_type__1c6e7a)
                        "invalid_pin" -> uiText(R.string.ui_that_code_didn_t_match_check_the_code_on_the_tv_and_tr_630194)
                        else -> uiText(R.string.ui_couldn_t_connect_the_tv_check_your_connection_and_try__c92806)
                    }
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
                // Pairing the same TV again replaces its old record on the server; drop it here now, not a minute later.
                syncHouseholdTvs()
                LanLibraryService.start(getApplication())
                // T075 / R-5: pairing may happen mid-session; start (or refresh) the
                // private-playback approval watcher together with the LAN server.
                publishLanAddress(parsed.first)
                syncLibrary()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = uiText(R.string.ui_could_not_connect_check_your_connection_and_try_the_tv_d77e1c)
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
                status = uiText(R.string.ui_sign_in_first_then_name_the_household_57a8fb)
                return@launch
            }
            busy = true
            status = uiText(R.string.ui_saving_23e392)
            try {
                val saved = catalog().setDisplayName(token, trimmed)
                if (!saved) {
                    status = uiText(R.string.ui_could_not_save_that_name_check_the_api_server_in_setti_1d5712)
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
                status = uiText(R.string.ui_signing_out_of_telegram_35bc7f, tv.name)
                LanLibraryService.start(getApplication())
            } else {
                status = uiText(R.string.ui_couldn_t_reach_cablegram_check_your_connection_and_try_de82d8)
            }
        }
    }

    /** Extends a temporary TV's end time by a day, or turns direct Telegram on or off for it. */
    fun updateTvTrust(tv: PairedTv, trust: TvTrust) {
        val token = pairing.accountToken ?: return
        val id = tv.deviceId ?: return
        viewModelScope.launch {
            if (catalog().setTvTrust(token, id, trust)) syncHouseholdTvs()
            else status = uiText(R.string.ui_couldn_t_update_direct_telegram_needs_a_link_older_tha_894844, tv.name)
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
        subtitleFlowOpen = false
        subtitleUser()?.let { subtitleCredentialStore.clear(it) }
        cancelSubtitleSessions()
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
        PhoneLanguageOverride.set(null)
        selectedId = null
        tab = PhoneTab.Library
        addingTv = false
        needsName = false
        needsAccount = true
        termsRequired = false
        signIn.reset()
        loadSignInOptions()
    }

    fun signOut() {
        if (busy) return
        cancelSubtitleSessions()
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
                status = uiText(R.string.ui_signed_out_your_videos_stay_on_this_phone_73ffd6)
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
    var deletingAccount by mutableStateOf(savedState.get<Boolean>(SAVED_DELETING_ACCOUNT) ?: false)
        private set

    val accountDeletion = AccountDeletionController(
        token = { pairing.accountToken },
        delete = { token, code -> catalog().deleteAccount(token, code) },
        wipeLocal = ::wipeAfterAccountDeletion,
    )

    /** Where the deletion code went; null until one was sent. */
    var deletionCodeSentTo by mutableStateOf(savedState.get<String>(SAVED_DELETION_CODE_SENT_TO))
        private set
    var deletionCodeFailed by mutableStateOf(false)
        private set

    fun openDeleteAccount() {
        accountDeletion.reset()
        rememberDeletionCodeSentTo(null)
        deletionCodeFailed = false
        rememberDeletingAccount(true)
    }

    // The open Delete account screen and where its code went survive Android ending this process while the person reads
    // the code in their mail app. The code itself is on the server and still valid.
    private fun rememberDeletingAccount(open: Boolean) {
        deletingAccount = open
        savedState[SAVED_DELETING_ACCOUNT] = open
    }

    private fun rememberDeletionCodeSentTo(address: String?) {
        deletionCodeSentTo = address
        savedState[SAVED_DELETION_CODE_SENT_TO] = address
    }

    /** Mails a sign-in code to the account's address; it confirms the deletion (spec 007 FR-015). */
    fun requestDeletionCode() {
        val token = pairing.accountToken ?: return
        if (busy) return
        viewModelScope.launch {
            busy = true
            try {
                val email = catalog().accountEmail(token)
                val failed = email?.let { catalog().requestSignInCode(it, forDeletion = true) }
                deletionCodeFailed = email == null || failed != null
                if (!deletionCodeFailed) rememberDeletionCodeSentTo(email)
            } finally {
                busy = false
            }
        }
    }

    fun closeDeleteAccount() {
        if (accountDeletion.running) return
        accountDeletion.reset()
        rememberDeletingAccount(false)
        rememberDeletionCodeSentTo(null)
    }

    fun deleteAccount(code: String) {
        viewModelScope.launch { accountDeletion.submit(code) }
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
        rememberDeletingAccount(false)
        rememberDeletionCodeSentTo(null)
        busy = false
        status = app.getString(R.string.account_deleted_message)
        PairLog.i("Account deleted; phone signed out")
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
    var telegramCommunityChannels by mutableStateOf<app.cablegram.telegram.CommunityChannels?>(null)
        private set
    var telegramLibraryBots by mutableStateOf<app.cablegram.telegram.LibraryBotRecommendations?>(null)
        private set
    var telegramBotHubOnly by mutableStateOf(false)
        private set
    var telegramFolderBusy by mutableStateOf(false)
        private set
    var telegramFolderMessage by mutableStateOf<String?>(null)
        private set
    private var telegramFolderJob: Job? = null
    private var telegramLibraryBotsJob: Job? = null
    private var telegramCommunityLibraryId: Long? = null
    private var telegramCommunityUserId: Long? = null
    private var telegramCommunitySession: app.cablegram.telegram.TelegramSession? = null
    private var telegramCommunitySaveJob: Job? = null

    private fun prepareCommunityChannels(session: app.cablegram.telegram.TelegramSession, userId: Long) {
        val chatId = telegramLink?.chatId ?: return
        if (telegramCommunityUserId == userId && telegramCommunityLibraryId == chatId && telegramCommunitySession === session) return
        telegramLibraryBotsJob?.cancel()
        telegramCommunityLibraryId = chatId
        telegramCommunitySaveJob?.cancel()
        telegramCommunityUserId = userId
        telegramCommunitySession = session
        telegramLibraryBots = if (PhoneTelegram.libraryBotsCompleted(getApplication(), userId, chatId)) null
        else createLibraryBotReview(session, userId, chatId, reviewAll = false)
        telegramCommunityChannels = if (PhoneTelegram.communityChannelsCompleted(getApplication(), userId)) null
        else app.cablegram.telegram.CommunityChannels(session.api) {
            PhoneTelegram.completeCommunityChannels(getApplication(), userId)
        }
    }

    private fun createLibraryBotReview(
        session: app.cablegram.telegram.TelegramSession,
        userId: Long,
        chatId: Long,
        reviewAll: Boolean,
    ) = app.cablegram.telegram.LibraryBotRecommendations(
        api = session.api,
        libraryChatId = chatId,
        handledBotIds = if (reviewAll) emptySet() else PhoneTelegram.handledLibraryBots(getApplication(), userId, chatId),
        onDecision = { PhoneTelegram.recordLibraryBotDecision(getApplication(), userId, chatId, it) },
        onCompleted = { PhoneTelegram.completeLibraryBots(getApplication(), userId, chatId) },
    )

    /** Explicit checks always fetch current recommendations and let users reconsider skipped bots. */
    fun checkTelegramBotHub() {
        val link = telegramLink?.takeIf { it.linked } ?: return
        val userId = link.userId ?: return
        val chatId = link.chatId ?: return
        if (telegramHealth != TelegramHealth.Ok || !PhoneTelegram.wasLinked(getApplication())) return
        val session = PhoneTelegram.session(getApplication())
        val ready = session.state.value as? app.cablegram.telegram.TelegramState.Ready
        if (ready?.user?.id != userId) {
            openTelegramSheet()
            return
        }
        val previous = telegramLibraryBotsJob
        previous?.cancel()
        telegramLibraryBotsJob = viewModelScope.launch {
            // Finish cancellation before discovery replaces the API's verified bot IDs.
            previous?.join()
            if (telegramLink?.userId != userId || telegramLink?.chatId != chatId || PhoneTelegram.existing() !== session) return@launch
            telegramLibraryBots = createLibraryBotReview(session, userId, chatId, reviewAll = true)
            telegramBotHubOnly = true
            telegramMessage = null
            telegramSheetOpen = true
        }
    }

    fun saveTelegramCommunityChannels() {
        val channels = telegramCommunityChannels ?: return
        if (telegramCommunitySaveJob?.isActive == true) return
        telegramCommunitySaveJob = viewModelScope.launch {
            channels.save()
            organizeTelegramFolder()
        }
    }

    fun loadTelegramLibraryBots() {
        val bots = telegramLibraryBots ?: return
        if (telegramLibraryBotsJob?.isActive == true) return
        telegramLibraryBotsJob = viewModelScope.launch { bots.load() }
    }

    fun addRecommendedTelegramBot() {
        val bots = telegramLibraryBots ?: return
        if (telegramLibraryBotsJob?.isActive == true) return
        telegramLibraryBotsJob = viewModelScope.launch {
            val addedBefore = bots.state.value.added
            bots.addCurrent()
            if (bots.state.value.added > addedBefore) organizeTelegramFolder()
        }
    }

    /** Folder failures are retryable and never undo successful channel or bot membership changes. */
    fun organizeTelegramFolder() {
        val link = telegramLink?.takeIf { it.linked } ?: return
        val chatId = link.chatId ?: return
        val userId = link.userId ?: return
        val session = PhoneTelegram.existing() ?: return
        if (telegramHealth != TelegramHealth.Ok || !PhoneTelegram.wasLinked(getApplication())) return
        if ((session.state.value as? app.cablegram.telegram.TelegramState.Ready)?.user?.id != userId) return
        val previous = telegramFolderJob
        previous?.cancel()
        telegramFolderJob = viewModelScope.launch {
            previous?.join()
            telegramFolderBusy = true
            telegramFolderMessage = null
            try {
                val result = withTimeout(30_000) {
                    session.api.organizeCablegramFolder(chatId, PhoneTelegram.handledLibraryBots(getApplication(), userId, chatId).toLongArray())
                }
                telegramFolderMessage = if (result.unlistedBots.isEmpty()) uiText(R.string.ui_cablegram_folder_updated_in_telegram_acc08b)
                else uiText(R.string.ui_cablegram_folder_updated_in_telegram_to_include_open_t_515a3b, result.unlistedBots.joinToString(", "))
            } catch (_: TimeoutCancellationException) {
                telegramFolderMessage = uiText(R.string.ui_updating_the_telegram_folder_took_too_long_tap_organiz_34b802)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairLog.w("Telegram folder update failed", e)
                telegramFolderMessage = uiText(R.string.ui_couldn_t_update_the_telegram_folder_check_your_connect_15069c)
            } finally {
                telegramFolderBusy = false
            }
        }
    }

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
                organizeTelegramFolder()
                status = if (library.created) uiText(R.string.ui_created_a_new_cablegram_library_channel_in_telegram_aef43c)
                else uiText(R.string.ui_found_your_cablegram_library_channel_in_telegram_again_a824e9)
            } catch (e: Exception) {
                status = uiText(R.string.ui_couldn_t_create_the_channel_9f0c99, e.message ?: uiText(R.string.ui_unknown_error_3e4443))
            }
        }
    }

    /** Telegram ended this phone's session: sign in again without disconnecting the household. */
    fun signInToTelegramAgain() {
        telegramHealth = TelegramHealth.Unknown
        clearTelegramRecommendations()
        // The old session is dead; start clean, then finish like a first link (same account → same link).
        PhoneTelegram.forget(getApplication())
        connectTelegram()
    }

    /** A first connection needs consent; an existing session resumes its unfinished household link. */
    fun openTelegramSheet() {
        if (!PhoneTelegram.configured) return
        telegramMessage = null
        telegramSheetOpen = true
        // TDLib outlives this ViewModel. After reopening the app, Ready alone does not mean the
        // household link was saved: the new ViewModel must also resume the finishing job.
        if (PhoneTelegram.existing() != null || PhoneTelegram.wasLinked(getApplication())) connectTelegram()
    }

    /** The owner chose Continue on "Before you connect": start TDLib and the sign-in steps. */
    fun connectTelegram() {
        if (!PhoneTelegram.configured) return
        telegramMessage = null
        telegramSheetOpen = true
        telegramStarted = true
        if (telegramFinishJob?.isActive == true) return
        val session = PhoneTelegram.session(getApplication())
        if (telegramLink?.linked == true && telegramHealth == TelegramHealth.Ok && PhoneTelegram.wasLinked(getApplication())) {
            telegramLink?.userId?.let { prepareCommunityChannels(session, it) }
            return
        }
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
            telegramMessage = uiText(R.string.ui_this_phone_isn_t_registered_with_your_household_yet_co_bd64f3)
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
                    if (started == null) telegramMessage = uiText(R.string.ui_telegram_didn_t_start_check_your_connection_and_try_ag_f28228)
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
                telegramMessage = uiText(R.string.ui_didn_t_answer_open_cablegram_on_the_tv_and_check_setti_5aa87a, tv.name) +
                    uiText(R.string.ui_or_connect_with_your_phone_number_instead_216b05)
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
            withTimeout(60_000) {
                val library = session.openLibrary(
                    knownChatId = telegramLink?.chatId,
                    allowCreate = true,
                    logoPath = PhoneTelegram.logoPath(getApplication()),
                ) ?: error("Could not open the Cablegram library channel")
                telegramChannelCreated = library.created
                val result = catalog().putTelegramLink(token, user.id, user.displayName, library.chatId, pairing.phoneDeviceId)
                if (result.conflictName != null) {
                    telegramMessage = uiText(R.string.ui_this_household_already_uses_the_telegram_account_of_7d8c2e, result.conflictName) +
                        uiText(R.string.ui_disconnect_it_first_or_sign_in_with_that_account_693723)
                    session.logOut()
                    PhoneTelegram.forget(getApplication())
                } else {
                    telegramLink = result.link
                    telegramHealth = TelegramHealth.Ok
                    PhoneTelegram.setLinked(getApplication(), true)
                    prepareCommunityChannels(session, user.id)
                    organizeTelegramFolder()
                    status = uiText(R.string.ui_telegram_connected_videos_you_share_into_cablegram_lib_8c4c5d)
                }
            }
        } catch (e: TimeoutCancellationException) {
            telegramMessage = uiText(R.string.ui_setting_up_your_telegram_library_took_too_long_check_y_927028)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            telegramMessage = uiText(R.string.ui_signed_in_to_telegram_but_setting_up_the_library_faile_297f32, e.message ?: uiText(R.string.ui_unknown_error_3e4443))
        } finally {
            telegramFinishing = false
        }
    }

    /** Closing half-way keeps the step: reopening continues where the user left off. */
    fun closeTelegramSheet() {
        telegramBotHubOnly = false
        telegramSheetOpen = false
        telegramMessage = null
    }

    /** Cancel operations tied to the old Telegram session before replacing or signing it out. */
    private fun clearTelegramRecommendations() {
        telegramFolderJob?.cancel()
        telegramFolderMessage = null
        telegramFolderBusy = false
        telegramBotHubOnly = false
        telegramLibraryBotsJob?.cancel()
        telegramLibraryBots = null
        telegramCommunityLibraryId = null
        telegramCommunitySaveJob?.cancel()
        telegramCommunityChannels = null
        telegramCommunityUserId = null
        telegramCommunitySession = null
    }

    /** Logs this phone out of Telegram and deletes the local TDLib database, files and key. */
    private fun wipeTelegramLocally() {
        clearTelegramRecommendations()
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
                status = uiText(R.string.ui_couldn_t_disconnect_telegram_check_your_connection_and_7a7e1e)
                return@launch
            }
            // End every TV's Telegram session while this phone can still do it: signing the phone out first
            // would leave TVs that are off or unplugged signed in to the account (US7, FR-010).
            val unended = withContext(Dispatchers.IO) {
                runCatching { TelegramTvApprovalWatcher.endDueSessions(getApplication(), catalog(), token) }
                catalog().dueTvTelegramSessions(token)?.size ?: -1
            }
            wipeTelegramLocally()
            status = if (unended == 0) uiText(R.string.ui_telegram_disconnected_your_tvs_are_signed_out_of_teleg_5f064d)
            else uiText(R.string.ui_telegram_disconnected_some_tvs_may_still_be_signed_in__3b14d3)
        }
    }

    fun loadRelayUsage() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { catalog().relayUsage(token, pairing.phoneDeviceId)?.let { relayUsage = it } }
    }

    /** Profile lock: TVs ask a phone before opening a profile. Null until the server answers. */
    var profileLock by mutableStateOf<Boolean?>(null)
        private set

    /** CAB-67: minutes an approved profile stays open without use on a TV shared with other accounts. */
    var sharedTvIdleMinutes by mutableStateOf<Int?>(null)
        private set

    fun loadProfileLock() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            catalog().profileLock(token)?.let { profileLock = it }
            catalog().sharedTvIdleMinutes(token)?.let { sharedTvIdleMinutes = it }
        }
    }

    fun updateSharedTvIdleMinutes(minutes: Int) {
        val token = pairing.accountToken ?: return
        val before = sharedTvIdleMinutes
        sharedTvIdleMinutes = minutes
        viewModelScope.launch {
            val saved = catalog().setSharedTvIdleMinutes(token, minutes)
            sharedTvIdleMinutes = saved ?: before
            if (saved == null) status = uiText(R.string.ui_couldn_t_change_that_setting_check_your_connection_and_8516e4)
        }
    }

    fun updateProfileLock(on: Boolean) {
        val token = pairing.accountToken ?: return
        val before = profileLock
        profileLock = on
        viewModelScope.launch {
            val saved = catalog().setProfileLock(token, on)
            profileLock = saved ?: before
            if (saved == null) status = uiText(R.string.ui_couldn_t_change_the_profile_lock_check_your_connection_14672d)
        }
    }

    fun loadProfiles() {
        val token = pairing.accountToken ?: return
        viewModelScope.launch { catalog().profiles(token)?.let(::applyProfiles) }
    }

    /** The account owner's profile language also sets the language of this app. */
    private fun applyProfiles(list: List<HouseholdProfile>) {
        profiles = list
        PhoneLanguageOverride.set(list.firstOrNull { it.sortOrder == 0 }?.uiLanguageOverride)
    }

    fun updateProfileLanguage(profile: HouseholdProfile, language: String?) {
        if (busy || (language != null && language !in supportedLanguages)) return
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            busy = true
            try {
                if (catalog().setProfileLanguage(token, profile.id, language)) {
                    catalog().profiles(token)?.let(::applyProfiles)
                    status = uiText(R.string.ui_language_updated_ff0392)
                } else status = uiText(R.string.ui_could_not_update_the_language_check_your_connection_an_ce45e9)
            } finally { busy = false }
        }
    }

    fun deleteProfile(profile: HouseholdProfile) {
        val token = pairing.accountToken ?: return
        viewModelScope.launch {
            busy = true
            val code = catalog().deleteProfile(token, profile.id)
            busy = false
            status = when (code) {
                204 -> uiText(R.string.ui_deleted_afb6f5, profile.name)
                409 -> uiText(R.string.ui_the_account_owner_s_profile_can_t_be_deleted_2f8068)
                null -> uiText(R.string.ui_couldn_t_reach_cablegram_check_your_connection_and_try_de82d8)
                else -> uiText(R.string.ui_couldn_t_delete_right_now_e5be9f, profile.name)
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
            } else status = uiText(R.string.ui_couldn_t_reach_cablegram_to_remove_check_your_connecti_8e61be, current.name)
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
            status = uiText(R.string.ui_tv_removed_scan_a_pin_when_you_want_one_again_ee2752)
        } else {
            // The LAN service keeps serving the other TVs; this makes it refuse the removed one now (CAB-37).
            LanLibraryService.start(getApplication())
            tvName = pairing.tvName
            status = uiText(R.string.ui_removed_this_tv_still_paired_4ec21c, pairing.tvs.size)
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
                    uiText(R.string.ui_could_not_sync_source_availability_863b58)
                }
                // Only reachable phone sources may be reattached by an import.
                // Unavailable titles stay cached and archived on the server.
                val items = present
                for ((index, item) in items.withIndex()) {
                    prepareProgress = (index + 1f) / items.size
                    prepareStep = uiText(R.string.ui_syncing_6c2de6, item.title)
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
                // files the user selected again by fingerprint; keep the rest as "not on this phone".
                val deviceVideos by lazy { store.selectedVideosByFingerprint() }
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
                            title = remote.title ?: uiText(R.string.ui_web_video_7d35b3),
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
                check(!metadataFailed) { uiText(R.string.ui_your_detail_edits_are_saved_on_this_phone_review_any_c_251495) }
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
            status = uiText(R.string.ui_paired_but_this_phone_has_no_wi_fi_address_yet_5134d1)
            return
        }
        lanAddress = uiText(R.string.ui_message_069c89, host, LanLibraryServer.PORT)
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
            announced && tunnel != null -> uiText(R.string.ui_tv_can_use_if_lan_does_not_match_3a3e2d, tunnel)
            announced -> uiText(R.string.ui_this_phone_is_on_the_same_wi_fi_as_the_tv_4eb203, lanAddress)
            else -> uiText(R.string.ui_api_announce_failed_check_the_api_server_in_settings_576e6b)
        }
        status = null
    }

    var sharedImportSucceeded by mutableStateOf<Boolean?>(null)
        private set

    fun importShared(uri: Uri, displayName: String, playNow: Boolean, caption: String? = null) {
        sharedImportSucceeded = null
        viewModelScope.launch {
            busy = true
            status = uiText(R.string.ui_copying_26b256, displayName)
            try {
                persistRead(uri)
                val item = withContext(Dispatchers.IO) { store.importUri(uri, displayName, null) }
                refresh()
                if (importDestination == STORAGE_BOTH && cloudConnected) {
                    beginSaveToCloud(item)
                }
                recentlyAddedIds = listOf(item.id)
                tab = PhoneTab.Library
                status = uiText(R.string.ui_video_added_08c7ee)
                sharedImportSucceeded = true
                syncLibrary()
                if (playNow) playOnTv(store.get(item.id) ?: item)
            } catch (error: Exception) {
                sharedImportSucceeded = false
                status = error.message ?: uiText(R.string.ui_could_not_import_that_file_3e92a3)
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
                uiText(R.string.ui_could_not_finish_importing_the_file_is_no_longer_avail_63e50e, dropped.joinToString { it.displayName })
            } else {
                uiText(R.string.ui_finished_importing_6d8eef, results.filterIsInstance<LibraryStore.SharedImportResult.Imported>().joinToString { it.item.title })
            }
        }
    }

    fun ensureBrowseRoot() {
        browseSource = BrowseSource.Roots
        browseTreeUri = null
        browseCrumbs = emptyList()
        browseEntries = emptyList()
        selectedBrowseIds = emptySet()
        refreshStorage()
        browseRoots = cloudStorageRoots()
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
        if (browseSource == BrowseSource.Folder) reloadBrowse()
    }

    fun openBrowseFolder(treeUri: Uri, folderName: String? = null) {
        if (!persistRead(treeUri)) {
            status = uiText(R.string.ui_folder_access_could_not_be_saved_choose_the_folder_aga_68a028)
            return
        }
        viewModelScope.launch {
            try {
                val name = folderName ?: withContext(Dispatchers.IO) {
                    indexer.displayName(DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri)))
                }
                store.rememberFolder(treeUri.toString(), name)
                refresh()
                browseSource = BrowseSource.Folder
                browseTreeUri = treeUri
                browseCrumbs = listOf(BrowseCrumb(name, documentId = DocumentsContract.getTreeDocumentId(treeUri)))
                status = null
                reloadBrowse()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                status = uiText(R.string.ui_could_not_open_that_folder_choose_it_again_e42b5b)
            }
        }
    }

    fun removeBrowseFolder(folder: IndexedFolder) {
        val uri = Uri.parse(folder.uri)
        val resolver = getApplication<Application>().contentResolver
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        try {
            if (grant != null) {
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                resolver.releasePersistableUriPermission(uri, flags)
            }
            store.forgetFolder(folder.uri)
            refresh()
            if (browseTreeUri == uri) ensureBrowseRoot()
            status = uiText(R.string.ui_removed_from_selected_folders_cb882a, folder.name)
        } catch (error: SecurityException) {
            status = uiText(R.string.ui_could_not_release_folder_access_try_again_b4fdec)
        }
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
            status = uiText(R.string.ui_choose_this_folder_with_the_system_picker_665188)
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
            var refused = 0
            val files = withContext(Dispatchers.IO) {
                // Make room first: Android keeps only a few hundred file grants per app.
                releaseUnusedGrants()
                uris.mapNotNull { uri ->
                    // One file Android won't keep access to doesn't stop the others.
                    if (!persistRead(uri)) { refused++; return@mapNotNull null }
                    runCatching {
                        val name = indexer.displayName(uri)
                        BrowseEntry(
                            id = uri.toString(),
                            name = name,
                            isDirectory = false,
                            isVideo = MediaIndexer.isVideoFile(name, ""),
                            sizeBytes = indexer.sizeBytes(uri),
                            uri = uri,
                        )
                    }.getOrElse { releaseGrant(uri); refused++; null }
                }
            }
            if (files.isNotEmpty()) addBrowseFileEntries(files.filter { it.isVideo })
            if (refused > 0) {
                status = uiText(R.string.ui_couldn_t_be_added_android_limits_how_many_f88239, refused, uiText(if (refused == 1) R.string.ui_video_d534be else R.string.ui_video_plural_word)) +
                    uiText(R.string.ui_single_files_an_app_can_keep_remove_videos_you_no_long_eb70b0)
            }
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
            status = uiText(R.string.ui_pick_video_files_to_add_95b421)
            return
        }
        var added = 0
        val imported = mutableListOf<LibraryItem>()
        files.forEachIndexed { index, entry ->
            prepareProgress = (index + 1f) / files.size
            prepareStep = uiText(R.string.ui_adding_of_files_2dd9e6, index + 1, files.size)
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
        status = if (added == 0) uiText(R.string.ui_those_files_are_already_in_your_library_9b2db9) else uiText(R.string.ui_added_files_fb53a9, added)
        recentlyAddedIds = imported.map { it.id }
        tab = PhoneTab.Library
        pendingPrivacyItems.addAll(imported)
        showNextPrivacyPrompt()
        viewModelScope.launch { syncLibrary() }
    }

    private fun showNextPrivacyPrompt() {
        pendingPrivacyItem = pendingPrivacyItems.removeFirstOrNull()
        if (pendingPrivacyItem != null) status = uiText(R.string.ui_should_this_require_phone_approval_92bae5)
    }

    fun choosePrivacy(isPrivate: Boolean) {
        val item = pendingPrivacyItem ?: return
        pendingPrivacyItem = null
        val updated = item.copy(isPrivate = isPrivate)
        store.update(updated)
        refresh()
        if (importDestination == STORAGE_BOTH && cloudConnected) {
            beginSaveToCloud(updated)
        }
        viewModelScope.launch { syncLibrary() }
        showNextPrivacyPrompt()
        if (pendingPrivacyItem == null) status = uiText(R.string.ui_videos_added_3d5b7a)
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
        val connections = buildList {
            addAll(storage?.connections.orEmpty())
            storage?.connection?.let(::add)
        }.distinctBy { it.id ?: it.displayLabel ?: it.bucketName }
        connections.filter { it.status == "active" }.forEach { conn ->
            val key = conn.id ?: conn.bucketName ?: conn.displayLabel ?: return@forEach
            if (roots.any { it.cloudKey == key }) return@forEach
            roots += StorageRoot(
                id = "cloud:$key",
                name = conn.displayLabel ?: conn.bucketName ?: uiText(R.string.ui_connected_cloud_3b1ae6),
                kind = StorageRootKind.Cloud,
                detail = conn.provider ?: uiText(R.string.ui_connected_229655),
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
                        BrowseSource.Cloud, BrowseSource.Roots, BrowseSource.None -> emptyList()
                    }
                }
            } catch (error: Exception) {
                status = uiText(R.string.ui_folder_access_is_unavailable_choose_the_folder_again_2e4497)
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
            status = uiText(R.string.ui_is_already_in_your_library_5f2bdf, displayName)
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
        applyHouseholdArtwork(item.id, done = uiText(R.string.ui_cover_saved_to_your_household_762369), pending = uiText(R.string.ui_the_cover_will_be_saved_to_your_household_on_the_next__87cda3))
    }

    fun keepArtworkOnPhone(item: LibraryItem) {
        store.updateItem(item.id) { it.copy(householdArtworkVersion = null) }
        refresh()
        applyHouseholdArtwork(item.id, done = uiText(R.string.ui_cover_removed_from_your_household_a7304d), pending = uiText(R.string.ui_the_cover_will_be_removed_from_your_household_on_the_n_135e1a))
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
            status = uiText(R.string.ui_enhancing_keeping_primary_audio_5ef242, item.title)
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
                status = uiText(R.string.ui_enhanced_43febe, item.title)
            } catch (error: Exception) {
                status = error.message ?: uiText(R.string.ui_enhance_failed_for_this_file_f4b261)
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
                status = uiText(R.string.ui_couldn_t_reach_cablegram_to_remove_check_your_connecti_8e61be, item.title)
                return@launch
            }
            if (nowPlaying?.id == item.id) nowPlaying = null
            if (selectedId == item.id) selectedId = null
            store.removeFromLibrary(item.id)
            refresh()
            result.telegramDeletions.forEach {
                telegramDeletions.add(PendingTelegramDeletion(it.stableSourceKey, it.chatId, it.messageIds, item.title, System.currentTimeMillis()))
            }
            status = if (permanently) uiText(R.string.ui_deleting_from_telegram_8a441e, item.title) else uiText(R.string.ui_removed_restore_it_from_import_telegram_hidden_videos_7f17e6, item.title)
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
                            status = uiText(R.string.ui_deleted_from_telegram_4e587f, job.title)
                        }
                    } else {
                        val error = outcome.exceptionOrNull()?.message ?: uiText(R.string.ui_unknown_error_3e4443)
                        telegramDeletions.failed(job.stableSourceKey, error)
                        catalog().reportTelegramDeletion(token, job.stableSourceKey, error)
                        if (System.currentTimeMillis() - job.createdAt > TelegramDeletionQueue.GIVE_UP_AFTER_MS) {
                            status = uiText(R.string.ui_couldn_t_delete_from_telegram_open_telegram_and_delete_898bc2, job.title)
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
                status = uiText(R.string.ui_restored_160ebf, title.title)
                loadHiddenVideos()
                syncLibrary(force = true)
            } else {
                status = uiText(R.string.ui_couldn_t_restore_check_your_connection_and_try_again_d17b3e, title.title)
            }
        }
    }

    fun askDelete(item: LibraryItem) {
        deleteTarget = item
    }

    fun removeFromLibrary(item: LibraryItem) {
        if (nowPlaying?.id == item.id) nowPlaying = null
        store.removeFromLibrary(item.id)
        viewModelScope.launch(Dispatchers.IO) { releaseUnusedGrants() }
        deleteTarget = null
        if (selectedId == item.id) selectedId = null
        refresh()
        viewModelScope.launch { syncLibrary() }
        status = uiText(R.string.ui_removed_from_the_library_the_file_is_still_on_the_phon_8401e9, item.title)
    }

    fun deleteFilePermanently(item: LibraryItem) {
        if (nowPlaying?.id == item.id) nowPlaying = null
        store.deleteFile(item.id)
        viewModelScope.launch(Dispatchers.IO) { releaseUnusedGrants() }
        deleteTarget = null
        if (selectedId == item.id) selectedId = null
        refresh()
        viewModelScope.launch { syncLibrary() }
        status = uiText(R.string.ui_deleted_from_this_phone_b74440, item.title)
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
            status = uiText(R.string.ui_connect_a_tv_to_play_fd22ec, item.title)
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
                    status = uiText(R.string.ui_starting_on_bc52ee, ready.title, tv)
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
            playingTvDeviceId = sent.deviceId
            nowPlaying = ready
            paused = effectiveRoute != null && castSender?.playbackPaused == true
            store.setWatchProgress(ready.id, ready.positionSeconds.coerceAtLeast(1))
            refresh()
            if (sent.latest) {
                status = if (sent.outcome == Outcome.Confirmed) uiText(R.string.ui_playing_on_8af952, ready.title, sent.tvName)
                    else uiText(R.string.ui_sent_to_not_confirmed_by_tv_8cbf56, ready.title, sent.tvName)
                nowPlayingStatus = status
            }
        }
    }

    fun playLocallyHint(item: LibraryItem) {
        viewModelScope.launch {
            ensurePlayable(item)
            status = uiText(R.string.ui_ready_on_this_phone_use_play_on_tv_when_a_tv_is_nearby_bbcf10)
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
            // "not_applicable": the TV had nothing to stop (the title already ended there), so nothing plays either way.
            if (!outcome.applies() && (outcome as? Outcome.Rejected)?.reason != "not_applicable") return@launch
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
            status = uiText(R.string.ui_downloading_b81bc3, item.title)
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
                status = uiText(R.string.ui_downloaded_3b7cff, ready.title)
            } catch (error: Exception) {
                status = error.message ?: uiText(R.string.ui_download_failed_eae34c)
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
            status = uiText(R.string.ui_connect_telegram_on_this_phone_first_import_telegram_41f79b)
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
                    status = uiText(R.string.ui_saving_to_telegram_8c9d51, item.title)
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
                    uiText(R.string.ui_telegram_didn_t_confirm_the_upload_try_saving_again_c7e964)
                }
                check(catalog().attachTelegramCopy(token, item.id, sent)) { uiText(R.string.ui_couldn_t_add_the_telegram_copy_to_your_library_try_sav_5406b8) }
                store.updateItem(item.id) { it.copy(telegramCopy = true, transferStatus = TRANSFER_IDLE, webTransferError = null, uploadBytes = null, uploadTotal = null) }
                status = uiText(R.string.ui_saved_to_telegram_you_can_now_free_up_phone_space_2c0ba2, item.title)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                store.updateItem(item.id) { it.copy(transferStatus = TRANSFER_FAILED, webTransferError = TELEGRAM_SAVE_FAILED) }
                throw cancelled
            } catch (error: Exception) {
                // Our own messages (size, verification, registration) say which step failed; no secrets in them.
                PairLog.w("Save to Telegram failed: ${error.javaClass.simpleName}: ${error.message}")
                store.updateItem(item.id) { it.copy(transferStatus = TRANSFER_FAILED, webTransferError = TELEGRAM_SAVE_FAILED) }
                status = (error as? app.cablegram.telegram.TgException)?.let(app.cablegram.telegram.TelegramSession::describe)
                    ?: error.message ?: uiText(R.string.ui_couldn_t_save_to_telegram_234f31, item.title)
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
            // Saves go only to the household's own storage (CAB-38): without one, the sheet leads to connecting it.
            cloudSheet = if (cloudConnected) CloudSheet.Confirm else CloudSheet.Setup
        }.onFailure {
            PairLog.e("Save to Cloud screen failed", it)
            status = it.message ?: uiText(R.string.ui_could_not_open_save_to_cloud_3f5142)
        }
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
                status = it.message ?: uiText(R.string.ui_could_not_start_cloud_save_8b3f61)
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
            status = uiText(R.string.ui_keep_a_cloud_copy_before_removing_the_phone_file_ec15b7)
            return
        }
        store.removeLocalCopy(item.id)
        // Only off the phone now (Telegram or own storage): the phone file is gone, so this phone no longer serves it.
        store.updateItem(item.id) { it.copy(sourceAvailable = false) }
        refresh()
        status = if (item.ownCloudCopy) uiText(R.string.ui_removed_the_phone_copy_stays_in_f4b19c, item.title, saveDestinationName)
        else uiText(R.string.ui_removed_the_phone_copy_stays_in_telegram_1677cc, item.title)
    }

    fun keepOnPhone(item: LibraryItem) {
        if (!item.copied) download(item) else status = uiText(R.string.ui_is_already_on_this_phone_417a80, item.title)
        cloudSheet = CloudSheet.None
        freeUpTargetId = null
    }

    fun clearLocalMetadata() {
        store.clearPosters()
        refresh()
        status = uiText(R.string.ui_cleared_local_posters_and_artwork_cache_1ef435)
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
            status = error.message ?: uiText(R.string.ui_could_not_open_that_video_dcde14)
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
        pairing.transferWhileCharging && !isCharging(getApplication()) -> uiText(R.string.ui_transfers_wait_until_this_phone_is_charging_be1621)
        pairing.wifiOnlyTransfers && !isWifi(getApplication()) -> uiText(R.string.ui_transfers_wait_for_wi_fi_bdb4ad)
        else -> uiText(R.string.ui_transfer_blocked_381459)
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
    private data class SentCommand(val tvName: String, val outcome: Outcome, val latest: Boolean, val deviceId: String?)

    /** The TV the current title was started on, and when this phone's last command was answered (CAB-57). */
    private var playingTvDeviceId: String? = null
    private var ownCommandAt = 0L
    private var followJob: Job? = null

    /**
     * CAB-57: while a title plays on a TV and this screen is visible, follow what the TV reports, so a title that
     * ends, is stopped or is paused on the TV shows that here too. Cast Connect playback reports through its own
     * session instead.
     */
    private fun followTvPlayback() {
        if (followJob?.isActive == true) return
        followJob = viewModelScope.launch {
            while (isActive && nowPlayingState != null && castVisible && !castPlaybackOwned) {
                delay(TV_FOLLOW_POLL_MS)
                val deviceId = playingTvDeviceId ?: pairing.tvs.lastOrNull()?.deviceId ?: break
                val token = pairing.accountToken?.takeIf { it.isNotBlank() } ?: break
                val report = catalog().tvPlayback(token, deviceId) ?: continue
                val current = nowPlayingState ?: break
                if (castPlaybackOwned) break
                when (val follow = followTv(report, current.id, SystemClock.elapsedRealtime() - ownCommandAt)) {
                    TvFollow.Keep -> Unit
                    TvFollow.Stopped -> { nowPlaying = null; paused = false }
                    is TvFollow.Playing -> if (paused != follow.paused) paused = follow.paused
                    is TvFollow.Other -> {
                        val other = items.firstOrNull { it.id == follow.videoId || it.id == follow.originId }
                        nowPlaying = other
                        paused = other != null && follow.paused
                    }
                }
            }
        }
    }

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
        report(uiText(R.string.ui_sending_b8ed52))
        val client = catalog()
        val castTarget = if (castRoute != null) pairing.tvs.lastOrNull()?.deviceId else null
        if (castRoute != null && castTarget == null) return null.also { reportFinal(uiText(R.string.ui_choose_a_paired_tv_in_settings_0b8448)) }
        val payload = if (castRoute != null && castTitle != null) buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("castLaunch", true)
            put("title", castTitle.title.take(160))
            put("senderName", pairing.displayName.take(80).ifBlank { uiText(R.string.ui_your_phone_807163) })
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
            sent.switchedFrom != null -> uiText(R.string.ui_isn_t_on_so_on_5fb016, sent.switchedFrom, uiText(if (command == "play" && videoId != null) R.string.ui_command_starting else R.string.ui_command_sending), sent.tvName)
            command == "play" && videoId != null -> uiText(R.string.ui_starting_on_12d09e, sent.tvName)
            else -> uiText(R.string.ui_sent_to_29ea5e, sent.tvName)
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
                    if (retry == null) return null.also { reportFinal(uiText(R.string.ui_that_cast_device_isn_t_your_selected_tv_choose_a_paire_45fa19)) }
                    sent = when (val result = CastSession.send(pairing, client, command, videoId, payload, household, retry.deviceId)) {
                        is CastSession.Result.Sent -> result
                        is CastSession.Result.Failed -> return null.also { reportFinal(result.message) }
                    }
                    val retried = castSender?.load(castRoute.deviceId, sent.commandId, retry.deviceId!!,
                        castTitle.id, castTitle.title, castTitle.posterUrl)
                    if (retried?.wrongTv == true) return null.also { reportFinal(uiText(R.string.ui_tv_changed_choose_a_tv_and_try_again_69312c)) }
                    selectTv(retry)
                    pairing.attachCastDeviceId(retry.deviceId!!, castRoute.deviceId)
                    tvs = pairing.tvs
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                return null.also { reportFinal(error.message ?: uiText(R.string.ui_could_not_open_the_tv_try_again_344ff8)) }
            }
        }
        val outcome = CastSession.confirm(client, sent, wait)
        reportFinal(when (outcome) {
            Outcome.Confirmed -> uiText(R.string.ui_done_on_623a01, sent.tvName)
            is Outcome.Rejected -> rejectionMessage(outcome.reason, sent.tvName)
            Outcome.TimedOut -> uiText(R.string.ui_didn_t_confirm_check_the_tv_e508b0, sent.tvName)
            Outcome.Unconfirmable -> uiText(R.string.ui_sent_not_confirmed_by_tv_feac62)
            Outcome.Unreachable -> uiText(R.string.ui_isn_t_responding_open_cablegram_on_it_choose_who_s_wat_f5df22, sent.tvName)
        })
        ownCommandAt = SystemClock.elapsedRealtime()
        return SentCommand(sent.tvName, outcome, latest = generation == commandGeneration, deviceId = sent.deviceId)
    }

    private fun publishCast() {
        CastSession.update(nowPlayingState?.let { Cast(it.id, it.title, tvName, pausedState) })
    }

    override fun onCleared() {
        cancelSubtitleSessions()
        castRoutesJob?.cancel()
        castSender?.release()
        super.onCleared()
    }

    private fun releaseGrant(uri: Uri) {
        runCatching { getApplication<Application>().contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * Android caps persisted file grants per app (128, or 512 from Android 11), so a grant that no library title or
     * selected folder uses any more is given back. Grants from the last minute are left alone: an import may still
     * be saving the title that uses it.
     */
    private fun releaseUnusedGrants() {
        val resolver = getApplication<Application>().contentResolver
        val inUse = (store.list().mapNotNull { it.sourceUri } + store.folders().map { it.uri }).toSet()
        val now = System.currentTimeMillis()
        resolver.persistedUriPermissions
            .filter { it.uri.toString() !in inUse && now - it.persistedTime > 60_000 }
            .forEach { grant ->
                val flags = (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                runCatching { resolver.releasePersistableUriPermission(grant.uri, flags) }
            }
    }

    private fun persistRead(uri: Uri): Boolean = runCatching {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        true
    }.getOrDefault(false)

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
