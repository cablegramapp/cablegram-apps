package app.cablegram.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * R-3 / FR-020: durable queue of progress updates that could not reach the
 * control plane (offline TV, control plane down). Entries survive process
 * death and are flushed oldest-first whenever the device next has
 * connectivity. Last-write resolve happens server-side via
 * `client_updated_at`; duplicates for the same (profile, item) are coalesced
 * to the latest position before flush to cut traffic.
 */
@Serializable
data class QueuedProgress(
    val videoId: String,
    val profileId: String?,
    val positionSeconds: Int,
    val state: String,
    val durationSeconds: Int? = null,
    val clientUpdatedAt: Long,
)

class ProgressQueue(context: Context) {
    private val preferences = context.getSharedPreferences("cablegram_progress", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun enqueue(videoId: String, profileId: String?, positionSeconds: Int, state: String, durationSeconds: Int? = null) {
        val current = load().toMutableList()
        // Coalesce: keep only the newest entry per (profile, video).
        current.removeAll { it.videoId == videoId && it.profileId == profileId }
        current.add(
            QueuedProgress(
                videoId = videoId,
                profileId = profileId,
                positionSeconds = positionSeconds,
                state = state,
                durationSeconds = durationSeconds,
                clientUpdatedAt = System.currentTimeMillis(),
            ),
        )
        val encoded = json.encodeToString(current.takeLast(MAX_ENTRIES))
        preferences.edit().putString(QUEUE_KEY, encoded).apply()
    }

    fun pending(): List<QueuedProgress> = load().sortedBy { it.clientUpdatedAt }

    fun clearAll() {
        preferences.edit().remove(QUEUE_KEY).apply()
    }

    fun remove(videoId: String, profileId: String?) {
        val remaining = load().filterNot { it.videoId == videoId && it.profileId == profileId }
        preferences.edit().putString(QUEUE_KEY, json.encodeToString(remaining)).apply()
    }

    private fun load(): List<QueuedProgress> =
        preferences.getString(QUEUE_KEY, null)?.let { raw ->
            runCatching { json.decodeFromString<List<QueuedProgress>>(raw) }.getOrDefault(emptyList())
        }.orEmpty()

    private companion object {
        const val QUEUE_KEY = "pending_progress"
        const val MAX_ENTRIES = 200
    }
}
