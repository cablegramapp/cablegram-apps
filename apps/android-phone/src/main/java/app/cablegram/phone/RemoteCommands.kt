package app.cablegram.phone

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The payload envelope consumed by the control plane and the TV player. */
internal fun remoteCommandBody(
    command: String,
    targetDeviceId: String,
    videoId: String? = null,
    arguments: JsonObject = buildJsonObject {},
): JsonObject {
    require(targetDeviceId.isNotBlank()) { "A remote command must target a paired TV" }
    return buildJsonObject {
        put("command", command)
        put("target_device_id", targetDeviceId)
        put("payload", buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            videoId?.let { put("videoId", it) }
        })
    }
}
