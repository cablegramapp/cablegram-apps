package app.cablegram.phone

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived stream links for one title and one TV (CAB-44).
 *
 * A player cannot send request headers, so a LAN stream URL used to carry the TV's long-lived capability as `?token=`.
 * Now the TV asks for a link with the capability in a header and the player only ever sees the link: whoever captures
 * it can play that one title, for that TV, briefly.
 *
 * A link is a random id this phone keeps, not a signature: removing or revoking the TV ends it at once, and a phone
 * restart ends them all. It lasts [idleMs] after it was issued or last used, so a film being watched keeps its link,
 * but never longer than [maxMs] in all.
 */
class LanStreamLinks(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val idleMs: Long = 15 * 60_000L,
    private val maxMs: Long = 12 * 60 * 60_000L,
    private val random: SecureRandom = SecureRandom(),
) {
    /** What a live link grants: its TV, when known, for private-title passes bound to that TV. */
    data class Grant(val tvDeviceId: String?)

    private class Link(val path: String, val capability: String, val tvDeviceId: String?, val endsAtMs: Long) {
        @Volatile var idleUntilMs: Long = 0
    }

    private val links = ConcurrentHashMap<String, Link>()

    /** How long an unused link lasts, for the TV to know when it should ask again. */
    val idleSeconds: Long get() = idleMs / 1000

    /** A new link id for [path] (`/media/<id>` or `/telegram/<id>`), for the TV holding [capability]. */
    fun issue(path: String, capability: String, tvDeviceId: String?): String {
        val now = nowMs()
        links.entries.removeIf { it.value.expiredAt(now) }
        // A TV asks once per playback; this only bounds a misbehaving one. The link idle the longest goes first.
        while (links.size >= MAX_LINKS) {
            val oldest = links.entries.minByOrNull { it.value.idleUntilMs } ?: break
            links.remove(oldest.key)
        }
        val bytes = ByteArray(32).also(random::nextBytes)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        links[id] = Link(path, capability, tvDeviceId, now + maxMs).apply { idleUntilMs = now + idleMs }
        return id
    }

    /**
     * The grant when [id] is a live link for exactly [path] and [stillAccepted] still accepts its capability; null
     * otherwise. Using a link keeps it alive for another [idleMs].
     */
    fun redeem(id: String?, path: String, stillAccepted: (capability: String) -> Boolean): Grant? {
        if (id.isNullOrBlank()) return null
        val link = links[id] ?: return null
        val now = nowMs()
        if (link.expiredAt(now)) {
            links.remove(id)
            return null
        }
        if (link.path != path) return null
        // The TV may have been removed or revoked since: a link never outlives its TV's capability.
        if (!stillAccepted(link.capability)) {
            links.remove(id)
            return null
        }
        link.idleUntilMs = minOf(now + idleMs, link.endsAtMs)
        return Grant(link.tvDeviceId)
    }

    private fun Link.expiredAt(now: Long) = now >= idleUntilMs || now >= endsAtMs

    private companion object {
        const val MAX_LINKS = 256
    }
}
