package app.cablegram.diagnostics

/**
 * Scrubs personal media filenames, paths, URLs, Telegram links, and free-text secrets
 * per CAB-46 log and data hygiene rules.
 */
object SentryScrubber {
    private val EXACT_SENSITIVE_PARAMS = setOf(
        "token", "auth", "secret", "password", "passwd", "pwd", "key",
        "code", "credential", "signature", "sig", "hash", "session"
    )

    private val SENSITIVE_PARAM_PREFIXES = listOf("token_", "token-", "key_", "key-", "secret_", "secret-", "auth_", "auth-")
    private val SENSITIVE_PARAM_SUFFIXES = listOf("_token", "-token", "_key", "-key", "_secret", "-secret", "_password", "-password", "_auth", "-auth", "_code", "-code", "_sig", "-sig")

    fun isSensitiveParam(name: String): Boolean {
        val lower = name.lowercase().trim()
        if (lower in EXACT_SENSITIVE_PARAMS) return true
        if (SENSITIVE_PARAM_SUFFIXES.any { lower.endsWith(it) }) return true
        if (SENSITIVE_PARAM_PREFIXES.any { lower.startsWith(it) }) return true
        return false
    }

    // Free text secrets: password: abc, token = xyz, api_key: 123, Authorization: Bearer abc
    private val AUTH_HEADER_REGEX = Regex("(?i)\\b(authorization)\\s*:\\s*(?:bearer\\s+)?([^\r\n\\s,;\"'<>]+)")
    private val FREE_TEXT_SECRET_REGEX = Regex("(?i)\\b(password|passwd|secret|token|api[_-]?key)\\s*[:=]\\s*([^\r\n\\s,;\"'<>]+)")
    private val BEARER_TOKEN_REGEX = Regex("(?i)\\b(bearer)\\s+([a-zA-Z0-9_.-]{10,})")

    // Telegram deep links anywhere in text
    private val TG_LINK_REGEX = Regex("(?i)tg://[^\r\n\\s,;\"'<>]+(?<=\\S)")

    // Storage and local file paths (handles paths with spaces, e.g. /sdcard/Movies/My Film.mkv)
    private val STORAGE_PATH_REGEX = Regex("(?i)(?:/storage/|/sdcard/|/data/data/|/data/user/\\d+/|/mnt/|content://|file:///)[^\r\n:,;\"'<>\\[\\]{}]+(?<=\\S)")

    // Media filenames (including those with spaces like 'My Holiday 2024.mp4')
    private val MEDIA_FILE_REGEX = Regex("(?i)[\\w.\\-+&'()\\[\\]]+(?:\\s+[\\w.\\-+&'()\\[\\]]+)*\\.(?:mp4|mkv|avi|mov|webm|flv|wmv|m4v|3gp|mp3|flac|m4a|aac|ogg|wav|opus|srt|vtt|ass|sub)\\b")

    // HTTP/HTTPS URL regex to find URLs inside arbitrary text
    private val HTTP_URL_REGEX = Regex("(?i)https?://[^\r\n\\s\"'<>\\[\\]{}]+(?<=\\S)")

    /**
     * Scrubs query parameters and embedded user credentials of a URL without external Uri parser.
     */
    fun scrubUrl(url: String): String {
        if (url.isBlank()) return url
        if (url.startsWith("tg://", ignoreCase = true)) {
            return "tg://[REDACTED]"
        }
        val queryIndex = url.indexOf('?')
        if (queryIndex == -1) {
            return scrubUserInfo(url)
        }
        val baseUrl = scrubUserInfo(url.substring(0, queryIndex))
        val afterQuery = url.substring(queryIndex + 1)
        val fragmentIndex = afterQuery.indexOf('#')
        val query = if (fragmentIndex != -1) afterQuery.substring(0, fragmentIndex) else afterQuery
        val fragment = if (fragmentIndex != -1) afterQuery.substring(fragmentIndex) else ""

        val scrubbedQuery = query.split('&').joinToString("&") { param ->
            val equalsIndex = param.indexOf('=')
            if (equalsIndex != -1) {
                val key = param.substring(0, equalsIndex)
                if (isSensitiveParam(key)) {
                    "$key=REDACTED"
                } else {
                    param
                }
            } else {
                if (isSensitiveParam(param)) "REDACTED" else param
            }
        }
        return "$baseUrl?$scrubbedQuery$fragment"
    }

    private fun scrubUserInfo(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return url
        val rest = url.substring(schemeEnd + 3)
        val slashIndex = rest.indexOf('/')
        val hostPart = if (slashIndex != -1) rest.substring(0, slashIndex) else rest
        val pathPart = if (slashIndex != -1) rest.substring(slashIndex) else ""
        val atIndex = hostPart.indexOf('@')
        if (atIndex != -1) {
            val hostOnly = hostPart.substring(atIndex + 1)
            return url.substring(0, schemeEnd + 3) + "[REDACTED]@" + hostOnly + pathPart
        }
        return url
    }

    /**
     * Scrubs personal media filenames, paths, URLs, Telegram links, and free-text secrets from messages.
     * Does not mangle prose or innocent query parameters like monkey or zipcode.
     */
    fun scrubMessage(message: String): String {
        if (message.isBlank()) return message
        var cleaned = message

        // 1. Scrub tg:// links anywhere in message
        cleaned = TG_LINK_REGEX.replace(cleaned, "tg://[REDACTED]")

        // 2. Scrub HTTP/HTTPS URLs query parameters
        cleaned = HTTP_URL_REGEX.replace(cleaned) { matchResult ->
            scrubUrl(matchResult.value)
        }

        // 3. Scrub free text secrets (Authorization: Bearer xyz, password: 123, token=xyz, etc.)
        cleaned = AUTH_HEADER_REGEX.replace(cleaned) { "Authorization: [REDACTED]" }
        cleaned = BEARER_TOKEN_REGEX.replace(cleaned) { "${it.groupValues[1]} [REDACTED]" }
        cleaned = FREE_TEXT_SECRET_REGEX.replace(cleaned) { matchResult ->
            "${matchResult.groupValues[1]}: [REDACTED]"
        }

        // 4. Scrub storage paths (e.g. /sdcard/Movies/My Film.mkv)
        cleaned = STORAGE_PATH_REGEX.replace(cleaned, "[PATH_REDACTED]")

        // 5. Scrub standalone media filenames (e.g. My Holiday 2024.mp4)
        cleaned = MEDIA_FILE_REGEX.replace(cleaned, "[MEDIA_FILE_REDACTED]")

        return cleaned
    }
}
