package app.cablegram.data

import java.util.Date

/**
 * The control plane's idea of "now", as far as this TV can tell. A TV's own clock can be minutes off, and offline
 * progress is ordered by timestamp, so progress that has to wait in the queue is stamped with this corrected time:
 * the device clock plus the offset learned from the `Date` header of the last successful API response (one-second
 * resolution, so the stamp is within a second or two of server time). Until the first response the offset is zero,
 * and a cold start while offline uses the device clock until the first request succeeds.
 */
class ServerClock(private val deviceNow: () -> Long = System::currentTimeMillis) {
    @Volatile
    private var offsetMs = 0L

    fun now(): Long = deviceNow() + offsetMs

    /** Call with the `Date` header of an API response; a missing or unparseable header changes nothing. */
    fun observe(serverDate: Date?) {
        if (serverDate != null) offsetMs = serverDate.time - deviceNow()
    }

    companion object {
        /** One clock for the whole app, fed by the API client's responses. */
        val shared = ServerClock()
    }
}
