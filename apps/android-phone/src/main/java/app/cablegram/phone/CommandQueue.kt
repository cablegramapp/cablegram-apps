package app.cablegram.phone

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CommandQueue(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "library/commands.json")

    @Synchronized
    fun add(command: String, videoId: String? = null) {
        val next = drainUnlocked() + RemoteCommand(UUID.randomUUID().toString(), command, videoId)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(next))
    }

    @Synchronized
    fun drain(): List<RemoteCommand> {
        val items = drainUnlocked()
        file.delete()
        return items
    }

    private fun drainUnlocked(): List<RemoteCommand> {
        if (!file.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<RemoteCommand>>(file.readText()) }.getOrDefault(emptyList())
    }
}
