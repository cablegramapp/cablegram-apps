package app.cablegram

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.cablegram.data.AccountCredential
import app.cablegram.data.ApiException
import app.cablegram.data.AuthStore
import app.cablegram.data.DeviceSession
import app.cablegram.data.CablegramApi
import app.cablegram.data.LibraryAds
import app.cablegram.data.LiveTvChannel
import app.cablegram.data.PairLog
import app.cablegram.data.PlaybackResponse
import app.cablegram.data.Profile
import app.cablegram.data.Video
import app.cablegram.data.ProgressQueue
import app.cablegram.data.localIpv4
import app.cablegram.data.localIpv4Addresses
import android.view.KeyEvent
import app.cablegram.data.demoLibraryVideos
import app.cablegram.data.demoPlaybackUrl
import app.cablegram.data.extractUserIdFromToken
import app.cablegram.data.getRandomLoadingVideoUrl
import app.cablegram.data.findRemoteTitle
import app.cablegram.data.hasCompletedIngest
import app.cablegram.data.isDemoVideoId
import app.cablegram.data.isLiveChannelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.jsonPrimitive
import app.cablegram.data.CommandJournal
import app.cablegram.data.CommandDelivery
import app.cablegram.data.TvCommand
import app.cablegram.data.validationError

sealed interface ScreenState {
    data object Loading : ScreenState
    data class Pairing(val session: DeviceSession, val nameRequired: Boolean = false) : ScreenState
    data class ProfilePicker(
        val profiles: List<Profile>,
        val pending: Boolean = false,
        val message: String? = null,
        /** R-4: profile awaiting PIN entry before it can be opened. */
        val pinPromptFor: Profile? = null,
        val pinError: String? = null,
        val pinDigits: Int = 0,
    ) : ScreenState
    data class SessionActions(val profiles: List<Profile>, val message: String? = null) : ScreenState
    data object Library : ScreenState
    data class Resolving(
        val video: Video,
        val converting: Boolean = false,
        val loadingVideoUrl: String? = null,
        val prepareProgress: Int? = null,
        val prepareStage: String? = null,
        val prepareLabel: String? = null,
        /** T075: private playback is waiting for phone approval — show the permit panel. */
        val awaitingApproval: Boolean = false,
    ) : ScreenState
    data class Player(val video: Video, val playback: PlaybackResponse) : ScreenState
    data class Error(
        val title: String,
        val message: String,
        val canSignOut: Boolean = false,
        val removableVideo: Video? = null,
        val convertibleVideo: Video? = null,
        /** Offers "Back to library" (and makes the Back key return there instead of leaving the app). */
        val canGoBack: Boolean = false,
    ) : ScreenState
}

internal fun ScreenState.acceptsCommandPolling(): Boolean =
    this is ScreenState.Library ||
        this is ScreenState.ProfilePicker ||
        this is ScreenState.Resolving ||
        this is ScreenState.Player

class CablegramViewModel(application: Application) : AndroidViewModel(application) {
    private val api = CablegramApi()
    private val authStore = AuthStore(application)
    /** Spec 004: this TV's Telegram session, approved by the household phone. */
    val telegram = TvTelegram(application, api, onLibraryChanged = { refreshLibrary() })
    init {
        // Spec 004 US8: at a temporary TV's end time, drop everything Telegram-related that was stored here.
        app.cablegram.data.onDeviceExpired = { telegram.wipeLocalData() }
    }
    private val lanPrefs = application.getSharedPreferences("cablegram_lan", Context.MODE_PRIVATE)
    private val progressQueue = ProgressQueue(application)
    private var flushJob: Job? = null
    private var token: String? = null
    private var pairingJob: Job? = null
    private var refreshJob: Job? = null
    private var librarySyncJob: Job? = null
    private var pinPrompt: String? = null
    private val unlockedProfileIds = mutableMapOf<String, Boolean>()
    private var commandJob: Job? = null
    private val commandJournal = CommandJournal(
        lanPrefs.getString("command_journal_v2", null),
        save = { check(lanPrefs.edit().putString("command_journal_v2", it).commit()) { "Cannot persist command result" } },
    )
    private val commandDelivery = CommandDelivery(viewModelScope, api.commandTransport(), commandJournal, ::handleCommand)
    private var remoteTitleCommand by mutableStateOf<Pair<String, String>?>(null)
    val pendingRemoteTitleId: String? get() = remoteTitleCommand?.first
    private var playbackJob: Job? = null
    private var cachedVideos: List<Video> = emptyList()
    private var profiles: List<Profile> = emptyList()
    private var activeProfileId: String? = null
    private val profileAccounts = mutableMapOf<String, AccountCredential>()
    private var failedPlaybackVideo: Video? = null
    private val playerCommands = app.cablegram.data.PendingCommands()
    private val navCommands = app.cablegram.data.PendingCommands()
    val pendingPlayerCommand get() = playerCommands.first
    val pendingNavCommand get() = navCommands.first
    private var sessionId: String? = null

    var isDemoMode by mutableStateOf(false)
        private set
    private val reviewerBypass = ReviewerBypassTracker()
    /** Whether the Select key currently held down belongs to the focused button. */
    private var selectPassesThrough = false

    val activeProfileName: String
        get() = when {
            isDemoMode -> "Demo"
            else -> profiles.firstOrNull { it.id == activeProfileId }?.name ?: "Cablegram"
        }

    val activeProfileAvatarUrl: String?
        get() = profiles.firstOrNull { it.id == activeProfileId }?.resolvedAvatarUrl()

    val hasProfiles: Boolean
        get() = profiles.isNotEmpty()

