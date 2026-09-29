package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Copies a title into Cablegram Cloud while the app is backgrounded, with a progress notification. */
class CloudTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<String>()
    private val running = AtomicBoolean(false)
    private val store by lazy { LibraryStore(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ITEM_ID)
        if (id.isNullOrBlank()) {
            if (queue.isEmpty() && !running.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        queue.add(id)
        val item = store.get(id)
        startAsForeground(item?.title ?: "Video", 0, item?.fileSizeBytes ?: 0)
        if (running.compareAndSet(false, true)) {
            scope.launch { drainQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun drainQueue() {
        try {
            while (true) {
                val id = queue.poll() ?: break
                saveOne(id)
            }
        } finally {
            running.set(false)
            if (queue.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else if (running.compareAndSet(false, true)) {
                scope.launch { drainQueue() }
            }
        }
    }

    private fun saveOne(id: String) {
        val item = store.get(id) ?: return
        store.update(
            item.copy(
                transferStatus = TRANSFER_SAVING,
                uploadBytes = 0,
                uploadTotal = item.fileSizeBytes,
            ),
        )
        val started = System.nanoTime()
        var lastNotify = 0L
        try {
            store.saveToCloud(item) { copied, total ->
                val now = System.currentTimeMillis()
                val elapsed = ((System.nanoTime() - started) / 1_000_000_000L).coerceAtLeast(1)
                store.get(id)?.let { current ->
                    store.update(
                        current.copy(
                            transferStatus = TRANSFER_SAVING,
                            uploadBytes = copied,
                            uploadTotal = total,
                            transferBytesPerSec = copied / elapsed,
                        ),
                    )
                }
                if (now - lastNotify >= 400) {
                    lastNotify = now
                    notifyProgress(item.title, copied, total, copied / elapsed)
                }
            }
            notifyDone(item.title, ok = true)
        } catch (error: Throwable) {
            PairLog.e("Save to cloud failed", error)
            store.get(id)?.let { store.update(it.copy(transferStatus = TRANSFER_FAILED)) }
            notifyDone(item.title, ok = false, detail = error.message)
        }
    }

    private fun startAsForeground(title: String, copied: Long, total: Long) {
        val notification = progressNotification(title, copied, total, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFY_ID, notification)
        }
    }

    private fun notifyProgress(title: String, copied: Long, total: Long, bytesPerSec: Long) {
        manager().notify(NOTIFY_ID, progressNotification(title, copied, total, bytesPerSec))
    }

    private fun notifyDone(title: String, ok: Boolean, detail: String? = null) {
        val text = when {
            ok -> "Saved to Cloud"
            !detail.isNullOrBlank() -> detail
            else -> "Could not save to Cloud"
        }
        val notification = builder()
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(0, 0, false)
            .build()
        manager().notify(NOTIFY_DONE_ID, notification)
    }

    private fun progressNotification(title: String, copied: Long, total: Long, bytesPerSec: Long): Notification {
        val max = 1000
        val progress = if (total > 0) ((copied * max) / total).toInt().coerceIn(0, max) else 0
        val remaining = (total - copied).coerceAtLeast(0)
        val bits = listOf(
            "${formatBytes(copied)} / ${formatBytes(if (total > 0) total else copied)}",
            formatRate(bytesPerSec),
            etaLabel(remaining, bytesPerSec),
        ).filter { it.isNotBlank() }
        return builder()
            .setContentTitle("Saving to Cloud")
            .setContentText(title)
            .setStyle(Notification.BigTextStyle().bigText("$title\n${bits.joinToString(" · ")}"))
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .setProgress(max, progress, total <= 0)
            .build()
    }

    private fun builder(): Notification.Builder {
        ensureChannel()
        return Notification.Builder(this, CHANNEL_ID)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Cloud saves",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while Cablegram copies a title to the cloud"
            setShowBadge(false)
        }
        manager().createNotificationChannel(channel)
    }

    private fun manager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun openApp(): PendingIntent {
        val intent = Intent(this, PhoneActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_ITEM_ID = "item_id"
        private const val CHANNEL_ID = "cloud_saves"
        private const val NOTIFY_ID = 42
        private const val NOTIFY_DONE_ID = 43

        fun start(context: Context, itemId: String) {
            val intent = Intent(context, CloudTransferService::class.java)
                .putExtra(EXTRA_ITEM_ID, itemId)
            context.startForegroundService(intent)
        }
    }
}
