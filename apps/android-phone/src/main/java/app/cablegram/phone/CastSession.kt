package app.cablegram.phone

import app.cablegram.phone.cast.castCommandTarget
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
import kotlinx.coroutines.withTimeoutOrNull
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
        /** [switchedFrom] as in [TargetResult.Target]. */
        data class Sent(val tvName: String, val commandId: String, val token: String, val switchedFrom: String? = null, val deviceId: String? = null) : Result
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
        castTargetDeviceId: String? = null,
    ): Result {
        val token = pairing.accountToken
        if (token.isNullOrBlank()) return Result.Failed("Sign in to use the remote.")
        val selected = if (castTargetDeviceId == null) pairing.tvs.lastOrNull()
            else pairing.tvs.firstOrNull { it.deviceId == castTargetDeviceId }
                ?: return Result.Failed("Choose a paired TV in Settings.")
        var list = household
        // A failed refresh means offline (null), not the stale cache: offline sends to the stored
        // device ID, and the server refuses a revoked TV. A title start always asks afresh which TVs are on.
        val titleStart = command == "play" && videoId != null
        if (list == null || titleStart || selected != null && list.none { it.id == selected.deviceId }) {
            list = client.householdTvs(token)
        }
        val target = when (val resolved = if (castTargetDeviceId != null)
            castCommandTarget(selected, list) else resolveTarget(selected, list)) {
            is TargetResult.Failed -> return Result.Failed(resolved.message)
            is TargetResult.Target -> resolved
        }
        // A TV without Cablegram open can't take a title: when it opens it shows "Who's watching?", and a title is never
        // kept for a profile chosen later. Say so now instead of waiting on it. Only from a fresh list, never the cache.
        if (titleStart && target.offline) {
            return Result.Failed("${target.name} isn't on. Open Cablegram on it, choose who's watching, then try again.")
        }
        return when (val sent = client.postCommand(token, command, videoId, target.deviceId, arguments)) {
            is CommandSend.Accepted -> {
                // The TV that is on becomes the current one, so the remote and the next title go there too.
                target.switchedFrom?.let { pairing.tvs.firstOrNull { tv -> tv.deviceId == target.deviceId } }
                    ?.let { tv -> pairing.tvs = pairing.tvs.filterNot { it.pin == tv.pin } + tv }
                Result.Sent(target.name, sent.id, token, target.switchedFrom, target.deviceId)
            }
            CommandSend.TargetGone -> Result.Failed("${target.name} is no longer connected. Choose a TV in Settings.")
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
                // The whole send and wait stays under the receiver's ~10 s window.
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    when (val result = CastSession.send(pairing, client, command, arguments = arguments)) {
                        is CastSession.Result.Failed -> PairLog.e("Notification remote $command failed: ${result.message}", null)
                        is CastSession.Result.Sent -> {
                            val outcome = CastSession.confirm(client, result, ConfirmationWait.Control(NOTIFICATION_WAIT_MS))
                            // Update only what the TV confirmed, and only if the same title is still current.
                            val now = CastSession.state.value
                            if (outcome != Outcome.Confirmed && outcome != Outcome.Unconfirmable) {
                                PairLog.e("Notification remote $command not confirmed: $outcome", null)
                            } else if (now?.videoId == cast.videoId) when (intent.action) {
                                ACTION_TOGGLE -> CastSession.update(now.copy(paused = command == "pause"))
                                ACTION_STOP -> CastSession.update(null)
                            }
                        }
                    }
                } ?: PairLog.e("Notification remote $command ran out of time", null)
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
        const val SEEK_SECONDS = 10
        private const val NOTIFICATION_WAIT_MS = 7_000L
        private const val RECEIVER_BUDGET_MS = 9_000L
    }
}
