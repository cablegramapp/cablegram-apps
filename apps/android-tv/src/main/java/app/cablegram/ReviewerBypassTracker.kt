package app.cablegram

import app.cablegram.data.DEMO_REVIEWER_PIN
import app.cablegram.data.DEMO_SELECT_CLICKS

class ReviewerBypassTracker(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val clickWindowMs: Long = 8_000L,
) {
    private var selectClicks = 0
    private var windowStartMs = 0L
    private var lastSelectMs = Long.MIN_VALUE / 2

    /**
     * A deliberate Select (not part of a rapid burst) must reach the focused
     * button, e.g. "New PIN". Only the quick repeats of the reviewer gesture
     * are swallowed. Call before [onSelect].
     */
    fun isDeliberateSelect(): Boolean = nowMs() - lastSelectMs > SELECT_BURST_GAP_MS
    private val pinBuffer = StringBuilder()

    fun onSelect(): Boolean {
        val now = nowMs()
        if (now - windowStartMs > clickWindowMs) {
            selectClicks = 0
            windowStartMs = now
        }
        selectClicks += 1
        lastSelectMs = now
        return selectClicks >= DEMO_SELECT_CLICKS
    }

    fun onDigit(digit: Char): Boolean {
        if (!digit.isDigit()) return false
        pinBuffer.append(digit)
        if (pinBuffer.length > DEMO_REVIEWER_PIN.length) {
            pinBuffer.delete(0, pinBuffer.length - DEMO_REVIEWER_PIN.length)
        }
        return pinBuffer.toString() == DEMO_REVIEWER_PIN
    }

    private companion object {
        const val SELECT_BURST_GAP_MS = 1_500L
    }
}
