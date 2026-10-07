package app.cablegram.phone

import java.util.concurrent.ConcurrentHashMap

/**
 * The credentials the running LAN server accepts, kept in step with [PairingStore] while it serves (CAB-37).
 *
 * Removing one of several TVs used to leave its capability (and, when it was the most recently paired TV, the
 * legacy token) accepted until the app process restarted. [sync] now drops whatever is no longer paired, and
 * [revokeDevice] works from the device ids this server has served, so a TV already gone from the store can still
 * be cut off when the control plane reports it revoked.
 *
 * Read on the server's request threads, written from the service: every field is safe to share.
 */
class LanCredentials(
    /** Confirms capabilities of TVs paired by another household phone. */
    private val verifier: LanCapabilityVerifier? = null,
) {
    @Volatile private var token: String = ""
    private val capabilities: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Every TV served since the service started, so a removed one's device id still maps to its capability. */
    private val deviceIds = ConcurrentHashMap<String, String>()
    private val revokedDeviceIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var served: List<PairedTv> = emptyList()

    /**
     * Accept exactly the TVs paired now. A TV no longer listed loses its capability and the verifier's cached
     * answer for it. [legacyToken] is the store's current one (`PairingStore.lanToken`), so a removed TV's
     * capability or PIN never stays accepted as the legacy token.
     */
    @Synchronized
    fun sync(tvs: List<PairedTv>, legacyToken: String?) {
        // A TV paired since the last sync is a new pairing: an earlier revocation of the same device id is over.
        val servedPins = served.map { it.pin }.toSet()
        tvs.filter { it.pin !in servedPins }.forEach { tv -> tv.deviceId?.let(revokedDeviceIds::remove) }
        val pins = tvs.map { it.pin }.toSet()
        served.filter { it.pin !in pins }.forEach(::forget)
        served = tvs
        val current = tvs.filter { it.deviceId == null || it.deviceId !in revokedDeviceIds }
        current.forEach { tv -> if (tv.capability != null && tv.deviceId != null) deviceIds[tv.capability] = tv.deviceId }
        val accepted = current.mapNotNull { it.capability }.toSet()
        capabilities.retainAll(accepted)
        capabilities.addAll(accepted)
        token = legacyToken?.takeIf { legacy -> current.any { it.capability == legacy || it.pin == legacy } }.orEmpty()
    }

    /**
     * The control plane reports this TV revoked: it is refused from now on, whether or not it is still in the store.
     * Only pairing it again (a new entry in [sync]) lets it back in.
     */
    @Synchronized
    fun revokeDevice(deviceId: String) {
        revokedDeviceIds.add(deviceId)
        deviceIds.filterValues { it == deviceId }.keys.forEach { capability ->
            capabilities.remove(capability)
            verifier?.forget(capability)
            if (token == capability) token = ""
        }
        served.filter { it.deviceId == deviceId }.forEach { tv ->
            tv.capability?.let(capabilities::remove)
            if (token == tv.pin || token == tv.capability) token = ""
        }
        verifier?.forgetDevice(deviceId)
    }

    /** Device capabilities first; the legacy PIN for old TVs; then the control plane for other phones' TVs. */
    fun authorized(supplied: List<String>): Boolean {
        if (supplied.isEmpty()) return false
        val legacy = token
        if (supplied.any { (legacy.isNotEmpty() && it == legacy) || capabilities.contains(it) }) return true
        return supplied.any { verifiedDeviceId(it) != null }
    }

    /** The TV device id behind [capability], or null when this phone does not accept it. */
    fun tvDeviceId(capability: String): String? =
        (deviceIds[capability]?.takeIf { capabilities.contains(capability) }) ?: verifiedDeviceId(capability)

    private fun verifiedDeviceId(capability: String): String? =
        verifier?.tvDeviceId(capability)?.takeIf { it !in revokedDeviceIds }

    private fun forget(tv: PairedTv) {
        tv.capability?.let { capability ->
            capabilities.remove(capability)
            verifier?.forget(capability)
        }
        tv.deviceId?.let { verifier?.forgetDevice(it) }
    }
}
