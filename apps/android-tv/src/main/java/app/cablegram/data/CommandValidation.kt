package app.cablegram.data

import kotlinx.serialization.json.*

/** Only actions with an implemented TV execution path are accepted. */
internal fun TvCommand.validationError(now: Long = System.currentTimeMillis()): String? {
    if (expiresAtMs == null || expiresAtMs <= now) return "expired"
    fun primitive(key: String) = payload[key] as? JsonPrimitive
    return when (command) {
        "play" -> if (payload.containsKey("videoId") && primitive("videoId")?.contentOrNull.isNullOrBlank()) "invalid_payload" else null
        "pause", "stop", "select", "next", "previous" -> null
        "move" -> if (primitive("direction")?.contentOrNull in setOf("up", "down", "left", "right")) null else "invalid_payload"
        "seek" -> if ((primitive("seconds")?.intOrNull ?: primitive("value")?.intOrNull) in -86400..86400) null else "invalid_payload"
        "volume" -> if (primitive("level")?.intOrNull in 0..100) null else "invalid_payload"
        "mute" -> if (primitive("muted")?.booleanOrNull != null) null else "invalid_payload"
        // Live subtitle shift from the phone, in milliseconds (positive shows subtitles later).
        "subtitle_delay" -> if (primitive("delay_ms")?.longOrNull in -600_000L..600_000L) null else "invalid_payload"
        else -> "unsupported_command"
    }
}
