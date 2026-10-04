package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.cablegram.telegram.TelegramLibrarySync
import app.cablegram.telegram.TelegramState
import app.cablegram.telegram.TgException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Signs paired TVs in to the household's Telegram with this phone's session (spec 004 US2, FR-003):
 * a TV posts its login link, the phone approves it like Telegram's "Scan QR" would.
 *
 * Runs inside [LanLibraryService] only on the phone that linked Telegram. A TV's link lives about
 * 30 s before TDLib replaces it, so this polls every 5 s (a push over the relay socket would be
 * cheaper; see spec 004 tasks).
 */
class TelegramTvApprovalWatcher(private val context: Context, private val scope: CoroutineScope) {
    private val store = PairingStore(context)
    private val client = CatalogClient(store.apiBaseUrl, store)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true || !PhoneTelegram.configured) return
        startLibrarySync()
        startTrustWork()
        job = scope.launch {
            val asked = mutableSetOf<String>()
            val askedPassword = mutableSetOf<String>()
            while (isActive) {
                val token = store.accountToken
                if (!token.isNullOrBlank() && PhoneTelegram.wasLinked(context)) {
                    val pending = client.pendingTvLogins(token).orEmpty()
                    for (request in pending) {
                        if (PhoneTelegram.autoApproveTvs(context)) {
                            approve(context, client, token, request)
                        } else if (asked.add(request.tvDeviceId + request.loginLink)) {
                            TelegramTvApprovals.ask(context, request)
                        }
                    }
                    if (pending.isEmpty()) TelegramTvApprovals.cancel(context)
                    // A TV that Telegram asks for the two-step password: the phone's keyboard types it (sealed to the TV).
                    val passwords = client.pendingTvPasswordRequests(token)
                    passwords?.forEach { if (askedPassword.add(it.requestId)) TelegramPasswordRequests.ask(context, it) }
                    if (passwords != null && passwords.isEmpty()) TelegramPasswordRequests.cancel(context)
                }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        libraryJob?.cancel()
        libraryJob = null
        trustJob?.cancel()
        trustJob = null
    }

    private var libraryJob: Job? = null

    /**
     * Spec 004 US3: keeps the household catalog in step with the library channel from the phone too,
     * so a video shared into the channel appears without a TV being on. Titles the phone registers
     * carry the caption or file name; a title matched on the phone is never overwritten by this.
     */
    /** Every minute: end due TV sessions, and remind about temporary TVs that are still paired near checkout. */
    private fun startTrustWork() {
        if (trustJob?.isActive == true) return
        trustJob = scope.launch {
            while (isActive) {
                val token = store.accountToken
                if (!token.isNullOrBlank()) {
                    if (PhoneTelegram.wasLinked(context)) runCatching { endDueSessions(context, client, token) }
                    runCatching { TvCheckoutReminders.check(context, client, token) }
                }
                delay(TRUST_POLL_MS)
            }
        }
    }

    private var trustJob: Job? = null

    private fun startLibrarySync() {
        if (libraryJob?.isActive == true) return
        libraryJob = scope.launch {
            while (isActive) {
                val token = store.accountToken
                val session = if (PhoneTelegram.wasLinked(context)) PhoneTelegram.session(context) else null
                val chatId = if (token.isNullOrBlank()) null else client.telegramLink(token)?.chatId
                if (session == null || chatId == null) { delay(RETRY_MS); continue }
                val ready = withTimeoutOrNull(30_000) { session.state.first { it is TelegramState.Ready } }
                if (ready == null) { delay(RETRY_MS); continue }
                val sync = TelegramLibrarySync(
                    api = session.api,
                    chatId = chatId,
                    register = { video -> store.accountToken?.let { client.registerTelegramVideo(it, video) } },
                    reconcile = { keys -> store.accountToken?.let { client.reconcileTelegramVideos(it, keys) } },
                )
                runCatching { sync.fullScan() }
                    .onSuccess { PairLog.i("Telegram library scan on phone: $it video(s)") }
                    .onFailure { PairLog.e("Telegram library scan on phone failed", it) }
                sync.watch(this).join()
            }
        }
    }

    companion object {
        private const val POLL_MS = 5_000L
        private const val RETRY_MS = 30_000L
        private const val TRUST_POLL_MS = 60_000L

        /** Approves one TV login with the phone's session and reports the outcome (no secrets). */
        suspend fun approve(context: Context, client: CatalogClient, token: String, request: PendingTvLogin) {
            val session = PhoneTelegram.session(context)
            val ready = withTimeoutOrNull(20_000) { session.state.first { it is TelegramState.Ready } }
            if (ready == null) {
                PairLog.i("Telegram TV login ${PairLog.pinTail(request.requestId)}: phone session not ready")
                return // stays pending; the TV keeps posting fresh links until the phone is ready
            }
            // Verified approval: only a Cablegram session is accepted, and it is identified exactly, so the
            // phone can end that TV's session later even when the TV is off (US8), and never another one.
            val outcome = session.approveTvLogin(request.loginLink, BuildConfig.TELEGRAM_API_ID)
            val error = (outcome.exceptionOrNull() as? TgException)?.name?.let(::telegramErrorName)
            client.postTvLoginResult(token, request.requestId, if (outcome.isSuccess) "approved" else "failed", error)
            val identified = outcome.getOrNull()?.session
            if (identified != null) client.putTvTelegramSession(token, request.tvDeviceId, identified.id)
            else if (outcome.isSuccess) PairLog.w("Telegram TV session not listed for ${PairLog.pinTail(request.tvDeviceId)}; it cannot be ended remotely")
            PairLog.i("Telegram TV login for ${request.tvName}: ${if (outcome.isSuccess) "approved" else "failed $error"}")
        }

        /** Ends every TV session the control plane lists as due (TV removed, signed out, expired, Telegram disconnected). */
        suspend fun endDueSessions(context: Context, client: CatalogClient, token: String) {
            val due = client.dueTvTelegramSessions(token).orEmpty()
            if (due.isEmpty()) return
            val session = PhoneTelegram.session(context)
            if (withTimeoutOrNull(20_000) { session.state.first { it is TelegramState.Ready } } == null) return
            for (item in due) {
                val id = item.telegramSessionId.toLongOrNull() ?: continue
                try {
                    val live = session.api.activeSessions()
                    if (live.none { it.id == id }) {
                        client.reportTvSessionEnded(token, item.tvDeviceId, "already_gone")
                    } else {
                        session.api.terminateSession(id)
                        client.reportTvSessionEnded(token, item.tvDeviceId, "terminated")
                        PairLog.i("Ended Telegram session of TV ${PairLog.pinTail(item.tvDeviceId)} (${item.reason})")
                    }
                } catch (e: TgException) {
                    // Stays due and is retried on the next round.
                    client.reportTvSessionEnded(token, item.tvDeviceId, "failed", telegramErrorName(e.name))
                }
            }
        }

        /** The server accepts Telegram error names only (`^[A-Z0-9_ ]{1,80}$`). */
        internal fun telegramErrorName(raw: String): String =
            raw.uppercase().replace(Regex("[^A-Z0-9_ ]"), " ").trim().take(80).ifBlank { "UNKNOWN" }
    }
}

