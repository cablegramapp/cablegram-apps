package app.cablegram.phone.cast

import app.cablegram.phone.PairedTv
import app.cablegram.phone.MeDevice
import app.cablegram.phone.TargetResult

data class CastRoute(val deviceId: String, val name: String)
data class CastDeviceStatus(val deviceId: String, val wrongTv: Boolean)

fun matchingCastRoute(tv: PairedTv?, routes: List<CastRoute>): CastRoute? =
    tv?.castDeviceId?.let { id -> routes.firstOrNull { it.deviceId == id } }

/** Status cannot enroll a new TV or authorize crossing the signed-in household. */
fun wrongTvRetry(status: CastDeviceStatus?, paired: List<PairedTv>, householdIds: Set<String>, alreadyRetried: Boolean): PairedTv? =
    status?.takeIf { it.wrongTv && !alreadyRetried && it.deviceId in householdIds }
        ?.let { value -> paired.firstOrNull { it.deviceId == value.deviceId } }

fun terminalCastIdle(playerState: Int, idleReason: Int): Boolean =
    playerState == 1 && idleReason in setOf(1, 2, 4) // SDK IDLE; FINISHED, CANCELED, ERROR

/** Poster metadata may use a public HTTPS image, never an authenticated/signed URL. */
fun publicPosterUrl(raw: String?): String? = raw?.takeIf { runCatching {
    val uri = java.net.URI(it)
    uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
}.getOrDefault(false) }

/** Unlike relay selection, an asleep Cast target must never switch to another online TV. */
fun castCommandTarget(selected: PairedTv?, household: List<MeDevice>?): TargetResult {
    val id = selected?.deviceId ?: return TargetResult.Failed("Choose a paired TV in Settings.")
    if (household != null && household.none { it.id == id })
        return TargetResult.Failed("${selected.name} is no longer connected. Choose a TV in Settings.")
    return TargetResult.Target(id, selected.name)
}

fun useCastForTitle(enabled: Boolean, senderAvailable: Boolean, routeAvailable: Boolean): Boolean =
    enabled && senderAvailable && routeAvailable
