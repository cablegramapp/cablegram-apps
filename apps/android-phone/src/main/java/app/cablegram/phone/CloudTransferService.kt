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

/**
 * Saves a title to the household's own storage (Google Drive or Cloudflare R2, specs 005 and 006) while the app is
 * backgrounded, with a progress notification. There is no Cablegram-hosted cloud (CAB-38).
 */
class CloudTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<String>()
    /** Titles queued while the household's own storage was connected; any other title is refused. */
    private val ownR2 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Where each queued title goes, for the notification: "Google Drive" or "Cloudflare R2". */
    private val destinations = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Where the title being saved now goes. A queue can mix destinations, so it is set per title in [saveOne]. */
    @Volatile private var destination = "Cloud"
    private val running = AtomicBoolean(false)
    private val store by lazy { LibraryStore(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ITEM_ID)
        if (id.isNullOrBlank()) {
            if (queue.isEmpty() && !running.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (intent.getBooleanExtra(EXTRA_OWN_R2, false)) ownR2.add(id)
        val target = intent.getStringExtra(EXTRA_DESTINATION)?.takeIf { it.isNotBlank() } ?: "Cloud"
        destinations[id] = target
        if (!running.get()) destination = target
        queue.add(id)
        val item = store.get(id)
        startAsForeground(item?.title ?: "Video", 0, item?.fileSizeBytes ?: 0)
        if (running.compareAndSet(false, true)) {
            scope.launch { drainQueue() }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 15+ stops a dataSync service after about 6 hours a day. The service must stop within seconds or the
     * app crashes, so the title being saved is cancelled (it resumes where it stopped when saved again) and the
     * titles still waiting are marked failed rather than left showing as queued.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        PairLog.i("Cloud transfer stopped by the system time limit")
        while (true) {
            val id = queue.poll() ?: break
            destinations.remove(id)
            ownR2.remove(id)
            store.get(id)?.let { store.update(it.copy(transferStatus = TRANSFER_FAILED)) }
        }
        notifyDone("Saving paused", ok = false, detail = "Android paused saving after a long run. Open Cablegram and save again to continue.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun drainQueue() {
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

    private suspend fun saveOne(id: String) {
        destination = destinations.remove(id) ?: "Cloud"
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
        val progress = { copied: Long, total: Long ->
            val now = System.currentTimeMillis()
            val elapsed = ((System.nanoTime() - started) / 1_000_000_000L).coerceAtLeast(1)
            // Keep the saved state in step, but not on every 256 KiB: the library file is rewritten each time.
            if (now - lastNotify >= 400) {
                lastNotify = now
                store.get(id)?.let { current ->
                    store.update(current.copy(transferStatus = TRANSFER_SAVING, uploadBytes = copied, uploadTotal = total, transferBytesPerSec = copied / elapsed))
                }
                notifyProgress(item.title, copied, total, copied / elapsed)
            }
        }
        try {
            if (ownR2.remove(id)) {
                saveToOwnR2(item, progress)
                notifyDone(item.title, ok = true, detail = "Saved to $destination")
                return
            }
            // Saves go only to the household's own storage (CAB-38); the save sheet never queues one without it.
            store.get(id)?.let { store.update(it.copy(transferStatus = TRANSFER_FAILED)) }
            notifyDone(item.title, ok = false, detail = "Connect Google Drive or Cloudflare R2 to save to the cloud.")
        } catch (error: Throwable) {
            PairLog.e("Save to cloud failed", error)
            store.get(id)?.let { store.update(it.copy(transferStatus = TRANSFER_FAILED)) }
            // Stopped (service destroyed): shown as "Save failed"; saving again resumes an R2 upload where it stopped.
            if (error is kotlinx.coroutines.CancellationException) throw error
            notifyDone(item.title, ok = false, detail = (error as? R2ApiException)?.friendly(destination) ?: error.message)
        }
    }

    /**
     * Streams the video to the household's own storage (R2 parts or Drive chunks, as the server says). The control plane verifies the stored size before it
     * records the copy, so [LibraryItem.ownCloudCopy] is only set for a copy that Free up space may rely on.
     */
    private suspend fun saveToOwnR2(item: LibraryItem, onProgress: (Long, Long) -> Unit) {
        val pairing = PairingStore(this)
        val token = pairing.accountToken ?: error("Sign in again to save to Cloudflare.")
        val pfd = store.openPfd(item) ?: error("Could not read that video")
        pfd.use {
            java.io.FileInputStream(it.fileDescriptor).use { stream ->
                val extension = item.filename.substringAfterLast('.', "").lowercase()
                val contentType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    ?.takeIf { type -> type.startsWith("video/") } ?: "video/mp4"
                val done = OwnCloudUploader(CatalogClient(pairing.apiBaseUrl, pairing).ownCloudApi(token), log = { PairLog.i(it) })
                    .upload(item.id, item.filename, contentType, stream.channel, onProgress)
                store.updateItem(item.id) { current ->
                    current.copy(
                        ownCloudCopy = true,
                        // The catalog's id for the copy, so it can be removed at once without waiting for the next sync.
                        ownCloudSourceId = done.sourceId,
                        transferStatus = TRANSFER_IDLE,
                        webTransferError = null,
                        uploadBytes = done.bytes,
                        uploadTotal = done.bytes,
                        fileSizeBytes = current.fileSizeBytes ?: done.bytes,
                    )
                }
            }
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
            ok -> detail ?: "Saved to Cloud"
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
            .setContentTitle("Saving to $destination")
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
        const val EXTRA_OWN_R2 = "own_r2"
        const val EXTRA_DESTINATION = "destination"
        private const val CHANNEL_ID = "cloud_saves"
        private const val NOTIFY_ID = 42
        private const val NOTIFY_DONE_ID = 43

        fun start(context: Context, itemId: String, ownR2: Boolean = false, destination: String = "Cloud") {
            val intent = Intent(context, CloudTransferService::class.java)
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_OWN_R2, ownR2)
                .putExtra(EXTRA_DESTINATION, destination)
            context.startForegroundService(intent)
        }
    }
}
