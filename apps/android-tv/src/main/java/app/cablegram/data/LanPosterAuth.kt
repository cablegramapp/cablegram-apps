package app.cablegram.data

import java.util.concurrent.ConcurrentHashMap
import okhttp3.Interceptor

/**
 * The phone's LAN posters, loaded with the TV's capability in a header (CAB-44).
 *
 * A poster URL used to carry the capability as `?token=`, and that one value streams the household's whole library.
 * [CablegramApi] now builds the URL without it and records which phone it points at; the image loader's client adds the
 * header for exactly those addresses and the `/poster/` path, so it reaches nothing else.
 */
object LanPosterAuth {
    private val capabilities = ConcurrentHashMap<String, String>()

    fun remember(host: String, port: Int, capability: String) {
        capabilities["$host:$port"] = capability
    }

    /** Called when the TV is signed out or unpaired, so no later image request carries an old capability. */
    fun clear() = capabilities.clear()

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val capability = capabilities["${url.host}:${url.port}"]
            ?.takeIf { url.scheme == "https" && url.encodedPath.startsWith("/poster/") && request.header("Authorization") == null }
        chain.proceed(if (capability == null) request else request.newBuilder().header("Authorization", "Bearer $capability").build())
    }
}
