package app.cablegram.diagnostics

import android.net.Uri

object SentryScrubber {
    private val SENSITIVE_QUERY_PARAMS = setOf("token", "secret", "auth", "key", "password", "code", "credential")
    private val MEDIA_EXTENSION_REGEX = Regex("(?i)[a-zA-Z0-9_.-]+\\.(mp4|mkv|avi|mov|webm|srt|vtt|ts|m4v)")
    private val STORAGE_PATH_REGEX = Regex("(?i)(/storage/|/sdcard/|content://)[^\\s,]+")

    fun scrubUrl(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("tg://", ignoreCase = true)) {
            return "tg://login?token=REDACTED"
        }
        return try {
            val uri = Uri.parse(url)
            val queryNames = uri.queryParameterNames
            if (queryNames.isEmpty()) return url

            val builder = uri.buildUpon().clearQuery()
            for (name in queryNames) {
                val isSensitive = SENSITIVE_QUERY_PARAMS.any { name.contains(it, ignoreCase = true) }
                if (isSensitive) {
                    builder.appendQueryParameter(name, "REDACTED")
                } else {
                    builder.appendQueryParameter(name, uri.getQueryParameter(name))
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            url.replace(Regex("(?i)([?&](?:token|code|secret|password|key)=)[^&]+"), "$1REDACTED")
        }
    }

    /**
     * Scrubs personal media filenames and file paths per CAB-46 log and data hygiene rules.
     */
    fun scrubMessage(message: String): String {
        if (message.isBlank()) return message
        var cleaned = scrubUrl(message)
        cleaned = STORAGE_PATH_REGEX.replace(cleaned, "[PATH_REDACTED]")
        cleaned = MEDIA_EXTENSION_REGEX.replace(cleaned, "[MEDIA_FILE_REDACTED]")
        return cleaned
    }
}
