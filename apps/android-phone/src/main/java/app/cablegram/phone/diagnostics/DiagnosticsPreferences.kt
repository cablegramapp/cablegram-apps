package app.cablegram.phone.diagnostics

import android.content.Context
import android.content.SharedPreferences

object DiagnosticsPreferences {
    private const val PREFS_NAME = "cablegram_diagnostics"
    private const val KEY_CRASH_REPORTING_ENABLED = "crash_reporting_enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Crash and diagnostics reporting defaults to enabled with an explicit opt-out in Settings (D-002).
     */
    fun isCrashReportingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CRASH_REPORTING_ENABLED, true)

    fun setCrashReportingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CRASH_REPORTING_ENABLED, enabled).apply()
    }
}
