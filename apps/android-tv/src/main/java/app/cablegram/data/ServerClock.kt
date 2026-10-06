package app.cablegram.data

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.util.Date

/**
 * The control plane's idea of "now", as far as this TV can tell. A TV's own clock can be minutes off, and progress
 * is ordered by timestamp, so progress is stamped with this corrected time, learned from the `Date` header of the
 * control plane's responses (one-second resolution: within a second or two of server time).
 *
 * The correction is anchored on time since boot ([sinceBoot]), not on the wall clock, so an NTP correction or a
 * manual clock change while the control plane is unreachable does not shift the stamps. With [attach] it is also
 * kept on disk: after the app restarts within the same boot the anchor still holds, and after a reboot the last
 * known wall-clock offset is used until the first response (better than the bare device clock, which may be wrong).
 */
class ServerClock(
    private val wallNow: () -> Long,
    /** Milliseconds since boot; it never jumps. */
    private val sinceBoot: () -> Long,
) {
    /** One clock for both (tests that only move one clock): wall time and time since boot move together. */
    constructor(deviceNow: () -> Long) : this(deviceNow, deviceNow)

    /** Server time at boot (server time minus [sinceBoot]), learned this boot; null until then. */
    @Volatile private var bootAnchor: Long? = null
    /** Server time minus the device's wall clock at the last observation; the fallback across reboots. */
    @Volatile private var wallOffset: Long? = null
    @Volatile private var store: Store? = null
    @Volatile private var bootId: String? = null

    /** Where the learned correction is kept between app starts. */
    interface Store {
        fun load(): Saved?
        fun save(saved: Saved)
    }
    data class Saved(val bootId: String?, val bootAnchor: Long, val wallOffset: Long)

    fun now(): Long = bootAnchor?.let { sinceBoot() + it } ?: wallOffset?.let { wallNow() + it } ?: wallNow()

    /** True once a control-plane response was seen during this boot: [now] is then server time, not a guess. */
    val learnedThisBoot: Boolean get() = bootAnchor != null

    /** Call with the `Date` header of a control-plane response; a missing or unparseable header changes nothing. */
    fun observe(serverDate: Date?) {
        if (serverDate == null) return
        val anchor = serverDate.time - sinceBoot()
        val offset = serverDate.time - wallNow()
        bootAnchor = anchor
        wallOffset = offset
        store?.let { runCatching { it.save(Saved(bootId, anchor, offset)) } }
    }

    /**
     * Restores what an earlier run learned and keeps future observations. The boot anchor is only trusted within the
     * same boot ([bootId]); otherwise only the wall-clock offset is. Something observed already in this run wins.
     */
    fun attach(store: Store, bootId: String?) {
        this.bootId = bootId
        this.store = store
        if (bootAnchor != null) return
        val saved = runCatching { store.load() }.getOrNull() ?: return
        if (bootId != null && saved.bootId == bootId) bootAnchor = saved.bootAnchor
        if (wallOffset == null) wallOffset = saved.wallOffset
    }

    companion object {
        /** One clock for the whole app, fed by the API client's responses; [attachTo] makes it survive restarts. */
        val shared = ServerClock(System::currentTimeMillis, ::elapsedSinceBoot)

        /** `elapsedRealtime` counts deep sleep too; plain JVM unit tests have no Android clock and use the JVM's. */
        private fun elapsedSinceBoot(): Long = runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000 }

        /** Keeps [shared]'s correction in the app's preferences, tied to this boot (`Settings.Global.BOOT_COUNT`). */
        fun attachTo(context: Context) {
            val prefs = context.getSharedPreferences("cablegram_clock", Context.MODE_PRIVATE)
            val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).toString() }.getOrNull()
            shared.attach(object : Store {
                override fun load(): Saved? {
                    if (!prefs.contains("wall_offset")) return null
                    return Saved(prefs.getString("boot_id", null), prefs.getLong("boot_anchor", 0L), prefs.getLong("wall_offset", 0L))
                }
                override fun save(saved: Saved) {
                    prefs.edit().putString("boot_id", saved.bootId).putLong("boot_anchor", saved.bootAnchor).putLong("wall_offset", saved.wallOffset).apply()
                }
            }, boot)
        }
    }
}
