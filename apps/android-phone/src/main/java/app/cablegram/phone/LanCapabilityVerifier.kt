package app.cablegram.phone

import java.util.concurrent.ConcurrentHashMap

/**
 * Only the phone that claimed a TV's PIN receives that TV's LAN capability.
 * Another household phone serving media would reject the TV forever, so
 * unknown capabilities are checked with the control plane. Accepted ones are
 * cached for [acceptMs]; rejected ones for a short [rejectMs] so a bad token
 * cannot make this phone hammer the server.
 */
class LanCapabilityVerifier(
    private val verify: (capability: String) -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val acceptMs: Long = 10 * 60_000L,
    private val rejectMs: Long = 30_000L,
) {
    private data class Entry(val tvDeviceId: String?, val untilMs: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    /** The TV device id for [capability], or null when it is not a paired TV. */
    fun tvDeviceId(capability: String): String? {
        cache[capability]?.takeIf { it.untilMs > nowMs() }?.let { return it.tvDeviceId }
        val tv = verify(capability)
        cache[capability] = Entry(tv, nowMs() + if (tv != null) acceptMs else rejectMs)
        return tv
    }

    /** Called when the control plane reports a TV revoked. */
    fun forget(capability: String) {
        cache.remove(capability)
    }

    /** Drops every cached answer for this TV, for a capability this phone never held itself (CAB-37). */
    fun forgetDevice(deviceId: String) {
        cache.entries.removeIf { it.value.tvDeviceId == deviceId }
    }
}