    var screen by mutableStateOf<ScreenState>(ScreenState.Loading)
        private set
    var libraryVideos by mutableStateOf<List<Video>>(emptyList())
        private set
    var isLibraryRefreshing by mutableStateOf(false)
        private set
    var librarySection by mutableStateOf("all")
        private set
    var libraryFocusItemId by mutableStateOf<String?>(null)
        private set
    var libraryShowKey by mutableStateOf<String?>(null)
        private set
    var libraryAds by mutableStateOf<LibraryAds?>(null)
        private set
    var pairingStatus by mutableStateOf<String?>(null)
        private set
    var tvLanIp by mutableStateOf<String?>(null)
        private set
    private var lanToken: String?
        get() = lanPrefs.getString("token", null)
        set(value) { lanPrefs.edit().putString("token", value).apply() }
    private var phoneJob: Job? = null

    fun selectLibrarySection(section: String) {
        librarySection = section
    }

    fun rememberLibraryExplorer(focusItemId: String?, showKey: String?) {
        libraryFocusItemId = focusItemId
        libraryShowKey = showKey
    }

    fun toggleMyList(videoIds: List<String>, inMyList: Boolean) {
        val ids = videoIds.toSet()
        if (ids.isEmpty()) return
        applyMyListChange(ids, inMyList)
        if (isDemoMode) {
            val saved = savedDemoMyList()
            demoMyListPrefs().edit().putStringSet("ids", if (inMyList) saved + ids else saved - ids).apply()
            return
        }
        val currentToken = token ?: return
        val profileId = activeProfileId ?: return
        viewModelScope.launch {
            runCatching { api.setMyList(profileId, ids.toList(), inMyList, currentToken) }
                .onFailure { applyMyListChange(ids, !inMyList) }
        }
    }

    private fun applyMyListChange(ids: Set<String>, inMyList: Boolean) {
        cachedVideos = cachedVideos.map { video -> if (video.id in ids) video.copy(inMyList = inMyList) else video }
        libraryVideos = cachedVideos
    }

    private fun demoMyListPrefs() =
        getApplication<Application>().getSharedPreferences("cablegram_demo_my_list", Context.MODE_PRIVATE)

    private fun savedDemoMyList(): Set<String> = demoMyListPrefs().getStringSet("ids", emptySet()).orEmpty()

    private fun applyDemoMyList(videos: List<Video>): List<Video> {
        val saved = savedDemoMyList()
        return videos.map { video -> video.copy(inMyList = video.id in saved) }
    }

    /**
     * One-time move of a My List saved on this TV by the previous build into
     * the profile's server-side list, so it appears on every TV.
     */
    private suspend fun migrateLocalMyList(profileId: String, currentToken: String) {
        val prefs = getApplication<Application>().getSharedPreferences("cablegram_my_list", Context.MODE_PRIVATE)
        val key = "profile:$profileId"
        val saved = prefs.getStringSet(key, emptySet()).orEmpty()
        if (saved.isEmpty()) return
        runCatching { api.setMyList(profileId, saved.toList(), true, currentToken) }
            .onSuccess { prefs.edit().remove(key).apply() }
    }

    init {
        refreshLanAddresses()
        PairLog.i("TV start api=${app.cablegram.BuildConfig.API_BASE_URL} emulator=${isEmulatorTv()} tvIp=$tvLanIp")
        viewModelScope.launch {
            while (true) {
                refreshLanAddresses()
                delay(5_000)
            }
        }
        viewModelScope.launch {
            libraryAds = api.getLibraryAds()
            val accounts = authStore.getAccounts()
            if (accounts.isEmpty()) {
                createPairingSession()
            } else {
                val active = authStore.getActiveAccount() ?: accounts.first()
                token = active.token
                sessionId = active.sessionId
                loadProfiles()
            }
            startProgressFlusher()
        }
    }

