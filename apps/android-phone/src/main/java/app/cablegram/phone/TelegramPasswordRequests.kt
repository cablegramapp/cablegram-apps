package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.cablegram.telegram.PasswordSeal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A TV that Telegram asks for the two-step password shows it here as a notification with a text field, so the phone's
 * keyboard can be used. The password is sealed to the TV's one-time key before it leaves the phone: the control plane
 * relays bytes it cannot read, and nothing is stored or logged here.
 */
object TelegramPasswordRequests {
    private const val CHANNEL = "telegram_tv_password"
    private const val NOTIFICATION_ID = 7305
    internal const val ACTION_ENTER = "app.cablegram.phone.telegram.TV_PASSWORD"
    internal const val ACTION_CANCEL = "app.cablegram.phone.telegram.TV_PASSWORD_CANCEL"
    internal const val EXTRA_REQUEST = "request_id"
    internal const val KEY_PASSWORD = "telegram_password"

    /** The request the user typed for, taken from the server's current list and never from the intent. */
    internal fun requestFor(pending: List<PendingTvPasswordRequest>?, requestId: String): PendingTvPasswordRequest? =
        pending?.firstOrNull { it.requestId == requestId }

    fun ask(context: Context, request: PendingTvPasswordRequest, failed: Boolean = false) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Telegram password for TVs", NotificationManager.IMPORTANCE_HIGH))
        }
        fun intent(action: String) = Intent(context, TelegramPasswordReceiver::class.java).setAction(action).putExtra(EXTRA_REQUEST, request.requestId)
        // Text entered in the notification is added to the intent, so the PendingIntent has to be mutable.
        val enter = PendingIntent.getBroadcast(context, 31, intent(ACTION_ENTER), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getBroadcast(context, 32, intent(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val field = RemoteInput.Builder(KEY_PASSWORD)
            .setLabel(request.hint.takeIf { it.isNotBlank() }?.let { "Hint: $it" } ?: "Telegram password")
            .build()
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val title = if (failed) "Couldn't send the password to ${request.tvName}" else "${request.tvName} needs your Telegram password"
        // The lock screen shows only that a TV is waiting: not the hint, and typing needs the phone unlocked.
        val publicVersion = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setContentTitle(title)
            .build()
        val enterAction = Notification.Action.Builder(null, "Enter password", enter).addRemoteInput(field)
        if (Build.VERSION.SDK_INT >= 31) enterAction.setAuthenticationRequired(true)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentTitle(title)
            .setStyle(Notification.BigTextStyle().bigText(
                "Type it here to finish signing in on ${request.tvName}. It is encrypted for that TV only; Cablegram can't read it." +
                    request.hint.takeIf { it.isNotBlank() }?.let { " Hint: $it." }.orEmpty(),
            ))
            .setAutoCancel(true)
            .addAction(enterAction.build())
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

class TelegramPasswordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(TelegramPasswordRequests.EXTRA_REQUEST) ?: return
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(TelegramPasswordRequests.KEY_PASSWORD)?.toString()
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = PairingStore(context)
                val token = store.accountToken ?: return@launch
                val client = CatalogClient(store.apiBaseUrl, store)
                when (action) {
                    TelegramPasswordRequests.ACTION_CANCEL -> {
                        client.cancelTvPassword(token, requestId)
                        TelegramPasswordRequests.cancel(context)
                    }
                    TelegramPasswordRequests.ACTION_ENTER -> {
                        val request = TelegramPasswordRequests.requestFor(client.pendingTvPasswordRequests(token), requestId)
                        if (request == null) {
                            TelegramPasswordRequests.cancel(context) // answered elsewhere, withdrawn or expired
                            return@launch
                        }
                        // Nothing typed: post the notification again, which also ends the reply's progress spinner.
                        if (typed.isNullOrEmpty()) {
                            TelegramPasswordRequests.ask(context, request)
                            return@launch
                        }
                        val sent = runCatching { client.sealTvPassword(token, requestId, PasswordSeal.seal(request.tvPublicKey, requestId, typed)) }
                            .getOrDefault(false)
                        // Never log the password or the sealed bytes: only whether it went.
                        PairLog.i("Telegram password for ${request.tvName}: ${if (sent) "sent" else "not sent"}")
                        if (sent) TelegramPasswordRequests.cancel(context) else TelegramPasswordRequests.ask(context, request, failed = true)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
