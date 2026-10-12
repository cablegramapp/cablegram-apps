package app.cablegram.phone

import kotlinx.coroutines.delay

/** Result of asking the control plane to accept a command (not of the TV running it). */
sealed interface CommandSend {
    data class Accepted(val id: String) : CommandSend
    data object Failed : CommandSend
    /** The control plane no longer knows the target TV (revoked or removed), as opposed to being unreachable. */
    data object TargetGone : CommandSend
}

/** What the control plane reports for a command. [Unsupported] is a server without the status endpoint. */
sealed interface CommandStatus {
    /**
     * [state] is pending, delivered, completed or rejected. [expiresInMs] is the time left before the
     * command expires, by the server's clock (null from a server that does not send it).
     */
    data class Known(val state: String, val reason: String?, val expiresInMs: Long? = null) : CommandStatus
    data object Unsupported : CommandStatus
}

sealed interface Outcome {
    data object Confirmed : Outcome
    data class Rejected(val reason: String?) : Outcome
    data object TimedOut : Outcome
    /** The server cannot report outcomes yet, so the TV's result is unknown. */
    data object Unconfirmable : Outcome
    /** A title start the TV never picked up, and the TV has gone offline (CAB-58). */
    data object Unreachable : Outcome
}

/** How long to wait: a control gives up quickly; a title start waits until the command expires. */
sealed interface ConfirmationWait {
    data class Control(val capMs: Long = CONTROL_CAP_MS) : ConfirmationWait
    data object TitleStart : ConfirmationWait
}

const val CONTROL_CAP_MS = 8_000L
private const val FAST_POLL_MS = 500L
private const val SLOW_POLL_MS = 1_000L
private const val FAST_POLL_WINDOW_MS = 2_000L
// Slack past the command's expiry so the server's own "expired" verdict is read.
private const val EXPIRY_GRACE_MS = 1_500L
// Upper bound for a title start whatever the server reports (its TTL is 300 s).
private const val TITLE_START_MAX_MS = 310_000L
// CAB-58: a title start the TV has not picked up after this long is checked against the TV's presence.
const val UNREACHABLE_GRACE_MS = 5_000L
private const val PRESENCE_CHECK_MS = 3_000L

/**
 * Polls the command's status until the TV confirms or rejects it, or the wait ends.
 * Free of Android dependencies: [fetch] and [clock] are injected for tests.
 *
 * CAB-58: a title start waits up to the command's 300 s life, which suits a TV that is busy, not one that is gone.
 * The server counts a TV as on for about 15 s after it last polled, so a title can be sent to a TV that was just
 * closed or lost its network, and nothing would ever pick it up. While the TV has not picked the title up,
 * [tvOnline] is asked now and then; once it says the TV is offline, the wait ends as [Outcome.Unreachable].
 */
suspend fun awaitOutcome(
    id: String,
    wait: ConfirmationWait,
    fetch: suspend (String) -> CommandStatus?,
    clock: () -> Long = System::currentTimeMillis,
    tvOnline: (suspend () -> Boolean?)? = null,
): Outcome {
    val start = clock()
    var presenceCheckedAt = Long.MIN_VALUE / 2
    var deadline = start + when (wait) {
        is ConfirmationWait.Control -> wait.capMs
        ConfirmationWait.TitleStart -> TITLE_START_MAX_MS
    }
    while (true) {
        when (val status = fetch(id)) {
            CommandStatus.Unsupported -> return Outcome.Unconfirmable
            is CommandStatus.Known -> when (status.state) {
                "completed" -> return Outcome.Confirmed
                "rejected" -> return Outcome.Rejected(status.reason)
                // Anchored on this phone's clock at the read: the server's absolute expiry would
                // end the wait early on a phone whose clock runs ahead.
                else -> {
                    if (wait == ConfirmationWait.TitleStart && status.expiresInMs != null) {
                        deadline = minOf(clock() + status.expiresInMs + EXPIRY_GRACE_MS, start + TITLE_START_MAX_MS)
                    }
                    val at = clock()
                    if (wait == ConfirmationWait.TitleStart && status.state == "pending" && tvOnline != null &&
                        at - start >= UNREACHABLE_GRACE_MS && at - presenceCheckedAt >= PRESENCE_CHECK_MS) {
                        presenceCheckedAt = at
                        if (tvOnline() == false) return Outcome.Unreachable
                    }
                }
            }
            null -> Unit // a transient read failure; keep polling until the deadline
        }
        val now = clock()
        if (now >= deadline) return Outcome.TimedOut
        delay(if (now - start < FAST_POLL_WINDOW_MS) FAST_POLL_MS else SLOW_POLL_MS)
    }
}
