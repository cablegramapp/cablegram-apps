package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class LanLibraryService : Service() {
    private var server: LanLibraryServer? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var revocationJob: Job? = null
    private var relay: RelayTunnel? = null
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var approvals: ApprovalWatcher? = null
    private var telegramApprovals: TelegramTvApprovalWatcher? = null
    @Volatile private var lastNotifiedAt = 0L
    @Volatile private var relayText: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = PairingStore(this)
        // Prefer device capabilities; fall back to the legacy PIN for old TVs.
        val legacyToken = store.lanToken
        val capabilities = store.tvs.mapNotNull { it.capability }.toSet()
        if (legacyToken.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }
        if (server == null) {
            runCatching {
                val client = CatalogClient(store.apiBaseUrl, store)
                val passes = PrivatePassVerifier(verify = { pass ->
                    store.accountToken?.let { token -> kotlinx.coroutines.runBlocking { client.verifyLanPass(token, pass) } }
                })
                val http = LanLibraryServer(
                    LibraryStore(this), CommandQueue(this), legacyToken, capabilities.toMutableSet(),
                    privatePasses = passes,
                    deviceIdForCapability = { capability -> store.tvs.firstOrNull { it.capability == capability }?.deviceId },
                    capabilityVerifier = LanCapabilityVerifier(verify = { capability ->
                        store.accountToken?.let { token -> kotlinx.coroutines.runBlocking { client.verifyLanCapability(token, capability) } }
                    }),
                    telegram = if (PhoneTelegram.configured) {
                        val removed = RemovedTelegramSources(client, store)
                        PhoneTelegramMedia(this, isRemoved = removed::contains) {
                            store.accountToken?.let { client.telegramLink(it)?.chatId }
                        }
                    } else null,
                )
                http.start()
                server = http
                advertise(legacyToken)
                startRevocationWatch()
                startRelay(store)
                PrivateApprovals.deleteLegacyChannel(this)
                // While something plays on the TV, the ongoing notification becomes its remote.
                serviceScope.launch { CastSession.state.drop(1).collect { postNotification() } }
            }.onFailure { PairLog.e("LAN library failed to start", it) }
        } else {
            // start() is also called after pairing. Keep the live server in sync
            // with the capabilities just persisted by PairingStore.
            server?.replaceCapabilities(capabilities)
        }
        // These talk to the control plane only, so they run even when the LAN server could not bind its port (another
        // app holds it): otherwise a TV waits for a Telegram approval or password that the phone is never asked for.
        // T075 / R-5: private-playback approvals, surfaced only when the TV asks.
        if (approvals == null) approvals = ApprovalWatcher(this, serviceScope).also { it.start() }
        // Spec 004 US2: sign paired TVs in to the household's Telegram.
        if (telegramApprovals == null) telegramApprovals = TelegramTvApprovalWatcher(this, serviceScope).also { it.start() }
        return START_STICKY
    }

    /**
     * R-2: while serving, periodically ask the control plane which household
     * devices are revoked and drop their capabilities live, precisely mapped
     * via each TV's stored device id. Failure/OFFLINE keeps the last known
     * state; server-side JWT enforcement remains the authoritative gate.
     */
    private fun startRevocationWatch() {
        revocationJob?.cancel()
        revocationJob = serviceScope.launch {
            val store = PairingStore(this@LanLibraryService)
            val client = CatalogClient(store.apiBaseUrl, store)
            while (isActive) {
                val token = store.accountToken
                val http = server
                if (!token.isNullOrBlank() && http != null) {
                    runCatching {
                        val revoked = client.fetchRevokedDeviceIds(token)
                        revoked.forEach { deviceId ->
                            store.tvs.firstOrNull { it.deviceId == deviceId }?.capability?.let { cap ->
                                http.revokeCapability(cap)
                                PairLog.i("LAN revoked TV capability device=${deviceId.takeLast(6)}")
                            }
                        }
                    }
                }
                delay(60_000)
            }
        }
    }

    /** Spec 003: keep this phone reachable through the relay when the TV can't reach it directly. */
    private fun startRelay(store: PairingStore) {
        val tunnel = RelayTunnel(
            relayUrl = { RelayTunnel.phoneUrl(store.apiBaseUrl) },
            accountToken = { store.accountToken },
            phoneDeviceId = { store.phoneDeviceId },
            networkType = { RelayConsent.networkType(this) },
            admit = { network -> RelayConsent.admit(this, store, network) },
            onActivity = { active, bytes, network -> updateRelayNotification(active, bytes, network) },
        )
        relay = tunnel
        tunnel.start()
        val manager = getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                RelayConsent.networkChanged(this@LanLibraryService, store)
                tunnel.networkChanged()
            }
            override fun onLost(network: android.net.Network) {
                RelayConsent.networkChanged(this@LanLibraryService, store)
                tunnel.networkChanged()
            }
        }
        networkCallback = callback
        runCatching { manager?.registerDefaultNetworkCallback(callback) }
    }

    private var relayStreamStartBytes = 0L

    /** While relaying, the ongoing notification says so and how much was sent (mobile data called out). */
    private fun updateRelayNotification(active: Int, bytes: Long, network: String) {
        val now = System.currentTimeMillis()
        if (active > 0 && now - lastNotifiedAt < 2_000) return
        lastNotifiedAt = now
        if (active == 0) relayStreamStartBytes = bytes
        val sentMb = (bytes - relayStreamStartBytes) / (1024 * 1024)
        relayText = when {
            active == 0 -> null
            network == "cellular" -> "Streaming via relay · $sentMb MB of mobile data"
            else -> "Streaming via relay · $sentMb MB"
        }
        postNotification()
    }

    private fun postNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification()) }
    }

    override fun onDestroy() {
        approvals?.stop()
        approvals = null
        telegramApprovals?.stop()
        telegramApprovals = null
        relay?.stop()
        relay = null
        networkCallback?.let { cb -> runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        revocationJob?.cancel()
        serviceScope.cancel()
        registration?.let { runCatching { nsdManager?.unregisterService(it) } }
        runCatching { server?.stop() }
        server = null
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
        super.onDestroy()
    }

    private fun advertise(token: String) {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("cablegram-advertise").apply {
            setReferenceCounted(false)
            acquire()
        }
        val manager = getSystemService(NSD_SERVICE) as NsdManager
        nsdManager = manager
        val info = NsdServiceInfo().apply {
            serviceName = LanLibraryServer.NSD_NAME
            serviceType = LanLibraryServer.NSD_TYPE
            port = LanLibraryServer.PORT
            setAttribute("token", token.take(64))
            localIpv4()?.let { setAttribute("ip", it) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * The one ongoing notification: the library's status, or — while this phone
     * has something playing on the TV — a remote for it.
     */
    private fun notification(): Notification {
        val channelId = "lan_library"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(channelId, "Library on TV", NotificationManager.IMPORTANCE_LOW))
        val relay = relayText
        val cast = CastSession.state.value
        val builder = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
        if (cast == null) {
            return builder
                .setContentTitle(if (relay != null) "Cablegram is streaming to your TV" else "Cablegram library is on your TV")
                .setContentText(relay ?: "Same Wi‑Fi streams directly; elsewhere Cablegram can use the relay.")
                .setContentIntent(PhoneActivity.openIntent(this))
                .build()
        }
        fun action(icon: Int, label: String, name: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            label,
            PendingIntent.getBroadcast(
                this, name.hashCode(), Intent(this, CastRemoteReceiver::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        return builder
            .setContentTitle(cast.title)
            .setContentText(listOfNotNull((if (cast.paused) "Paused on " else "Playing on ") + cast.tvName, relay).joinToString(" · "))
            .setContentIntent(PhoneActivity.openIntent(this, PhoneTab.Remote))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_rew, "Back ${CastRemoteReceiver.SEEK_SECONDS} seconds", CastRemoteReceiver.ACTION_REWIND))
            .addAction(
                if (cast.paused) action(android.R.drawable.ic_media_play, "Resume", CastRemoteReceiver.ACTION_TOGGLE)
                else action(android.R.drawable.ic_media_pause, "Pause", CastRemoteReceiver.ACTION_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_ff, "Forward ${CastRemoteReceiver.SEEK_SECONDS} seconds", CastRemoteReceiver.ACTION_FORWARD))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", CastRemoteReceiver.ACTION_STOP))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            val intent = Intent(context, LanLibraryService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanLibraryService::class.java))
        }
    }
}

internal fun localIpv4Addresses(): List<String> {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
    val found = mutableListOf<String>()
    for (nic in interfaces) {
        if (!nic.isUp || nic.isLoopback) continue
        for (address in nic.inetAddresses) {
            if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                address.hostAddress?.let(found::add)
            }
        }
    }
    return found.distinct()
}

internal fun localIpv4(): String? = localIpv4Addresses().firstOrNull()
