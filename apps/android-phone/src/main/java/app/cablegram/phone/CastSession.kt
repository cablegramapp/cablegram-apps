package app.cablegram.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What this phone last started on a TV; shared by the app UI and the ongoing notification's remote. */
data class Cast(val videoId: String, val title: String, val tvName: String, val paused: Boolean)

object CastSession {
    private val current = MutableStateFlow<Cast?>(null)
    val state: StateFlow<Cast?> = current.asStateFlow()

    fun update(cast: Cast?) {
        current.value = cast
    }

    sealed interface Result {
        data class Sent(val tvName: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Sends to this phone's TV while it is still active in the household,
     * otherwise to an active household TV: a TV paired by another household
     * phone must be controllable too.
     */
    suspend fun send(
        pairing: PairingStore,
        client: CatalogClient,
        command: String,
        videoId: String? = null,
        arguments: JsonObject = buildJsonObject {},
    ): Result {
        val token = pairing.accountToken
        if (token.isNullOrBlank()) return Result.Failed("Sign in to use the remote.")
        val local = pairing.tvs.lastOrNull()
        val household = client.householdTvs(token)
        val target: Pair<String, String> = when {
            household == null -> local?.deviceId?.let { it to local.name }
            else -> household.firstOrNull { it.id == local?.deviceId }?.let { it.id to local!!.name }
                ?: household.firstOrNull()?.let { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: "TV") }
        } ?: return Result.Failed("No TV is paired with this household. Pair a TV to use the remote.")
        val sent = client.postCommand(token, command, videoId, target.first, arguments)
        return if (sent) Result.Sent(target.second) else Result.Failed("Could not reach the control service. Check your connection and try again.")
    }
}

/** The remote buttons on the ongoing notification while something plays on the TV. */
class CastRemoteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cast = CastSession.state.value ?: return
        val (command, arguments) = when (intent.action) {
            ACTION_REWIND -> "seek" to buildJsonObject { put("seconds", -SEEK_SECONDS) }
            ACTION_FORWARD -> "seek" to buildJsonObject { put("seconds", SEEK_SECONDS) }
            // A play command without a video ID resumes the current player.
            ACTION_TOGGLE -> (if (cast.paused) "play" else "pause") to buildJsonObject {}
            ACTION_STOP -> "stop" to buildJsonObject {}
            else -> return
        }
        // Reflect the change right away, as the in-app remote does.
        when (intent.action) {
            ACTION_TOGGLE -> CastSession.update(cast.copy(paused = !cast.paused))
            ACTION_STOP -> CastSession.update(null)
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val pairing = PairingStore(context)
                val result = CastSession.send(pairing, CatalogClient(pairing.apiBaseUrl, pairing), command, arguments = arguments)
                if (result is CastSession.Result.Failed) PairLog.e("Notification remote $command failed: ${result.message}", null)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REWIND = "app.cablegram.phone.REMOTE_REWIND"
        const val ACTION_TOGGLE = "app.cablegram.phone.REMOTE_TOGGLE"
        const val ACTION_FORWARD = "app.cablegram.phone.REMOTE_FORWARD"
        const val ACTION_STOP = "app.cablegram.phone.REMOTE_STOP"
        const val SEEK_SECONDS = 15
    }
}
