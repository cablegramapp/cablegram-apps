package app.cablegram.cast

import android.app.Application
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.cablegram.BuildConfig
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

class SpikeApplication : Application(), DefaultLifecycleObserver {
    private lateinit var session: MediaSessionCompat
    override fun onCreate() {
        super<Application>.onCreate()
        require(BuildConfig.CABLEGRAM_CAST_APP_ID.isNotBlank()) { "Configure the Cast spike app ID" }
        CastReceiverContext.initInstance(this)
        val manager = CastReceiverContext.getInstance().mediaManager
        session = MediaSessionCompat(this, "CablegramCastSpike")
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(PlaybackStateCompat.STATE_NONE, 0, 0f).build())
        session.isActive = true
        manager.setSessionCompatToken(session.sessionToken)
        manager.setMediaLoadCommandCallback(object : MediaLoadCommandCallback() {
            override fun onLoad(senderId: String?, request: MediaLoadRequestData): Task<MediaLoadRequestData> {
                val data = request.customData
                val keys = data?.keys()?.asSequence()?.toSet()
                if (keys != setOf("commandId", "targetDeviceId") ||
                    data?.opt("commandId") !is String || data.opt("targetDeviceId") !is String ||
                    data.getString("commandId").isBlank() || data.getString("targetDeviceId").isBlank()) {
                    Log.w("CAB20_SPIKE", "LOAD_REJECTED wallMs=${System.currentTimeMillis()}")
                    return Tasks.forException(IllegalArgumentException("Malformed Cast launch data"))
                }
                // Only synthetic public IDs are logged. Do not log request/intent extras or credentials.
                Log.i("CAB20_SPIKE", "LOAD wallMs=${System.currentTimeMillis()} customData=$data")
                manager.setDataFromLoad(request)
                manager.broadcastMediaStatus()
                return Tasks.forResult(request)
            }
        })
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }
    override fun onStart(owner: LifecycleOwner) { CastReceiverContext.getInstance().start() }
    override fun onStop(owner: LifecycleOwner) { CastReceiverContext.getInstance().stop() }
}
