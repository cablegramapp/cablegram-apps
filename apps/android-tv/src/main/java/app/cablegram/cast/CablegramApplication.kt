package app.cablegram.cast

import android.app.Application
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.cablegram.BuildConfig
import app.cablegram.data.LanPosterAuth
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.google.android.gms.cast.MediaInfo
import org.json.JSONObject
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

class CablegramApplication : Application(), DefaultLifecycleObserver, SingletonImageLoader.Factory {
    var mediaSession: TvMediaSession? = null
        private set
    private var receiver: CastReceiverContext? = null
    private var queuedLaunch: CastLaunch? = null
    private var onLaunch: ((CastLaunch) -> Unit)? = null

    override fun onCreate() {
        super<Application>.onCreate()
        if (BuildConfig.CABLEGRAM_CAST_APP_ID.isBlank()) return
        // Non-Cast TVs keep the existing relay path even when a receiver ID is configured.
        runCatching {
            CastReceiverContext.initInstance(this)
            val context = CastReceiverContext.getInstance()
            val manager = context.mediaManager
            mediaSession = TvMediaSession(this, manager)
            manager.setMediaLoadCommandCallback(object : MediaLoadCommandCallback() {
                override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
                    val launch = CastLaunch.parse(request.customData?.toString())
                        ?: return Tasks.forException(IllegalArgumentException("Malformed Cast launch data"))
                    // Initialize the Cast media session without echoing untrusted URLs or metadata.
                    val resolved = MediaLoadRequestData.Builder()
                        .setMediaInfo(MediaInfo.Builder(launch.commandId)
                            .setContentType("video/mp4").setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).build())
                        .setCustomData(JSONObject().put("commandId", launch.commandId)
                            .put("targetDeviceId", launch.targetDeviceId)).build()
                    manager.setDataFromLoad(resolved)
                    mediaSession?.restoreMetadataAfterLoad()
                    // Neither contentId nor incoming metadata participates in media resolution.
                    onLaunch?.invoke(launch) ?: run { queuedLaunch = launch }
                    return Tasks.forResult(resolved)
                }
            })
            receiver = context
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }.onFailure { receiver = null; mediaSession = null }
    }

    /**
     * Posters from the phone need the TV's capability as a header, which [LanPosterAuth] adds (CAB-44), and come over TLS
     * with the phone's pinned certificate (CAB-48).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = {
                app.cablegram.data.LanTls.pinned(okhttp3.OkHttpClient.Builder().addInterceptor(LanPosterAuth.interceptor).build())
            }))
        }
        .build()

    fun bindLaunchHandler(handler: (CastLaunch) -> Unit) {
        onLaunch = handler
        queuedLaunch?.let { queuedLaunch = null; handler(it) }
    }

    fun unbindLaunchHandler(handler: (CastLaunch) -> Unit) {
        if (onLaunch === handler) onLaunch = null
    }

    fun handleIntent(intent: Intent?) { intent?.let { receiver?.mediaManager?.onNewIntent(it) } }
    val hasCastSenders: Boolean get() = receiver?.senders?.isNotEmpty() == true
    override fun onStart(owner: LifecycleOwner) { receiver?.start() }
    override fun onStop(owner: LifecycleOwner) { receiver?.stop() }
}
