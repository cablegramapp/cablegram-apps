package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/** Mobile-data consent for relay streams (spec 003 US3). */
object RelayConsent {
    private const val CHANNEL = "relay_consent"
    private const val NOTIFICATION_ID = 7301
    const val ACTION_ALLOW_ONCE = "app.cablegram.phone.RELAY_ALLOW_ONCE"
    const val ACTION_ALLOW_ALWAYS = "app.cablegram.phone.RELAY_ALLOW_ALWAYS"
    const val ACTION_DENY = "app.cablegram.phone.RELAY_DENY"
    private const val ALLOW_ONCE_MS = 3 * 60 * 60 * 1000L

    fun networkType(context: Context): String {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return "other"
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return "other"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    /** null = serve the relay request; otherwise the refusal (and the phone asks when policy is Ask). */
    fun admit(context: Context, store: PairingStore, network: String): RelayRefusal? {
        if (network != "cellular") {
            store.relayMobileAllowedUntil = 0L
            return null
        }
        return when (store.relayMobileData) {
            RelayMobileDataPolicy.Always -> null
            RelayMobileDataPolicy.Never -> RelayRefusal.MobileDataNotAllowed
            RelayMobileDataPolicy.Ask -> if (System.currentTimeMillis() < store.relayMobileAllowedUntil) null else {
                ask(context)
                RelayRefusal.MobileDataPending
            }
        }
    }

    /**
     * "Allow this time" lasts until the phone is back on Wi‑Fi. Called on every network change:
     * resetting only when a relay request arrived on Wi‑Fi missed the usual case (back home, the TV
     * uses LAN, no relay request) and silently kept the permission for the next time.
     */
    fun networkChanged(context: Context, store: PairingStore = PairingStore(context)) {
        if (networkType(context) != "cellular" && store.relayMobileAllowedUntil != 0L) {
            store.relayMobileAllowedUntil = 0L
            PairLog.i("Relay mobile-data permission reset: phone left mobile data")
        }
    }

    fun apply(context: Context, action: String?) {
        val store = PairingStore(context)
        when (action) {
            ACTION_ALLOW_ONCE -> store.relayMobileAllowedUntil = System.currentTimeMillis() + ALLOW_ONCE_MS
            ACTION_ALLOW_ALWAYS -> store.relayMobileData = RelayMobileDataPolicy.Always
            ACTION_DENY -> store.relayMobileAllowedUntil = 0L
        }
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun ask(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Mobile data for TV streaming", NotificationManager.IMPORTANCE_HIGH))
        }
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, code, Intent(context, RelayConsentReceiver::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setContentTitle("Stream to your TV over mobile data?")
            .setStyle(Notification.BigTextStyle().bigText(
                "Your TV can't reach this phone on Wi‑Fi. Streaming through the Cablegram relay uploads the video over your mobile data plan (about 2–3 GB per hour of HD video).",
            ))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Allow this time", action(ACTION_ALLOW_ONCE, 1)).build())
            .addAction(Notification.Action.Builder(null, "Always allow", action(ACTION_ALLOW_ALWAYS, 2)).build())
            .addAction(Notification.Action.Builder(null, "Don't allow", action(ACTION_DENY, 3)).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }
}

class RelayConsentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RelayConsent.apply(context, intent.action)
}
