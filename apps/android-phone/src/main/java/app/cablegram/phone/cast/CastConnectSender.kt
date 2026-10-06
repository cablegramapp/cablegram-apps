package app.cablegram.phone.cast

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouter
import app.cablegram.phone.BuildConfig
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Called on the main thread. Discovery is active only while the app/picker is visible. */
class CastConnectSender(context: Context) {
    private val cast = if (BuildConfig.CABLEGRAM_CAST_APP_ID.isNotBlank() &&
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS)
        runCatching { CastContext.getSharedInstance(context) }.getOrNull() else null
    private val router = MediaRouter.getInstance(context)
    private val routeState = MutableStateFlow<List<CastRoute>>(emptyList())
    private val deviceStatus = MutableStateFlow<CastDeviceStatus?>(null)
    val routes = routeState.asStateFlow()
    val available get() = cast != null
    val playbackTerminated: Boolean get() {
        if (cast?.sessionManager?.currentCastSession?.isConnected != true) return true
        val status = client?.mediaStatus ?: return false
        return terminalCastIdle(status.playerState, status.idleReason)
    }
    var onDevice: ((CastDeviceStatus, String) -> Unit)? = null
    var onPlayback: ((Boolean?, Boolean) -> Unit)? = null
    private var pendingConnection: CompletableDeferred<Unit>? = null
    private var client: RemoteMediaClient? = null
    private var visible = false

    private fun castId(route: MediaRouter.RouteInfo): String? = route.extras?.let { CastDevice.getFromBundle(it)?.deviceId }
    private fun refreshRoutes() {
        val selector = cast?.mergedSelector ?: return
        routeState.value = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }
            .mapNotNull { route -> castId(route)?.let { CastRoute(it, route.name.toString()) } }
    }
    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refreshRoutes()
    }
    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = client?.mediaStatus ?: return
            val id = cast?.sessionManager?.currentCastSession?.castDevice?.deviceId ?: return
            status.customData?.let { data ->
                val own = data.opt("deviceId") as? String
                if (!own.isNullOrBlank()) {
                    val value = CastDeviceStatus(own, data.optString("reason") == "wrong_tv")
                    deviceStatus.value = value
                    onDevice?.invoke(value, id)
                }
            }
            when {
                terminalCastIdle(status.playerState, status.idleReason) -> onPlayback?.invoke(null, true)
                status.playerState == MediaStatus.PLAYER_STATE_PAUSED -> onPlayback?.invoke(true, false)
                status.playerState == MediaStatus.PLAYER_STATE_PLAYING -> onPlayback?.invoke(false, false)
            }
        }
    }
    private fun attach(session: CastSession) {
        client?.unregisterCallback(mediaCallback)
        client = session.remoteMediaClient
        client?.registerCallback(mediaCallback)
        pendingConnection?.complete(Unit)
    }
    private fun ended() {
        client?.unregisterCallback(mediaCallback); client = null
        pendingConnection?.completeExceptionally(IllegalStateException("Cast session ended"))
        onPlayback?.invoke(null, true)
    }
    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pendingConnection?.completeExceptionally(IllegalStateException("Cast connection failed"))
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) = ended()
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) = ended()
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }
    init {
        cast?.sessionManager?.addSessionManagerListener(sessionListener, CastSession::class.java)
        cast?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let(::attach)
    }

    fun setVisible(value: Boolean) {
        visible = value
        router.removeCallback(routerCallback)
        if (value) cast?.mergedSelector?.let {
            router.addCallback(it, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            refreshRoutes()
        }
    }
    fun scanForPicker() {
        cast?.mergedSelector?.takeIf { visible }?.let {
            router.removeCallback(routerCallback)
            router.addCallback(it, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
            refreshRoutes()
        }
    }

    suspend fun load(routeId: String, commandId: String, targetDeviceId: String, videoId: String, title: String, poster: String?): CastDeviceStatus? {
        val context = cast ?: error("Cast unavailable")
        val session = context.sessionManager.currentCastSession
        if (session?.isConnected != true || session.castDevice?.deviceId != routeId) {
            val route = router.routes.firstOrNull { castId(it) == routeId && it.isEnabled } ?: error("Cast device unavailable")
            val waiter = CompletableDeferred<Unit>()
            pendingConnection = waiter
            try { route.select(); withTimeout(30_000) { waiter.await() } }
            finally { if (pendingConnection === waiter) pendingConnection = null }
        }
        val connected = context.sessionManager.currentCastSession
        check(connected?.isConnected == true && connected.castDevice?.deviceId == routeId) { "Cast device changed" }
        val remote = connected.remoteMediaClient ?: error("Cast device unavailable")
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            publicPosterUrl(poster)?.let { addImage(WebImage(Uri.parse(it))) }
        }
        deviceStatus.value = null
        val request = MediaLoadRequestData.Builder().setMediaInfo(MediaInfo.Builder(videoId)
            .setContentType("video/mp4").setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setMetadata(metadata).build())
            .setCustomData(JSONObject().put("commandId", commandId).put("targetDeviceId", targetDeviceId)).build()
        withTimeout(30_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val pending = remote.load(request)
                pending.setResultCallback { result ->
                    if (continuation.isActive) {
                        if (result.status.isSuccess) continuation.resume(Unit)
                        else continuation.resumeWithException(IllegalStateException("TV did not accept Cast launch"))
                    }
                }
                continuation.invokeOnCancellation { pending.cancel() }
            }
        }
        // The TV reads its own stored credentials asynchronously after LOAD. Wait for its
        // identity instead of deciding retry from a stale or absent immediate load response.
        val value = withTimeoutOrNull(5_000) { deviceStatus.first { it != null } }
            ?: error("TV did not identify itself. Try again.")
        return value.copy(wrongTv = value.wrongTv || value.deviceId != targetDeviceId)
    }

    fun release() {
        setVisible(false)
        client?.unregisterCallback(mediaCallback)
        cast?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        pendingConnection?.cancel()
        onDevice = null; onPlayback = null
    }
}
