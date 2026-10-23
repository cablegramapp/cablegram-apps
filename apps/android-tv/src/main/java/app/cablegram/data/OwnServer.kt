package app.cablegram.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** What "Use your own server" found at an address, before this TV switches to it. */
sealed interface ServerProbe {
    data class Found(val url: String, val edition: String) : ServerProbe
    data object InvalidAddress : ServerProbe
    data object Unreachable : ServerProbe
    data object NotCablegram : ServerProbe
}

/**
 * A typed address as a server URL. Without a scheme, a home-network host gets `http://` (the self-hosting default)
 * and anything else `https://`; [normalizeServerUrl] then decides whether it is allowed.
 */
fun ownServerUrl(address: String): String? {
    val typed = address.trim().trimEnd('/')
    if (typed.isEmpty()) return null
    if ("://" in typed) return normalizeServerUrl(typed)
    val host = typed.substringBefore('/').substringBeforeLast(':')
    return normalizeServerUrl((if (isPrivateLanHost(host)) "http://" else "https://") + typed)
}

/** No credentials, redirects or shared state: this asks a server the TV doesn't trust yet. */
private val probeClient: OkHttpClient by lazy {
    cablegramHttpClient()
        .connectTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** `GET {address}/api/server`; only an answer shaped like Cablegram's counts as a server. */
suspend fun probeServer(address: String, client: OkHttpClient = probeClient): ServerProbe {
    val url = ownServerUrl(address) ?: return ServerProbe.InvalidAddress
    return withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url("$url/api/server").get().build()).execute().use { response ->
                val body = if (response.isSuccessful) response.body?.string().orEmpty() else ""
                val fields = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                val edition = fields?.get("edition")?.jsonPrimitive?.contentOrNull
                if (fields?.containsKey("api_version") == true && edition != null) ServerProbe.Found(url, edition) else ServerProbe.NotCablegram
            }
        } catch (_: IOException) {
            ServerProbe.Unreachable
        }
    }
}
