package app.cablegram.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * R-3 / FR-020: durable queue of progress updates that could not reach the
 * control plane (offline TV, control plane down). Entries survive process
 * death and are flushed oldest-first whenever the device next has connectivity.
 *
 * Ordering rules (CAB-14):
 * - An entry's `clientUpdatedAt` is its version. It is the time the playback changed (`observedAt`, taken from
 *   [ServerClock] before any network call), not the time the upload failed.
 * - There is one entry per (profile, video). A newer update replaces the older one whatever its position: a
 *   rewind or restart is a newer update and wins.
 * - Within one (profile, video) the version strictly increases, so two updates with the same timestamp keep
 *   the order they happened in (the later one gets one millisecond more).
 * - The server keeps the update with the latest timestamp; an equal timestamp is accepted and the later request
 *   wins, so a replay can never overwrite an update that carries a later timestamp.
 * - After an upload only the exact version that was uploaded is removed ([acknowledge]); a newer update for the
 *   same video stays queued.
 */
@Serializable
data class QueuedProgress(
    val videoId: String,
    val profileId: String?,
    val positionSeconds: Int,
    val state: String,
    val durationSeconds: Int? = null,
    val clientUpdatedAt: Long,
    /** The TV account (pairing session) that made the update; null for entries queued before CAB-37. */
    val accountId: String? = null,
)

class ProgressQueue internal constructor(
    initial: String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = ServerClock.shared::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val entries: MutableList<QueuedProgress> =
        initial?.let { raw -> runCatching { json.decodeFromString<List<QueuedProgress>>(raw) }.getOrNull() }
            ?.toMutableList() ?: mutableListOf()

    constructor(context: Context) : this(preferences(context))

    private constructor(preferences: SharedPreferences) : this(
        preferences.getString(QUEUE_KEY, null),
        { preferences.edit().putString(QUEUE_KEY, it).apply() },
    )

    /** [observedAt] is when the playback state changed; callers take it before the upload that failed. */
    @Synchronized
    fun enqueue(
        videoId: String,
        profileId: String?,
        positionSeconds: Int,
        state: String,
        durationSeconds: Int? = null,
        observedAt: Long = clock(),
        accountId: String? = null,
    ) {
        val previous = entries.firstOrNull { it.videoId == videoId && it.profileId == profileId }
        entries.removeAll { it.videoId == videoId && it.profileId == profileId }
        val version = if (previous != null && observedAt <= previous.clientUpdatedAt) previous.clientUpdatedAt + 1 else observedAt
        entries.add(QueuedProgress(videoId, profileId, positionSeconds, state, durationSeconds, version, accountId))
        if (entries.size > MAX_ENTRIES) entries.subList(0, entries.size - MAX_ENTRIES).clear()
        persist()
    }

    @Synchronized
    fun pending(): List<QueuedProgress> = entries.sortedBy { it.clientUpdatedAt }

    /** Removes [entry] only if it is still the queued version; returns whether it was. */
    @Synchronized
    fun acknowledge(entry: QueuedProgress): Boolean {
        val removed = entries.removeAll { it == entry }
        if (removed) persist()
        return removed
    }

    /**
     * An account was revoked or removed from this TV (CAB-37): its unsent progress goes too. Entries queued before
     * they carried an account are matched by the account's profiles.
     */
    @Synchronized
    fun forgetAccount(accountId: String, profileIds: Set<String>) {
        val removed = entries.removeAll { it.accountId == accountId || (it.accountId == null && it.profileId in profileIds) }
        if (removed) persist()
    }

    @Synchronized
    fun clearAll() {
        entries.clear()
        persist()
    }

    private fun persist() = save(json.encodeToString(entries.toList()))

    private companion object {
        const val QUEUE_KEY = "pending_progress"
        const val MAX_ENTRIES = 200

        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences("cablegram_progress", Context.MODE_PRIVATE)
    }
}

/**
 * Uploads the queued entries oldest-first. An uploaded entry is acknowledged by its exact version, so a newer
 * update enqueued meanwhile stays. A transient failure stops the round and keeps everything queued; an entry the
 * server will never accept (deleted profile or title) is dropped so it cannot block the queue forever.
 */
suspend fun flushProgress(
    queue: ProgressQueue,
    upload: suspend (QueuedProgress) -> Unit,
    isPermanentRejection: (Throwable) -> Boolean,
    /** Only the entries the uploading account may send: another account's progress waits for that account. */
    include: (QueuedProgress) -> Boolean = { true },
) {
    for (entry in queue.pending().filter(include)) {
        val failure = runCatching { upload(entry) }.exceptionOrNull()
        if (failure != null && !isPermanentRejection(failure)) return // still offline; keep the rest queued
        queue.acknowledge(entry)
    }
}
