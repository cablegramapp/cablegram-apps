package app.cablegram.phone.cast

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import app.cablegram.phone.BuildConfig
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import org.json.JSONObject
import java.util.UUID

/** Throwaway sender: route selection deliberately never automatically sends LOAD. */
class SpikeSenderActivity : Activity() {
    private lateinit var router: MediaRouter
    private lateinit var routes: LinearLayout
    private lateinit var status: TextView
    private lateinit var context: CastContext
    private val selector by lazy { MediaRouteSelector.Builder().addControlCategory(
        CastMediaControlIntent.categoryForCast(BuildConfig.CABLEGRAM_CAST_APP_ID)).build() }
    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) { renderRoutes() }
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) { renderRoutes() }
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) { renderRoutes() }
        override fun onRouteSelected(router: MediaRouter, route: MediaRouter.RouteInfo) {
            Log.i("CAB20_SPIKE", "ROUTE_SELECTED wallMs=${System.currentTimeMillis()} name=${route.name}")
            status.text = "Route selected: ${route.name}. Wait, then inspect session."
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(BuildConfig.CABLEGRAM_CAST_APP_ID.isNotBlank())
        context = CastContext.getSharedInstance(this)
        router = MediaRouter.getInstance(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        status = TextView(this).apply { text = "CAB-20 Cast spike ${BuildConfig.CABLEGRAM_CAST_APP_ID}" }
        layout.addView(status)
        routes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(routes)
        fun button(label: String, action: () -> Unit) { layout.addView(Button(this).apply {
            text = label; setOnClickListener { action() }
        }) }
        button("Inspect session") {
            val session = context.sessionManager.currentCastSession
            status.text = "Session connected: ${session?.isConnected == true}"
            Log.i("CAB20_SPIKE", "SESSION wallMs=${System.currentTimeMillis()} connected=${session?.isConnected == true}")
        }
        button("Send synthetic LOAD") {
            val client = context.sessionManager.currentCastSession?.remoteMediaClient
            if (client == null) { status.text = "No connected Cast session"; return@button }
            val commandId = UUID.randomUUID().toString()
            val data = JSONObject().put("commandId", commandId).put("targetDeviceId", "cab20-spike-tv")
            val media = MediaInfo.Builder("cab20-spike-title")
                .setContentType("video/mp4").setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setMetadata(MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
                    putString(MediaMetadata.KEY_TITLE, "CAB-20 spike — no playback")
                }).build()
            val request = MediaLoadRequestData.Builder().setMediaInfo(media).setCustomData(data).build()
            Log.i("CAB20_SPIKE", "SEND_LOAD wallMs=${System.currentTimeMillis()} customData=$data")
            status.text = "LOAD sent: $commandId"
            client.load(request).setResultCallback { result ->
                Log.i("CAB20_SPIKE", "LOAD_RESULT wallMs=${System.currentTimeMillis()} commandId=$commandId status=${result.status.statusCode}")
                status.text = "LOAD result ${result.status.statusCode}: $commandId"
            }
        }
        button("End Cast session") { context.sessionManager.endCurrentSession(true); status.text = "Session ended" }
        setContentView(layout)
    }
    override fun onStart() {
        super.onStart()
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        renderRoutes()
    }
    override fun onStop() { router.removeCallback(callback); super.onStop() }
    private fun renderRoutes() {
        routes.removeAllViews()
        router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }.forEach { route ->
            routes.addView(Button(this).apply { text = "Connect: ${route.name}"; setOnClickListener {
                Log.i("CAB20_SPIKE", "CONNECT wallMs=${System.currentTimeMillis()} name=${route.name}")
                route.select()
            } })
        }
    }
}
