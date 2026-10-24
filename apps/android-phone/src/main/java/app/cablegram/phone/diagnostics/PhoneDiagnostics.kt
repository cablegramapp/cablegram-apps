package app.cablegram.phone.diagnostics

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.cablegram.phone.BuildConfig
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message
import io.sentry.protocol.SentryId
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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
    fun isAvailable(): Boolean = BuildConfig.SENTRY_DSN.isNotBlank()

    fun init(context: Context) {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank() || !DiagnosticsPreferences.isCrashReportingEnabled(context)) {
            return
        }
        if (Sentry.isEnabled()) return

        runCatching {
            SentryAndroid.init(context.applicationContext) { options ->
                options.dsn = dsn
                options.beforeSend = SentryOptions.BeforeSendCallback { event, _ ->
                    scrubEvent(event)
                }
                options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
                    scrubBreadcrumb(breadcrumb)
                }
            }
        }.onFailure {
            android.util.Log.e("PhoneDiagnostics", "Failed to initialize Sentry", it)
        }
    }

    fun close() {
        runCatching {
            if (Sentry.isEnabled()) {
                Sentry.close()
            }
        }
    }

    fun scrubEvent(event: SentryEvent): SentryEvent {
        event.message?.let { msg ->
            msg.formatted?.let { msg.formatted = SentryScrubber.scrubMessage(it) }
            msg.message?.let { msg.message = SentryScrubber.scrubMessage(it) }
        }

        event.exceptions?.forEach { ex ->
            ex.value?.let { ex.value = SentryScrubber.scrubMessage(it) }
            ex.stacktrace?.frames?.forEach { frame ->
                frame.absPath?.let { frame.absPath = SentryScrubber.scrubMessage(it) }
            }
        }

        event.breadcrumbs?.forEach { breadcrumb ->
            scrubBreadcrumb(breadcrumb)
        }

        event.transaction?.let {
            event.transaction = SentryScrubber.scrubMessage(it)
        }

        event.request?.let { req ->
            req.url?.let { req.url = SentryScrubber.scrubUrl(it) }
        }

        event.tags?.keys?.toList()?.forEach { key ->
            val v = event.getTag(key)
            if (v != null) {
                event.setTag(key, SentryScrubber.scrubMessage(v))
            }
        }

        event.extras?.keys?.toList()?.forEach { key ->
            val v = event.getExtra(key)
            if (v is String) {
                event.setExtra(key, scrubJsonOrText(v))
            }
        }

        return event
    }

    /** Scrubs a JSON string value by value (keeping it valid JSON); anything else is scrubbed as text. */
    private fun scrubJsonOrText(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return SentryScrubber.scrubMessage(text)
        return runCatching { scrubJsonValue(null, JSONTokener(trimmed).nextValue()).toString() }
            .getOrElse { SentryScrubber.scrubMessage(text) }
    }

    private fun scrubJsonValue(key: String?, value: Any?): Any? = when (value) {
        is JSONObject -> JSONObject().also { out ->
            value.keys().forEach { k -> out.put(k, scrubJsonValue(k, value.opt(k))) }
        }
        is JSONArray -> JSONArray().also { out ->
            for (i in 0 until value.length()) out.put(scrubJsonValue(key, value.opt(i)))
        }
        is String -> if (key != null && SentryScrubber.isSensitiveParam(key)) "[REDACTED]" else SentryScrubber.scrubMessage(value)
        else -> value
    }

    fun scrubBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message?.let {
            breadcrumb.message = SentryScrubber.scrubMessage(it)
        }
        breadcrumb.data.keys.toList().forEach { key ->
            val v = breadcrumb.getData(key)
            if (v is String) {
                breadcrumb.setData(key, SentryScrubber.scrubMessage(v))
            }
        }
        return breadcrumb
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

    /**
     * Explicit user-initiated problem report sends regardless of automatic crash reporting opt-out,
     * as the user is intentionally submitting this diagnostic feedback.
     */
    fun sendProblemReport(
        context: Context,
        description: String,
        playbackState: String? = null,
        dsn: String = BuildConfig.SENTRY_DSN,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (dsn.isBlank()) {
            android.util.Log.w("PhoneDiagnostics", "Cannot send problem report: SENTRY_DSN is not configured")
            onComplete(false)
            return
        }

        val report = buildProblemReport(context, description, playbackState)
        val crashReportingEnabled = DiagnosticsPreferences.isCrashReportingEnabled(context)

        // Off the main thread: flush and close wait for the queue to drain, which can take seconds on a bad network.
        Thread({
            val delivered = send(context, dsn, report, crashReportingEnabled)
            Handler(Looper.getMainLooper()).post { onComplete(delivered) }
        }, "problem-report").start()
    }

    private fun send(context: Context, dsn: String, report: ProblemReportData, crashReportingEnabled: Boolean): Boolean {
        return try {
            if (!Sentry.isEnabled()) {
                SentryAndroid.init(context.applicationContext) { options ->
                    options.dsn = dsn
                    options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> scrubEvent(event) }
                    options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ -> scrubBreadcrumb(breadcrumb) }
                }
            }

            val event = SentryEvent().apply {
                message = Message().apply {
                    formatted = "User Problem Report: ${report.description}"
                }
                setTag("app_version", report.appVersion)
                setTag("android_version", report.androidVersion.toString())
                setTag("device_model", report.model)
                setTag("device_manufacturer", report.manufacturer)
                report.playbackState?.let { setTag("playback_state", it) }
                setExtra("report_json", report.toJson())
            }

            val queued = Sentry.captureEvent(event) != SentryId.EMPTY_ID
            if (queued) Sentry.flush(FLUSH_TIMEOUT_MS)
            queued
        } catch (e: Throwable) {
            android.util.Log.e("PhoneDiagnostics", "Failed sending Sentry problem report", e)
            false
        } finally {
            if (!crashReportingEnabled) {
                close()
            }
        }
    }

    private const val FLUSH_TIMEOUT_MS = 3000L
}
