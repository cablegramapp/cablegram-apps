package app.cablegram.phone

import java.util.concurrent.ConcurrentHashMap

/** A LAN pass the control plane confirmed for an approved private playback. */
data class VerifiedPass(
    val originIdentities: Set<String>,
    val tvDeviceId: String?,
    val expiresAtMs: Long,
)

/**
 * Private titles are streamed only with a LAN pass minted when a TV starts an
 * approved playback. Passes are confirmed with the control plane once and then
 * cached until they expire, so seeks and range requests stay local.
 */
class PrivatePassVerifier(
    private val verify: (pass: String) -> VerifiedPass?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<String, VerifiedPass>()

    fun allows(pass: String?, itemId: String, tvDeviceId: String?): Boolean {
        if (pass.isNullOrBlank()) return false
        cache.entries.removeIf { it.value.expiresAtMs <= nowMs() }
        val verified = cache[pass] ?: verify(pass)?.takeIf { it.expiresAtMs > nowMs() }?.also { cache[pass] = it }
            ?: return false
        if (itemId !in verified.originIdentities) return false
        // A pass is bound to the TV that started the playback.
        return tvDeviceId == null || verified.tvDeviceId == null || tvDeviceId == verified.tvDeviceId
    }
}