    private fun refreshLanAddresses() {
        val scope = viewModelScope
        scope.launch(Dispatchers.IO) {
            val ips = localIpv4Addresses().joinToString("  ·  ").ifBlank { null } ?: localIpv4()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                tvLanIp = ips
            }
        }
    }

    /** T077: pairing completed or library screen re-entered without re-polling. */
    fun continueWithPhoneLibrary() {
        pairingJob?.cancel()
        screen = ScreenState.Library
        startCommandPolling()
        startLibrarySync()
    }

    fun retry() {
        val video = failedPlaybackVideo
        if (video != null) {
            play(video)
            return
        }
        viewModelScope.launch {
            val accounts = authStore.getAccounts()
            if (accounts.isEmpty()) createPairingSession() else loadProfiles()
        }
    }

    fun enterDemoMode() {
        pairingJob?.cancel()
        pairingJob = null
        refreshJob?.cancel()
        librarySyncJob?.cancel()
        stopCommandDelivery()
        playbackJob?.cancel()
        isDemoMode = true
        token = null
        sessionId = null
        cachedVideos = applyDemoMyList(demoLibraryVideos())
        libraryVideos = cachedVideos
        screen = ScreenState.Library
    }

    /**
     * Play Console reviewer bypass. Must run from [MainActivity.dispatchKeyEvent]
     * before Compose so Select does not activate "New code".
     */
    fun onReviewerBypassKey(event: KeyEvent): Boolean {
        if (isDemoMode || !isReviewerBypassScreen()) return false
        val digit = reviewerDigit(event.keyCode)
        val isSelect = isReviewerSelectKey(event.keyCode)
        if (!isSelect && digit == null) return false
        if (isSelect) {
            // Down and Up must go the same way: Compose clicks on key Up.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                selectPassesThrough = reviewerBypass.isDeliberateSelect()
                if (reviewerBypass.onSelect()) {
                    selectPassesThrough = false
                    enterDemoMode()
                    return true
                }
            }
            return !selectPassesThrough
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && reviewerBypass.onDigit(digit!!)) {
            enterDemoMode()
        }
        return true
    }

    private fun isReviewerBypassScreen(): Boolean = when (val current = screen) {
        is ScreenState.Pairing, ScreenState.Loading -> true
        is ScreenState.Error -> token == null
        else -> false
    }

    fun refreshLibrary() {
        if (isDemoMode) return
        if (screen !is ScreenState.Library || refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch { loadLibrary(showRefreshIndicator = true) }
    }

    /** Refresh only when the app returns to the foreground; never replace the active library screen. */
    fun refreshLibraryOnResume() = refreshLibrary()

    fun showProfilePicker() {
        librarySyncJob?.cancel()
        stopCommandDelivery()
        if (profiles.isNotEmpty()) screen = ScreenState.ProfilePicker(profiles)
        startCommandPolling()
    }

    /**
     * Back on "Who's watching?" after switching profile: return to the profile that was playing.
     * False when there is none (app start), so the picker asks before exiting instead.
     */
    fun returnToLibraryFromPicker(): Boolean {
        if (activeProfileId == null || cachedVideos.isEmpty()) return false
        pinPrompt = null
        screen = ScreenState.Library
        startLibrarySync()
        startCommandPolling()
        return true
    }

    fun showSessionActions() {
        librarySyncJob?.cancel()
        if (profiles.isNotEmpty()) screen = ScreenState.SessionActions(profiles)
    }

    fun closeSessionActions() {
        if (cachedVideos.isNotEmpty()) {
            screen = ScreenState.Library
            startLibrarySync()
        }
        else showProfilePicker()
    }

    fun signOutProfile() {
        val currentToken = token
        val profileId = activeProfileId
        val currentSessionId = sessionId
        viewModelScope.launch {
            if (currentToken == null || profileId == null) return@launch
            try {
                runCatching { api.detachProfile(profileId, currentToken) }
                val remainingAccountProfiles = runCatching {
                    api.getProfiles(currentToken).profiles
                }.getOrDefault(emptyList())

                if (remainingAccountProfiles.isEmpty() && currentSessionId != null) {
                    authStore.removeAccount(currentSessionId)
                }
                activeProfileId = null
                cachedVideos = emptyList()
                loadProfiles()
            } catch (error: Exception) {
                screen = ScreenState.SessionActions(profiles, error.userMessage())
            }
        }
    }

    fun playLiveChannel(channel: LiveTvChannel) {
        librarySyncJob?.cancel()
        failedPlaybackVideo = null
        screen = ScreenState.Player(
            video = channel.toVideo(),
            playback = PlaybackResponse(status = "ready", url = channel.streamUrl),
        )
    }

    fun play(video: Video) {
        finishRemoteTitle("superseded")
        if (isLiveChannelId(video.id)) return
        // T077 / R-7: the server catalog is the single source of truth.
        // Playback resolution always goes through the control plane (`getPlayback`),
        // which resolves the serving phone from registered device LAN hints.
        // Legacy direct-to-phone lookup (`phone:` ids, NSD, relay) is retired.
        if (isDemoMode || isDemoVideoId(video.id)) {
            val url = demoPlaybackUrl(video.id) ?: return
            librarySyncJob?.cancel()
            failedPlaybackVideo = null
            screen = ScreenState.Player(video, PlaybackResponse(status = "ready", url = url))
            return
        }
        val currentToken = token ?: return
        librarySyncJob?.cancel()
        failedPlaybackVideo = null
        val loadingUrl = getRandomLoadingVideoUrl()
        if (!video.hasCompletedIngest() || video.tier == "evicted") {
            screen = ScreenState.Resolving(
                video,
                loadingVideoUrl = loadingUrl,
                prepareProgress = video.ingestProgress,
                prepareStage = video.ingestStage,
                prepareLabel = video.ingestLabel,
            )
        }
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch { awaitPlayback(video, currentToken, loadingUrl) }
    }

    fun requestTranscode(video: Video) {
        val currentToken = token ?: return
        val loadingUrl = getRandomLoadingVideoUrl()
        screen = ScreenState.Resolving(video, converting = true, loadingVideoUrl = loadingUrl)
        playbackJob?.cancel(); playbackJob = viewModelScope.launch {
            try {
                api.requestTranscode(video.id, "manual_client_fallback", currentToken)
                awaitPlayback(video, currentToken, loadingUrl)
            }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { screen = ScreenState.Error("Can't convert this video", error.userMessage(), convertibleVideo = video, canGoBack = true) }
        }
    }

    /** Leaving the wait screen only stops polling; the server keeps converting in the background. */
    fun cancelResolving() {
        finishRemoteTitle("cancelled")
        playbackJob?.cancel()
        playbackJob = null
        failedPlaybackVideo = null
        closePlayer()
    }

    private suspend fun awaitPlayback(video: Video, currentToken: String, initialLoadingUrl: String? = null) {
        var currentLoadingUrl = initialLoadingUrl ?: getRandomLoadingVideoUrl()
        var currentVideo = video
        try {
            while (true) {
                var awaitingApproval = false
                val playback = api.getPlayback(
                    currentVideo.id, currentToken, lanToken,
                    telegramUrl = telegram::streamUrl,
                    telegramPhoneId = telegram::phoneDeviceId,
                    onStatus = { label ->
                        // Live status from the API while it works (e.g. approval wait).
                        screen = ScreenState.Resolving(
                            video = currentVideo,
                            loadingVideoUrl = currentLoadingUrl,
                            prepareLabel = label,
                        )
                    },
                    onAwaitingApproval = {
                        // Show the permit panel the moment the grant is raised.
                        awaitingApproval = true
                        screen = ScreenState.Resolving(
                            video = currentVideo,
                            loadingVideoUrl = currentLoadingUrl,
                            prepareLabel = "Waiting for your phone to approve",
                            awaitingApproval = true,
                        )
                    },
                )
                awaitingApproval = playback.status == "preparing" && awaitingApproval
                currentVideo = currentVideo.copy(
                    title = playback.title?.takeIf { it.isNotBlank() } ?: currentVideo.title,
                    posterUrl = playback.posterUrl ?: currentVideo.posterUrl,
                )
                if (playback.status == "ready" && playback.url != null) {
                    currentVideo = currentVideo.copy(
                        ingestProgress = 100,
                        ingestStage = "ready",
                        ingestLabel = "Ready",
                        tier = if (currentVideo.tier == "ingesting" || currentVideo.tier == "evicted") "hot+r2" else currentVideo.tier,
                    )
                    cachedVideos = cachedVideos.map { if (it.id == currentVideo.id) currentVideo else it }
                    libraryVideos = cachedVideos
                    // Playback responses carry no progress; resume from the
                    // profile's catalog position so Resume / Start over appears.
                    screen = ScreenState.Player(
                        currentVideo,
                        playback.copy(resumePositionSeconds = playback.resumePositionSeconds ?: video.resumePositionSeconds),
                    )
                    break
                }
                if (playback.status == "denied") {
                    // T075 / R-5: private playback was not approved — show why and stop.
                    playbackJob?.cancel()
                    failedPlaybackVideo = currentVideo
                    screen = ScreenState.Error(
                        "Playback wasn't approved",
                        playback.prepareLabel ?: "Your phone didn't approve this title. Press Try again to ask once more, or Back to the title.",
                        canSignOut = false,
                        removableVideo = null,
                        canGoBack = true,
                    )
                    break
                }
                if (playback.status != "preparing" && playback.status != "transcoding") {
                    throw IllegalStateException("Video is not available")
                }
                if (playback.loadingVideoUrl != null && currentLoadingUrl.isEmpty()) {
                    currentLoadingUrl = playback.loadingVideoUrl
                }
                cachedVideos = cachedVideos.map { if (it.id == currentVideo.id) currentVideo else it }
                libraryVideos = cachedVideos
                screen = ScreenState.Resolving(
                    video = currentVideo,
                    converting = playback.status == "transcoding",
                    loadingVideoUrl = currentLoadingUrl.ifEmpty { playback.loadingVideoUrl },
                    prepareProgress = playback.prepareProgress,
                    prepareStage = playback.prepareStage,
                    prepareLabel = playback.prepareLabel,
                    awaitingApproval = awaitingApproval,
                )
                delay((playback.pollAfterSeconds ?: 2) * 1_000L)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failedPlaybackVideo = currentVideo
            val removableVideo = if ((error as? ApiException)?.statusCode == 409) video else null
            screen = ScreenState.Error(
                title = "Can't play this video",
                message = playbackError(error),
                removableVideo = removableVideo,
                canGoBack = true,
            )
        }
    }

    fun removeUnavailableVideo(video: Video) {
        val currentToken = token ?: return
        viewModelScope.launch {
            try {
                api.deleteFailedVideo(video.id, currentToken)
                failedPlaybackVideo = null
                cachedVideos = cachedVideos.filterNot { it.id == video.id }
                libraryVideos = cachedVideos
                screen = ScreenState.Library
                startLibrarySync()
            } catch (error: Exception) {
                screen = ScreenState.Error("Can't remove this video", error.userMessage(), canGoBack = true)
            }
        }
    }

    fun closePlayer() {
        telegram.releasePlayback()
        finishRemoteTitle("cancelled")
        // Do not leave player actions queued for the next title.
        val stoppingId = playerCommands.first?.takeIf { it.command == "stop" }?.id
        playerCommands.snapshot().filter { it.id != stoppingId }
            .forEach { finishCommand(it.id, "player_closed") }
        playerCommands.clear()
        libraryVideos = cachedVideos
        screen = ScreenState.Library
        if (!isDemoMode) startLibrarySync()
    }

    fun reportPlaybackState(videoId: String, positionSeconds: Int, durationSeconds: Int?, isPlaying: Boolean, volume: Int, muted: Boolean, engine: String? = null) {
        if (isPlaying) remotePlaybackResult(videoId, null)
        if (isLiveChannelId(videoId) || isDemoMode || isDemoVideoId(videoId)) return
        val currentToken = token ?: return
        val state = when {
            isPlaying -> "playing"
            durationSeconds != null && durationSeconds > 0 &&
                positionSeconds >= durationSeconds * 95 / 100 -> "completed"
            else -> "paused"
        }
        viewModelScope.launch {
            runCatching {
                api.updateProgress(
                    videoId, positionSeconds, currentToken, activeProfileId,
                    state = state, durationSeconds = durationSeconds,
                )
            }.onFailure { error ->
                when {
                    error.isUnauthorized() -> onSessionUnauthorized(currentToken)
                    // The profile was deleted (from the phone): leave it instead of queueing
                    // progress that can never be accepted.
                    (error as? ApiException)?.statusCode == 403 -> onProfileGone()
                    (error as? ApiException)?.statusCode == 410 -> onMediaDeleted(videoId)
                    error.isPermanentRejection() -> Unit
                    else -> progressQueue.enqueue(videoId, activeProfileId, positionSeconds, state, durationSeconds)
                }
            }
        }
    }

    /**
     * R-3: drain the durable progress queue oldest-first. Runs on startup and
     * then retries on a rhythm while the app is alive; each success removes
     * its entry, each failure leaves the queue untouched for the next round.
     */
    private fun startProgressFlusher() {
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                flushProgressQueue()
            }
        }
    }

    suspend fun flushProgressQueue() {
        val currentToken = token ?: return
        val pending = progressQueue.pending()
        if (pending.isEmpty()) return
        for (entry in pending) {
            val entryRef = entry
            val lastFailure = runCatching {
                api.updateProgress(
                    entryRef.videoId,
                    entryRef.positionSeconds,
                    currentToken,
                    entryRef.profileId,
                    state = entryRef.state,
                    durationSeconds = entryRef.durationSeconds,
                    clientUpdatedAt = entryRef.clientUpdatedAt,
                )
            }.exceptionOrNull()
            // A rejected entry (deleted profile or title) would otherwise block the queue forever.
            if (lastFailure != null && !lastFailure.isPermanentRejection()) return // still offline; keep the rest queued
            progressQueue.remove(entryRef.videoId, entryRef.profileId)
        }
    }

    suspend fun renewPlayback(videoId: String): PlaybackResponse? {
        if (isLiveChannelId(videoId) || isDemoMode) return null
        val currentToken = token ?: return null
        return runCatching { api.getPlayback(videoId, currentToken, lanToken, telegramUrl = telegram::streamUrl, telegramPhoneId = telegram::phoneDeviceId) }.getOrNull()
    }

    fun signOut() {
        pairingJob?.cancel()
        refreshJob?.cancel()
        librarySyncJob?.cancel()
        stopCommandDelivery()
        viewModelScope.launch {
            isDemoMode = false
            // Revoke server-side too; clearing only local tokens left the device active.
            authStore.getAccounts().forEach { account ->
                runCatching { kotlinx.coroutines.withTimeout(5_000) { api.revokeSelf(account.token) } }
            }
            authStore.clear()
            telegram.signOutAndWipe()
            token = null
            sessionId = null
            activeProfileId = null
            profileAccounts.clear()
            profiles = emptyList()
            cachedVideos = emptyList()
            createPairingSession()
        }
    }

    fun chooseProfile(profile: Profile) {
        // R-4 / FR-008: profiles with a PIN must be unlocked on the TV first.
        if (profile.pinSet && unlockedProfileIds[profile.id] != true) {
            pinPrompt = profile.id
            screen = ScreenState.ProfilePicker(profiles, pinPromptFor = profile)
            return
        }
        proceedWithProfile(profile)
    }

    /** R-4: PIN keypad callback — verifies against the control plane. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun submitProfilePin(pin: String) {
        val profile = profiles.firstOrNull { it.id == pinPrompt } ?: return
        if (profile.pinSet && unlockedProfileIds[profile.id] != true) {
            screen = ScreenState.ProfilePicker(profiles, pending = true, pinPromptFor = profile, pinDigits = pin.length)
            viewModelScope.launch {
                val code = runCatching {
                    api.unlockProfile(profile.id, pin, profileAccounts[profile.id]?.token ?: token ?: return@launch)
                }.getOrNull()
                if (code != null && code in 200..299) {
                    unlockedProfileIds[profile.id] = true
                    pinPrompt = null
                    proceedWithProfile(profile)
                } else {
                    // A lockout or network failure used to read "Wrong PIN", so people kept retrying
                    // the right PIN.
                    val message = when (code) {
                        403 -> "Wrong PIN. Try again."
                        429 -> "Too many tries. This profile is locked for 15 minutes."
                        else -> "Couldn't check the PIN. Check the connection and try again."
                    }
                    screen = ScreenState.ProfilePicker(profiles, pinPromptFor = profile, pinError = message)
                }
            }
        } else {
            proceedWithProfile(profile)
        }
    }

    fun cancelProfilePin() {
        pinPrompt = null
        screen = ScreenState.ProfilePicker(profiles)
    }

    /** R-4: emits keypad digit presses so the UI can show the masked length. */
    fun onPinDigit() {
        val profile = profiles.firstOrNull { it.id == pinPrompt } ?: return
        val current = screen as? ScreenState.ProfilePicker ?: return
        screen = current.copy(pinDigits = (current.pinDigits + 1).coerceAtMost(8))
    }

    private fun proceedWithProfile(profile: Profile) {
        val account = profileAccounts[profile.id]
        val currentToken = account?.token ?: token ?: return
        val currentSessionId = account?.sessionId ?: sessionId
        token = currentToken
        sessionId = currentSessionId
        activeProfileId = profile.id
        viewModelScope.launch {
            if (currentSessionId != null) {
                authStore.setActiveSessionId(currentSessionId)
            }
        }
        screen = ScreenState.ProfilePicker(profiles, pending = true, message = "Opening profile…")
        viewModelScope.launch {
            try {
                val request = api.requestProfileSwitch(profile.id, deviceName(), currentToken)
                if (request.status == "approved") {
                    api.attachProfile(profile.id, currentToken)
                    activeProfileId = profile.id
                    loadLibrary()
                    return@launch
                }
                repeat(150) {
                    delay(2_000)
                    when (val result = api.getProfileSwitchRequest(request.requestId, currentToken).status) {
                        "approved" -> {
                            api.attachProfile(profile.id, currentToken)
                            activeProfileId = profile.id
                            loadLibrary()
                            return@launch
                        }
                        "denied", "expired" -> {
                            screen = ScreenState.ProfilePicker(profiles, message = "Profile change was not approved.")
                            return@launch
                        }
                    }
                }
                screen = ScreenState.ProfilePicker(profiles, message = "Approval request expired.")
            } catch (error: Exception) {
                screen = ScreenState.ProfilePicker(profiles, message = error.userMessage())
            }
        }
    }

    fun addProfile(name: String) {
        val currentToken = token ?: return
        viewModelScope.launch {
            try { api.createProfile(name, currentToken); loadProfiles() }
            catch (error: Exception) { screen = ScreenState.ProfilePicker(profiles, message = error.userMessage()) }
        }
    }

    fun startProfilePairing() {
        librarySyncJob?.cancel()
        stopCommandDelivery()
        viewModelScope.launch { createPairingSession() }
    }

    fun cancelProfilePairing() {
        pairingJob?.cancel()
        pairingJob = null
        if (profiles.isNotEmpty()) {
            screen = ScreenState.ProfilePicker(profiles)
            startCommandPolling()
        }
    }

    private suspend fun createPairingSession() {
        val currentJob = currentCoroutineContext()[Job]
        if (pairingJob !== currentJob) pairingJob?.cancel()
        // "New PIN" keeps the pairing screen (and its focused button) mounted;
        // only the code changes.
        if (screen !is ScreenState.Pairing) screen = ScreenState.Loading
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        try {
            // ANDROID_ID, hashed by the server: keeps the free relay allowance per physical TV.
            val hardwareId = runCatching {
                android.provider.Settings.Secure.getString(getApplication<android.app.Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            }.getOrNull()
            val session = api.createDeviceSession(deviceName, hardwareId)
            // A New PIN press can still be in flight when the reviewer gesture
            // opens demo mode; a late session must not replace it.
            if (isDemoMode) return
            lanToken = session.pin
            PairLog.i("TV session ok ${PairLog.pinTail(session.pin)} session=${session.sessionId} tvIp=$tvLanIp")
            screen = ScreenState.Pairing(session)
            pairingJob = viewModelScope.launch { pollPairing(session) }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Exception) {
            if (isDemoMode) return
            val pin = (0..999_999).random().toString().padStart(6, '0')
            lanToken = pin
            val qr = "cablegram://pair?token=$pin&name=${java.net.URLEncoder.encode(deviceName, "UTF-8")}"
            screen = ScreenState.Pairing(
                DeviceSession(
                    sessionId = java.util.UUID.randomUUID().toString(),
                    pairingToken = pin,
                    pin = pin,
                    qrUrl = qr,
                    phoneQrUrl = qr,
                    expiresAt = "",
                ),
            )
            PairLog.e("TV createDeviceSession failed, offline PIN ${PairLog.pinTail(pin)}", error)
            pairingStatus = "API unreachable. Phone QR still works on this Wi‑Fi."
        }
    }

    private suspend fun pollPairing(session: DeviceSession) {
        while (true) {
            delay(2_000)
            try {
                val result = api.getPairingStatus(session.sessionId, session.pairingToken)
                PairLog.i(
                    "TV poll status=${result.status} host=${result.phoneHost} port=${result.phonePort} " +
                        "public=${result.phonePublicBaseUrl} ${PairLog.pinTail(session.pin)}",
                )
                if (result.status == "paired" && result.token != null) {
                    val pairedSessionId = result.sessionId ?: session.sessionId
                    val pairedToken = result.token
                    val account = AccountCredential(
                        sessionId = pairedSessionId,
                        token = pairedToken,
                        userId = extractUserIdFromToken(pairedToken),
                    )
                    authStore.saveAccount(account)
                    token = pairedToken
                    sessionId = pairedSessionId
                    // Store the device capability for LAN media auth. The pairing
                    // PIN is no longer the LAN secret (FR-016 / R-1).
                    if (!result.lanCapability.isNullOrBlank()) {
                        lanToken = result.lanCapability
                        PairLog.i("LAN capability stored ${PairLog.pinTail(result.lanCapability)}")
                    } else if (lanPrefs.getString("token", null) == null) {
                        // Transition: older phone build that has not stored a
                        // capability yet. Fall back to the PIN, matching the
                        // phone's legacy behavior, until both apps are updated.
                        lanToken = session.pin
                    }
                    // A fresh pairing always goes through "Who's watching?": opening
                    // the library here skipped profile selection (no progress, no
                    // resume) and bypassed profile PINs.
                    pairingStatus = "Phone paired. Choose who's watching."
                    loadProfiles()
                    return
                }
                if (result.status == "paired") {
                    pairingStatus = "Phone paired. Opening the library."
                    continueWithPhoneLibrary()
                    return
                }
                if (result.status == "name_required") {
                    screen = ScreenState.Pairing(session, nameRequired = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: ApiException) {
                if (error.statusCode == 410) {
                    PairLog.w("TV pairing expired (410), new PIN")
                    createPairingSession()
                    return
                }
                PairLog.w("TV poll ApiException ${error.statusCode} ${error.userMessage()}", error)
                pairingStatus = error.userMessage()
            } catch (error: Exception) {
                PairLog.w("TV poll failed ${error.message}", error)
                pairingStatus = error.userMessage()
            }
        }
    }

    private suspend fun loadProfiles() {
        val accounts = authStore.getAccounts()
        if (accounts.isEmpty()) {
            token = null
            sessionId = null
            activeProfileId = null
            createPairingSession()
            return
        }

        val allProfiles = mutableListOf<Profile>()
        val newProfileAccounts = mutableMapOf<String, AccountCredential>()
        var hasAuthError = false
        var lastErrorMessage: String? = null

        for (account in accounts) {
            try {
                var result = api.getProfiles(account.token).profiles
                var attempt = 0
                while (result.isEmpty() && attempt < 5) {
                    delay(400)
                    result = api.getProfiles(account.token).profiles
                    attempt++
                }
                if (result.isEmpty()) {
                    authStore.removeAccount(account.sessionId)
                } else {
                    for (p in result) {
                        allProfiles.add(p)
                        newProfileAccounts[p.id] = account
                    }
                }
            } catch (error: ApiException) {
                if (error.statusCode == 401) {
                    hasAuthError = true
                    authStore.removeAccount(account.sessionId)
                } else {
                    lastErrorMessage = error.userMessage()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastErrorMessage = error.userMessage()
            }
        }

        if (allProfiles.isEmpty()) {
            val remainingAccounts = authStore.getAccounts()
            if (remainingAccounts.isEmpty() || hasAuthError) {
                authStore.clear()
                // Revoked or unpaired: nothing of the household's Telegram may stay on this TV.
                telegram.signOutAndWipe()
                token = null
                sessionId = null
                activeProfileId = null
                createPairingSession()
            } else {
                screen = ScreenState.Error("Profiles unavailable", lastErrorMessage ?: "Could not load profiles.", canSignOut = true)
            }
            return
        }

        profiles = allProfiles
        profileAccounts.clear()
        profileAccounts.putAll(newProfileAccounts)

        val activeAccount = activeProfileId?.let { profileAccounts[it] }
            ?: authStore.getActiveAccount()
            ?: accounts.first()
        token = activeAccount.token
        sessionId = activeAccount.sessionId

        screen = ScreenState.ProfilePicker(profiles)
        startCommandPolling()
    }

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private suspend fun loadLibrary(showRefreshIndicator: Boolean = false) {
        val currentToken = token ?: return
        if (cachedVideos.isEmpty()) screen = ScreenState.Loading
        else if (showRefreshIndicator) isLibraryRefreshing = true
        try {
            activeProfileId?.let { migrateLocalMyList(it, currentToken) }
            cachedVideos = api.getVideos(currentToken, lanToken, activeProfileId).videos
            libraryVideos = cachedVideos
            screen = ScreenState.Library
            startLibrarySync()
            startCommandPolling()
            telegram.sync(currentToken)
        } catch (error: ApiException) {
            if (error.statusCode == 401) {
                val currentSessionId = sessionId
                if (currentSessionId != null) {
                    authStore.removeAccount(currentSessionId)
                }
                activeProfileId = null
                loadProfiles()
            } else {
                screen = ScreenState.Error("Library unavailable", error.userMessage(), canSignOut = true)
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (error: Exception) {
            screen = ScreenState.Error("Library unavailable", error.userMessage(), canSignOut = true)
        } finally {
            isLibraryRefreshing = false
        }
    }

    private fun isEmulatorTv(): Boolean {
        val hardware = Build.HARDWARE.lowercase()
        return hardware.contains("goldfish") || hardware.contains("ranchu")
            || Build.FINGERPRINT.contains("generic")
            || tvLanIp?.startsWith("10.0.2.") == true
    }

    private fun startCommandPolling() {
        if (isDemoMode || commandJob?.isActive == true) return
        commandJob = viewModelScope.launch {
            while (true) {
                val accounts = if (screen is ScreenState.ProfilePicker) authStore.getAccounts()
                    else listOfNotNull(token?.let { t -> sessionId?.let { s -> AccountCredential(s, t) } })
                commandDelivery.setAccounts(accounts)
                commandJournal.executing().forEach { record ->
                    val id = record.command.id
                    if (record.command.expiresAtMs == null || record.command.expiresAtMs <= System.currentTimeMillis()) {
                        finishCommand(id, "expired")
                        if (remoteTitleCommand?.first == id) {
                            remoteTitleCommand = null
                            cancelResolving()
                        }
                    } else if (screen is ScreenState.Error || !screen.acceptsCommandPolling()) {
                        finishCommand(id, "screen_unavailable")
                    }
                }
                commandJournal.prune()
                delay(1_000)
            }
        }
    }

    private fun stopCommandDelivery() {
        commandJob?.cancel()
        commandJob = null
        commandJournal.executing().forEach { finishCommand(it.command.id, "session_closed") }
        remoteTitleCommand = null
        commandDelivery.stop()
        playerCommands.clear()
        navCommands.clear()
    }

    override fun onCleared() {
        commandDelivery.stop()
        super.onCleared()
    }

    private fun handleCommand(command: TvCommand, account: AccountCredential) {
        command.validationError()?.let { finishCommand(command.id, it); return }
        if (!screen.acceptsCommandPolling() || isDemoMode) {
            finishCommand(command.id, "screen_unavailable")
            return
        }
        if (screen !is ScreenState.ProfilePicker && account.token != token) {
            finishCommand(command.id, "inactive_account")
            return
        }
        if (command.command == "play" && command.payload.containsKey("videoId")) {
            // Starting a title requires an already selected/unlocked profile.
            // Never acknowledge a title merely stored for future profile selection.
            if (screen is ScreenState.ProfilePicker || activeProfileId == null) {
                finishCommand(command.id, "profile_required")
                return
            }
            val videoId = command.payload["videoId"]?.jsonPrimitive?.content
            val cached = findRemoteTitle(cachedVideos, videoId)
            if (cached != null) {
                startRemoteTitle(command, cached)
                return
            }
            // The library only refreshes while it is on screen, so a title the phone imported during
            // playback is not cached yet. Refresh once before rejecting it as unavailable.
            val currentToken = token
            if (currentToken == null) {
                finishCommand(command.id, "title_unavailable")
                return
            }
            viewModelScope.launch {
                runCatching { api.getVideos(currentToken, lanToken, activeProfileId).videos }
                    .onSuccess { if (token == currentToken) cachedVideos = it }
                val video = findRemoteTitle(cachedVideos, videoId)
                if (video == null || token != currentToken || !screen.acceptsCommandPolling()) {
                    finishCommand(command.id, "title_unavailable")
                } else {
                    startRemoteTitle(command, video)
                }
            }
        } else if (screen is ScreenState.Player) {
            playerCommands.add(command)
        } else if (screen is ScreenState.Resolving && command.command == "stop") {
            cancelResolving()
            finishCommand(command.id)
        } else if (screen !is ScreenState.Resolving && command.command in setOf("move", "select")) {
            navCommands.add(command)
        } else {
            finishCommand(command.id, "not_applicable")
        }
    }

    private fun startRemoteTitle(command: TvCommand, video: Video) {
        if (isLiveChannelId(video.id)) {
            finishCommand(command.id, "title_unavailable")
            return
        }
        play(video)
        remoteTitleCommand = command.id to video.id
    }

    private fun finishCommand(id: String, reason: String? = null) {
        commandJournal.finish(id, reason)
        playerCommands.remove(id)
        navCommands.remove(id)
    }

    private fun finishRemoteTitle(reason: String? = null) {
        val pending = remoteTitleCommand ?: return
        remoteTitleCommand = null
        finishCommand(pending.first, reason)
    }

    fun remotePlaybackResult(videoId: String, reason: String?) {
        if ((screen as? ScreenState.Player)?.video?.id == videoId && remoteTitleCommand?.second == videoId) finishRemoteTitle(reason)
    }

    fun consumePlayerCommand(id: String, reason: String?) { finishCommand(id, reason) }
    fun consumeNavCommand(id: String, reason: String?) { finishCommand(id, reason) }

    /**
     * Checks for new personal-library items without replacing ScreenState.Library.
     * Stable grid keys and the remembered grid state retain the user's tile focus and scroll anchor.
     */
    private fun startLibrarySync() {
        if (isDemoMode) return
        if (librarySyncJob?.isActive == true) return
        librarySyncJob = viewModelScope.launch {
            var lastSignature: String? = null
            var lastEtag: String? = null
            while (true) {
                delay(LIBRARY_SYNC_MS)
                if (screen !is ScreenState.Library) return@launch
                val currentToken = token ?: return@launch
                runCatching { api.getVideosIfChanged(currentToken, lanToken, activeProfileId, lastEtag) }
                    .onSuccess { fetch ->
                        // 304: nothing changed, so the list stays as it is.
                        if (fetch is CablegramApi.LibraryFetch.Changed) {
                            lastEtag = fetch.etag
                            val latest = fetch.library.videos
                            // Cheap staleness check first: a full structural equals of the
                            // library every cycle costs O(n) object-graph compares on the
                            // main thread and thrashes low-memory TVs (ANR 4b063625).
                            val signature = latest.joinToString("|") { v ->
                                "${v.id}:${v.ingestProgress}:${v.ingestStage}:${v.tier}:${v.inMyList}:${v.resumePositionSeconds}:${v.title}:${v.posterUrl}"
                            }
                            if (signature != lastSignature) {
                                lastSignature = signature
                                cachedVideos = latest
                                libraryVideos = latest
                            }
                        }
                        // Network is up again — drain any queued offline progress (R-3).
                        flushProgressQueue()
                    }
                    .onFailure { error -> if (error.isUnauthorized()) onSessionUnauthorized(currentToken) }
            }
        }
    }

    private fun Throwable.isUnauthorized() = (this as? ApiException)?.statusCode == 401

    /** 4xx other than auth/timeouts/rate limits: retrying the same request cannot succeed. */
    private fun Throwable.isPermanentRejection(): Boolean {
        val code = (this as? ApiException)?.statusCode ?: return false
        return code in 400..499 && code != 401 && code != 408 && code != 429
    }

    /** Spec 004 US5: the phone deleted the title (410 media_deleted) while it was playing. */
    private fun onMediaDeleted(videoId: String) {
        cachedVideos = cachedVideos.filter { it.id != videoId }
        if ((screen as? ScreenState.Player)?.video?.id != videoId) return
        telegram.releasePlayback()
        failedPlaybackVideo = null
        screen = ScreenState.Error(title = "This video was deleted", message = "It was removed from your library on the phone.", canGoBack = true)
    }

    private fun onProfileGone() {
        if (activeProfileId == null) return
        PairLog.w("Active profile rejected (403); back to the profile picker")
        activeProfileId = null
        screen = ScreenState.Loading
        viewModelScope.launch { loadProfiles() }
    }

    /**
     * The control plane rejected this TV's token (revoked from the phone, or expired). Before this,
     * background calls swallowed the 401, so a removed TV kept playing and browsing from cache.
     */
    private fun onSessionUnauthorized(rejectedToken: String) {
        if (token != rejectedToken) return
        PairLog.w("TV session rejected (401); signing this session out")
        token = null // also stops the player's dispose-time progress write from re-entering here
        stopCommandDelivery()
        val rejectedSession = sessionId
        activeProfileId = null
        screen = ScreenState.Loading
        viewModelScope.launch {
            rejectedSession?.let { authStore.removeAccount(it) }
            loadProfiles()
        }
    }

    private fun playbackError(error: Exception): String = when {
        (error as? ApiException)?.statusCode == 409 ->
            "This video's source changed on your phone. Open Cablegram on the phone to refresh it."
        else -> error.userMessage()
    }

    private fun Exception.userMessage(): String = message ?: "Check the backend address and network connection."

    private companion object {
        // New phone imports appear within a few seconds without re-fetching the
        // whole catalog and device list every two seconds.
        const val LIBRARY_SYNC_MS = 5_000L
    }
}

private fun isReviewerSelectKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_BUTTON_A,
    KeyEvent.KEYCODE_BUTTON_SELECT,
    -> true
    else -> false
}

private fun reviewerDigit(keyCode: Int): Char? = when (keyCode) {
    KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> '0'
    KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> '1'
    KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> '2'
    KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> '3'
    KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> '4'
    KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5 -> '5'
    KeyEvent.KEYCODE_6, KeyEvent.KEYCODE_NUMPAD_6 -> '6'
    KeyEvent.KEYCODE_7, KeyEvent.KEYCODE_NUMPAD_7 -> '7'
    KeyEvent.KEYCODE_8, KeyEvent.KEYCODE_NUMPAD_8 -> '8'
    KeyEvent.KEYCODE_9, KeyEvent.KEYCODE_NUMPAD_9 -> '9'
    else -> null
}
