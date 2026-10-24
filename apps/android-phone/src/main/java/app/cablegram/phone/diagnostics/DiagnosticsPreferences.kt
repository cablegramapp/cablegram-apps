package app.cablegram.phone.diagnostics

import android.content.Context

object DiagnosticsPreferences {
    private const val PREFS_NAME = "cablegram_diagnostics"
    private const val KEY_CRASH_REPORTING = "crash_reporting_enabled"

    fun isCrashReportingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASH_REPORTING, true)

    fun setCrashReportingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CRASH_REPORTING, enabled)
            .apply()

        if (enabled) {
            PhoneDiagnostics.init(context)
        } else {
            PhoneDiagnostics.close()
        }
    }
}
