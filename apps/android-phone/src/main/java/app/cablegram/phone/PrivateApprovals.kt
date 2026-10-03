package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * T075 / R-5 (Constitution VI): while the phone is paired, poll the control
 * plane for private playback requests raised by the TV and surface each as a
 * high-priority notification with Allow / Not now actions. Approval redeems
 * the one-time token the TV uses; deny / expiry means the TV never starts
 * playback.
 *
 * The watcher runs inside [LanLibraryService] so the phone shows one ongoing
 * notification; only an actual request produces an approval notification.
 */
class ApprovalWatcher(private val context: Context, private val scope: CoroutineScope) {
    private val store = PairingStore(context)
    private val client = CatalogClient(store.apiBaseUrl, store)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val seen = mutableSetOf<String>()
            while (isActive) {
                val token = store.accountToken
                if (token.isNullOrBlank()) {
                    PrivateApprovals.pending.value = emptyList()
                } else {
                    runCatching { client.fetchPendingPrivateApprovals(token) }.onSuccess { pending ->
                        // The in-app card reads this, so the app does not poll a second time.
                        PrivateApprovals.pending.value = pending
                        // Notify only new attempts; an approved/denied/expired attempt stays out.
                        pending.filter { seen.add(it.attemptId) }.forEach { approval ->
                            PairLog.i("Approval pending attempt=${PairLog.pinTail(approval.attemptId)} title=${approval.title}")
                            PrivateApprovals.notifyPending(context, approval)
                        }
                        // Withdrawn, expired, or answered elsewhere: drop the
                        // stale notification so it cannot be approved late.
                        val live = pending.map { it.attemptId }.toSet()
                        seen.filterNot { it in live }.forEach { gone ->
                            PrivateApprovals.cancelNotification(context, gone)
                            seen.remove(gone)
                        }
                    }.onFailure { PairLog.e("Approval watch poll failed", it) }
                }
                delay(approvalPollDelayMs(PrivateApprovals.appVisible))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

}

/** The one poll for pending approvals: quick while the app is on screen, slow in the background. */
internal fun approvalPollDelayMs(appVisible: Boolean): Long = if (appVisible) 6_000L else 15_000L

object PrivateApprovals {
    /** What the single poller ([ApprovalWatcher]) last saw; the in-app approval card shows this list. */
    val pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    /** True while the app is on screen. */
    @Volatile var appVisible = false
    private const val CHANNEL_ID = "private_approvals"
    /** Channel of the removed standalone watcher service; deleted so it leaves system settings. */
    private const val LEGACY_WATCH_CHANNEL_ID = "private_approvals_watcher"
    const val ACTION_APPROVE = "app.cablegram.phone.PRIVATE_APPROVE"
    const val ACTION_DENY = "app.cablegram.phone.PRIVATE_DENY"
    const val EXTRA_ATTEMPT_ID = "attempt_id"

    fun notifyPending(context: Context, approval: PendingApproval) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Playback approvals", NotificationManager.IMPORTANCE_HIGH))
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context,
            code,
            Intent(context, PrivateApprovalReceiver::class.java).setAction(name).putExtra(EXTRA_ATTEMPT_ID, approval.attemptId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("${approval.tvName?.takeIf { it.isNotBlank() } ?: "Your TV"} wants to play a private title")
            .setContentText(approval.title ?: "A private title is waiting for your approval")
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setCategory(Notification.CATEGORY_REMINDER)
            // Tapping opens the app, where the same request is shown as a card.
            .setContentIntent(PhoneActivity.openIntent(context))
            .addAction(Notification.Action.Builder(null, "Allow once", action(ACTION_APPROVE, approval.attemptId.hashCode())).build())
            .addAction(Notification.Action.Builder(null, "Not now", action(ACTION_DENY, approval.attemptId.hashCode() + 1)).build())
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notifyId(approval.attemptId), notification) }
    }

    /** T075: dismiss the OS notification after responding from inside the app. */
    fun cancelNotification(context: Context, attemptId: String) {
        context.getSystemService(NotificationManager::class.java)?.cancel(notifyId(attemptId))
    }

    fun deleteLegacyChannel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(LEGACY_WATCH_CHANNEL_ID) }
    }

    private fun notifyId(attemptId: String) = 2000 + (attemptId.hashCode() and 0xFFFF)
}

/** Handles Allow once / Not now from an approval notification, even when no service is running. */
class PrivateApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val attemptId = intent.getStringExtra(PrivateApprovals.EXTRA_ATTEMPT_ID) ?: return
        val action = intent.action
        val pending = goAsync()
        PrivateApprovals.cancelNotification(context, attemptId)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = PairingStore(context)
                val token = store.accountToken
                if (token.isNullOrBlank()) return@launch
                val client = CatalogClient(store.apiBaseUrl, store)
                when (action) {
                    PrivateApprovals.ACTION_APPROVE -> runCatching { client.approvePrivatePlayback(token, attemptId) }
                        .onSuccess { approved -> PairLog.i("Approval sent ok=${approved != null} attempt=${PairLog.pinTail(attemptId)}") }
                        .onFailure { PairLog.e("Approval failed attempt=${PairLog.pinTail(attemptId)}", it) }
                    PrivateApprovals.ACTION_DENY -> runCatching { client.denyPrivatePlayback(token, attemptId) }
                        .onSuccess { denied -> PairLog.i("Deny sent ok=$denied attempt=${PairLog.pinTail(attemptId)}") }
                        .onFailure { PairLog.e("Deny failed attempt=${PairLog.pinTail(attemptId)}", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
