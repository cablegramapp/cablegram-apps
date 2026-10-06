package app.cablegram.cast

import app.cablegram.data.TvCommand
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Keeps only a verified server command, and only after its Cast IDs match this TV. */
internal class CastTitleHold {
    var launch: CastLaunch? = null
        private set
    var command: TvCommand? = null
        private set

    fun signal(value: CastLaunch, ownDeviceIds: Set<String>): Boolean {
        if (value.targetDeviceId !in ownDeviceIds) return false
        launch = value
        return true
    }

    fun matches(value: TvCommand, accountDeviceId: String?): Boolean =
        value.command == "play" && value.payload.containsKey("videoId") &&
            launch?.commandId == value.id && launch?.targetDeviceId == accountDeviceId

    fun hold(value: TvCommand, accountDeviceId: String?): Boolean {
        if (!matches(value, accountDeviceId)) return false
        command = value
        return true
    }

    fun expired(now: Long): Boolean = command?.let { it.expiresAtMs == null || it.expiresAtMs <= now } == true

    /** Caller resolves the title only in the profile the viewer actually selected. */
    fun select(hasTitle: Boolean, now: Long): String? = when {
        command == null -> "profile_required"
        expired(now) -> "expired"
        !hasTitle -> "title_unavailable"
        else -> null
    }

    fun clear(): TvCommand? = command.also { command = null; launch = null }
}

/** Command-first race window is bounded independently of the 300-second picker hold. */
internal fun castSignalWaitMs(command: TvCommand, now: Long): Long? {
    if (command.command != "play" || !command.payload.containsKey("videoId") ||
        (command.payload["castLaunch"] as? JsonPrimitive)?.booleanOrNull != true) return null
    val remaining = ((command.expiresAtMs ?: return null) - now).coerceAtLeast(0)
    return minOf(30_000L, remaining)
}
