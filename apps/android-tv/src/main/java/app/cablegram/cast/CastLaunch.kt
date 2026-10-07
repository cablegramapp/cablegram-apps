package app.cablegram.cast

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** An untrusted wake signal. Only a separately authenticated server command may play a title. */
data class CastLaunch(val commandId: String, val targetDeviceId: String) {
    companion object {
        fun parse(raw: String?): CastLaunch? = runCatching {
            val data = Json.parseToJsonElement(raw ?: return null) as? JsonObject ?: return null
            if (data.keys != setOf("commandId", "targetDeviceId")) return null
            fun id(key: String): String? = (data[key] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() && it.length <= 128 }
            CastLaunch(id("commandId") ?: return null, id("targetDeviceId") ?: return null)
        }.getOrNull()
    }
}
