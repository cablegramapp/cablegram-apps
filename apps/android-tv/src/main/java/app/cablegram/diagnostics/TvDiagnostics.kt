package app.cablegram.diagnostics

import android.content.Context
import android.os.Build
import app.cablegram.BuildConfig
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

object TvDiagnostics {
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank() || !DiagnosticsPreferences.isCrashReportingEnabled(context)) {
            return
        }

        runCatching {
            val sentryAndroidClass = Class.forName("io.sentry.android.core.SentryAndroid")
            val initMethod = sentryAndroidClass.getMethod("init", Context::class.java, Class.forName("io.sentry.Sentry\$OptionsConfiguration"))
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
        description: String = "User initiated report from TV",
        playbackState: String? = null,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val report = buildProblemReport(context, description, playbackState)
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) {
            android.util.Log.i("TvDiagnostics", "Diagnostic problem report recorded: ${report.toJson()}")
            onComplete(true)
            return
        }

        runCatching {
            val sentryClass = Class.forName("io.sentry.Sentry")
            val captureMessageMethod = sentryClass.getMethod("captureMessage", String::class.java)
            captureMessageMethod.invoke(null, "TV Problem Report: ${report.description}")
            onComplete(true)
        }.onFailure {
            android.util.Log.e("TvDiagnostics", "Failed sending Sentry problem report", it)
            onComplete(false)
        }
    }
}