/** "Allow <TV> to use your Telegram?" when automatic approval is off. */
object TelegramTvApprovals {
    private const val CHANNEL = "telegram_tv_login"
    private const val NOTIFICATION_ID = 7304
    internal const val ACTION_ALLOW = "app.cablegram.phone.telegram.ALLOW_TV"
    internal const val ACTION_DENY = "app.cablegram.phone.telegram.DENY_TV"
    internal const val EXTRA_REQUEST = "request_id"

    /** Allow approves the request the user was shown, never another one the server lists. */
    internal fun requestToApprove(pending: List<PendingTvLogin>?, requestId: String): PendingTvLogin? =
        pending?.firstOrNull { it.requestId == requestId }

    fun ask(context: Context, request: PendingTvLogin) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Telegram sign-in for TVs", NotificationManager.IMPORTANCE_HIGH))
        }
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, code,
            // Only the request id travels in the intent; the login link is fetched again on Allow.
            Intent(context, TelegramTvApprovalReceiver::class.java).setAction(name).putExtra(EXTRA_REQUEST, request.requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setContentTitle("Let ${request.tvName} use your Telegram?")
            .setStyle(Notification.BigTextStyle().bigText(
                "${request.tvName} wants to play videos from your \"Cablegram library\" channel. It signs in as its own device on your Telegram account.",
            ))
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Allow", action(ACTION_ALLOW, 11)).build())
            .addAction(Notification.Action.Builder(null, "Don't allow", action(ACTION_DENY, 12)).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

class TelegramTvApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(TelegramTvApprovals.EXTRA_REQUEST) ?: return
        TelegramTvApprovals.cancel(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = PairingStore(context)
                val token = store.accountToken ?: return@launch
                val client = CatalogClient(store.apiBaseUrl, store)
                when (intent.action) {
                    TelegramTvApprovals.ACTION_DENY -> client.postTvLoginResult(token, requestId, "denied")
                    TelegramTvApprovals.ACTION_ALLOW -> {
                        // The TV may have posted a fresher link since the notification; approve the current one
                        // of the same request. Never another request: the user allowed this one only.
                        val request = TelegramTvApprovals.requestToApprove(client.pendingTvLogins(token), requestId)
                        request?.let { TelegramTvApprovalWatcher.approve(context, client, token, it) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** One hour before a temporary TV's end time: "Sign out now" or "Extend" (spec 004 US8). */
object TvCheckoutReminders {
    private const val CHANNEL = "tv_checkout"
    internal const val ACTION_SIGN_OUT = "app.cablegram.phone.tv.SIGN_OUT_NOW"
    internal const val ACTION_EXTEND = "app.cablegram.phone.tv.EXTEND"
    internal const val EXTRA_TV = "tv_device_id"

    suspend fun check(context: Context, client: CatalogClient, token: String) {
        val tvs = client.householdTvs(token).orEmpty()
        val prefs = context.getSharedPreferences("cablegram_tv_reminders", Context.MODE_PRIVATE)
        val now = java.time.Instant.now()
        for (tv in tvs) {
            val trust = tv.toTrust()
            val key = "${tv.id}@${trust.expiresAt}"
            if (!TvTrustRules.reminderDue(trust, now) || prefs.getBoolean(key, false)) continue
            remind(context, tv, trust)
            prefs.edit().putBoolean(key, true).apply()
        }
    }

    private fun remind(context: Context, tv: MeDevice, trust: TvTrust) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Temporary TV reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        fun action(name: String, code: Int) = PendingIntent.getBroadcast(
            context, code + tv.id.hashCode(),
            Intent(context, TvCheckoutReceiver::class.java).setAction(name).putExtra(EXTRA_TV, tv.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = tv.displayName ?: "Your temporary TV"
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setContentTitle("$name is still paired")
            .setContentText("It will be unpaired ${trust.expiresAt?.let(::formatCheckout) ?: "soon"}.")
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Sign out now", action(ACTION_SIGN_OUT, 21)).build())
            .addAction(Notification.Action.Builder(null, "Extend a day", action(ACTION_EXTEND, 22)).build())
            .build()
        runCatching { manager.notify(7400 + (tv.id.hashCode() and 0xff), notification) }
    }
}

class TvCheckoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tvId = intent.getStringExtra(TvCheckoutReminders.EXTRA_TV) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = PairingStore(context)
                val token = store.accountToken ?: return@launch
                val client = CatalogClient(store.apiBaseUrl, store)
                when (intent.action) {
                    TvCheckoutReminders.ACTION_SIGN_OUT -> client.revokeDevice(tvId, token)
                    TvCheckoutReminders.ACTION_EXTEND -> {
                        val tv = client.householdTvs(token)?.firstOrNull { it.id == tvId } ?: return@launch
                        val trust = tv.toTrust()
                        val end = trust.expiresAt ?: return@launch
                        client.setTvTrust(token, tvId, trust.copy(expiresAt = end.plus(TvTrustRules.EXTEND_BY)))
                    }
                }
                context.getSystemService(NotificationManager::class.java)?.cancel(7400 + (tvId.hashCode() and 0xff))
            } finally {
                pending.finish()
            }
        }
    }
}
