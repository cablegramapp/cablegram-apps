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

/** Uses the app's authenticated HTTP client (including phone token renewal). Never follows redirects. */
suspend fun fetchTelegramClientCredentials(
    client: OkHttpClient,
    baseUrl: String,
    token: String,
    allowInsecureLocal: Boolean = false,
): TelegramClientCredentials = withContext(Dispatchers.IO) {
    try {
        val url = "${baseUrl.trimEnd('/')}/api/telegram/client-config".toHttpUrl()
        val local = url.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
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
