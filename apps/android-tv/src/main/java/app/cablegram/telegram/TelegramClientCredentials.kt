package app.cablegram.telegram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

// Shared by apps/android-phone and apps/android-tv. Keep both copies identical.

/** In-memory application identity. Deliberately no generated toString or persistent cache. */
class TelegramClientCredentials(val apiId: Int, val apiHash: String)

/** Fixed message with no server body, credential value or underlying exception attached. */
class TelegramClientUnavailable : Exception("Couldn't start Telegram. Check your connection and try again.")

/** Loopback, `.local`/`.lan` names and private IPv4 ranges. Parsed, so a public name like `10.example.com` is not private. */
internal fun isPrivateLanHost(host: String): Boolean {
    val h = host.trim().lowercase().removeSurrounding("[", "]")
    if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "10.0.2.2" || h.endsWith(".local") || h.endsWith(".lan")) return true
    val parts = h.split(".")
    if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
        val a = parts[0].toInt()
        val b = parts[1].toInt()
        if (a == 10 || a == 127) return true
        if (a == 192 && b == 168) return true
        if (a == 172 && b in 16..31) return true
    }
    return false
}

/** Uses the app's authenticated HTTP client (including phone token renewal). Never follows redirects. */
suspend fun fetchTelegramClientCredentials(
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    allowInsecureLocal: Boolean = false,
): TelegramClientCredentials = withContext(Dispatchers.IO) {
    try {
        val url = "${baseUrl.trimEnd('/')}/api/telegram/client-config".toHttpUrl()
        val local = isPrivateLanHost(url.host)
        if (token.isBlank() || (!url.isHttps && !(allowInsecureLocal && local))) throw TelegramClientUnavailable()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header("Cache-Control", "no-store").get().build()
        client.newBuilder().followRedirects(false).followSslRedirects(false).cache(null).build()
            .newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw TelegramClientUnavailable()
                val body = Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val id = body["api_id"]?.jsonPrimitive?.intOrNull ?: throw TelegramClientUnavailable()
                val hash = body["api_hash"]?.jsonPrimitive?.content ?: throw TelegramClientUnavailable()
                if (id <= 0 || !Regex("[a-fA-F0-9]{32}").matches(hash)) throw TelegramClientUnavailable()
                TelegramClientCredentials(id, hash)
            }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw TelegramClientUnavailable()
    }
}
