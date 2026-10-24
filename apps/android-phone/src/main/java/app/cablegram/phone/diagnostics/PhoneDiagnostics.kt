package app.cablegram.phone.diagnostics

import android.content.Context
import android.os.Build
import app.cablegram.phone.BuildConfig
import org.json.JSONObject

data class ProblemReportData(
    val appVersion: String,
    val versionCode: Int,
    val manufacturer: String,
    val model: String,
    val androidVersion: Int,
    val description: String,
    val playbackState: String?,
) {
    fun toJson(): String = JSONObject().apply {
        put("appVersion", appVersion)
        put("versionCode", versionCode)
        put("manufacturer", manufacturer)
        put("model", model)
        put("androidVersion", androidVersion)
        put("description", SentryScrubber.scrubMessage(description))
        playbackState?.let { put("playbackState", SentryScrubber.scrubMessage(it)) }
    }.toString()
}

object PhoneDiagnostics {
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank() || !DiagnosticsPreferences.isCrashReportingEnabled(context)) {
            return
        }

        runCatching {
            // Optional Sentry Android initialization via reflection to decouple runtime dependency
            val sentryAndroidClass = Class.forName("io.sentry.android.core.SentryAndroid")
            val initMethod = sentryAndroidClass.getMethod("init", Context::class.java, Class.forName("io.sentry.Sentry\$OptionsConfiguration"))
            // If sentry-android is on classpath, initialize it
            initMethod.invoke(null, context.applicationContext, null)
        }
    }

    fun buildProblemReport(context: Context, description: String, playbackState: String? = null): ProblemReportData {
        return ProblemReportData(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.SDK_INT,
            description = SentryScrubber.scrubMessage(description),
            playbackState = playbackState?.let { SentryScrubber.scrubMessage(it) },
        )
    }

    fun sendProblemReport(
        context: Context,
        description: String,
        playbackState: String? = null,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val report = buildProblemReport(context, description, playbackState)
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            // No DSN configured: log sanitized report and succeed
            android.util.Log.i("PhoneDiagnostics", "Diagnostic problem report recorded: ${report.toJson()}")
            onComplete(true)
            return
        }

        runCatching {
            val sentryClass = Class.forName("io.sentry.Sentry")
            val captureMessageMethod = sentryClass.getMethod("captureMessage", String::class.java)
            captureMessageMethod.invoke(null, "User Problem Report: ${report.description}")
            onComplete(true)
        }.onFailure {
            android.util.Log.e("PhoneDiagnostics", "Failed sending Sentry problem report", it)
            onComplete(false)
        }
    }
}
