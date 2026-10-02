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
        data class Sent(val tvName: String, val commandId: String, val token: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Sends to the TV selected on this phone. A selected TV the household no longer lists fails
     * instead of falling back to another TV. [household] is the caller's cached list; it is
     * refreshed from the server only when it does not already contain the selected TV.
     */
    suspend fun send(
        pairing: PairingStore,
        client: CatalogClient,
        command: String,
        videoId: String? = null,
        arguments: JsonObject = buildJsonObject {},
        household: List<MeDevice>? = null,
    ): Result {
        val token = pairing.accountToken
        if (token.isNullOrBlank()) return Result.Failed("Sign in to use the remote.")
        val selected = pairing.tvs.lastOrNull()
        var list = household
        if (list == null || selected != null && list.none { it.id == selected.deviceId }) list = client.householdTvs(token) ?: list
        val target = when (val resolved = resolveTarget(selected, list)) {
            is TargetResult.Failed -> return Result.Failed(resolved.message)
            is TargetResult.Target -> resolved
        }
        return when (val sent = client.postCommand(token, command, videoId, target.deviceId, arguments)) {
            is CommandSend.Accepted -> Result.Sent(target.name, sent.id, token)
            CommandSend.Failed -> Result.Failed("Could not reach the control service. Check your connection and try again.")
        }
    }

    /** Waits for the TV to confirm a command the control plane accepted. */
    suspend fun confirm(client: CatalogClient, sent: Result.Sent, wait: ConfirmationWait): Outcome =
        awaitOutcome(sent.commandId, wait, { client.commandStatus(sent.token, it) })
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
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val pairing = PairingStore(context)
                val client = CatalogClient(pairing.apiBaseUrl, pairing)
                when (val result = CastSession.send(pairing, client, command, arguments = arguments)) {
                    is CastSession.Result.Failed -> PairLog.e("Notification remote $command failed: ${result.message}", null)
                    is CastSession.Result.Sent -> {
                        // Stay under the receiver's ~10 s window; update only what the TV confirmed.
                        val outcome = CastSession.confirm(client, result, ConfirmationWait.Control(NOTIFICATION_WAIT_MS))
                        if (outcome == Outcome.Confirmed || outcome == Outcome.Unconfirmable) when (intent.action) {
                            ACTION_TOGGLE -> CastSession.update(cast.copy(paused = command == "pause"))
                            ACTION_STOP -> CastSession.update(null)
                        } else PairLog.e("Notification remote $command not confirmed: $outcome", null)
                    }
                }
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
        private const val NOTIFICATION_WAIT_MS = 7_000L
    }
}
