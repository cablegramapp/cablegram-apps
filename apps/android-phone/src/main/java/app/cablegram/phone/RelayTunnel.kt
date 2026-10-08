package app.cablegram.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** What the phone may do when a relay stream would be uploaded over mobile data (spec 003 US3). */
enum class RelayMobileDataPolicy { Ask, Always, Never }

/** Why a relay request was refused before reaching the media server. */
enum class RelayRefusal { MobileDataPending, MobileDataNotAllowed }

/**
 * Keeps this phone reachable through the Cablegram relay (spec 003, contracts/relay-protocol.md).
 * The relay forwards a TV's request over this WebSocket; it is executed against the phone's own
 * media server on loopback, so the TV capability, private-play pass and Range handling are
 * exactly the LAN ones. Body bytes are sent only within the credit the relay grants.
 */
class RelayTunnel(
    private val relayUrl: () -> String?,
    private val accountToken: () -> String?,
    private val phoneDeviceId: () -> String?,
    private val networkType: () -> String,
    /** Returns null to serve, or the refusal to send back (mobile-data policy). */
    private val admit: (network: String) -> RelayRefusal?,
    private val onActivity: (activeStreams: Int, bytesSent: Long, network: String) -> Unit = { _, _, _ -> },
    /** The media server's plain listener on 127.0.0.1; the LAN one is TLS (CAB-48). */
    private val localPort: Int,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) {
    private class Stream(val job: Job, val credit: Channel<Long>)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val streams = ConcurrentHashMap<Int, Stream>()
    private val bytesSent = AtomicLong(0)
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var socket: WebSocket? = null
    @Volatile private var connectedNetwork = "other"
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive) {
                val url = relayUrl()
                val token = accountToken()
                val deviceId = phoneDeviceId()
                if (url == null || token.isNullOrBlank() || deviceId.isNullOrBlank()) {
                    delay(15_000)
                    continue
                }
                val closed = Channel<Unit>(1)
                val opened = connect(url, token, deviceId) { closed.trySend(Unit) }
                closed.receive()
                cancelAll()
                socket = null
                backoff = if (opened()) 1_000L else (backoff * 2).coerceAtMost(30_000L)
                delay(backoff)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        socket?.close(1000, "stopped")
        cancelAll()
        scope.cancel()
    }

    /** Re-announce the network after a Wi-Fi ↔ mobile change so the TV shows the right notice. */
    fun networkChanged() {
        val network = networkType()
        if (network == connectedNetwork) return
        connectedNetwork = network
        socket?.send(buildJsonObject { put("t", "hello"); put("v", 1); put("network", network) }.toString())
    }

    private fun connect(url: String, token: String, deviceId: String, onClosed: () -> Unit): () -> Boolean {
        var wasOpen = false
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("X-Cablegram-Device", deviceId)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wasOpen = true
                connectedNetwork = networkType()
                PairLog.i("Relay connected network=$connectedNetwork")
                webSocket.send(buildJsonObject { put("t", "hello"); put("v", 1); put("network", connectedNetwork) }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val sid = message["sid"]?.jsonPrimitive?.intOrNull
                when (message["t"]?.jsonPrimitive?.contentOrNull) {
                    "req" -> if (sid != null) serve(webSocket, sid, message)
                    "credit" -> {
                        val bytes = message["bytes"]?.jsonPrimitive?.longOrNull ?: return
                        sid?.let { streams[it]?.credit?.trySend(bytes) }
                    }
                    "cancel" -> sid?.let { streams.remove(it)?.job?.cancel() }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                PairLog.w("Relay connection failed http=${response?.code} ${t.javaClass.simpleName}")
                onClosed()
            }
        })
        return { wasOpen }
    }

    private fun serve(ws: WebSocket, sid: Int, message: JsonObject) {
        val network = networkType()
        admit(network)?.let { refusal ->
            val reason = when (refusal) {
                RelayRefusal.MobileDataPending -> "mobile_data_pending"
                RelayRefusal.MobileDataNotAllowed -> "mobile_data_not_allowed"
            }
            ws.send(buildJsonObject { put("t", "error"); put("sid", sid); put("status", 403); put("reason", reason) }.toString())
            return
        }
        val method = message["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val path = message["path"]?.jsonPrimitive?.contentOrNull ?: return
        // Only the media server's own paths; the relay already restricts them, this is defense in depth.
        if (!path.startsWith("/media/") && !path.startsWith("/poster/") && !path.startsWith("/telegram/")) {
            ws.send(buildJsonObject { put("t", "error"); put("sid", sid); put("status", 404); put("reason", "not_found") }.toString())
            return
        }
        val headers = message["headers"]?.jsonObject
        val initialCredit = message["credit"]?.jsonPrimitive?.longOrNull ?: (1024L * 1024L)
        val credit = Channel<Long>(Channel.UNLIMITED)
        val job = scope.launch {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("http://127.0.0.1:$localPort$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 5_000
                    readTimeout = 30_000
                    headers?.forEach { (name, value) -> value.jsonPrimitive.contentOrNull?.let { setRequestProperty(name, it) } }
                }
                val status = connection.responseCode
                ws.send(buildJsonObject {
                    put("t", "head"); put("sid", sid); put("status", status)
                    putJsonObject("headers") {
                        listOf("content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag")
                            .forEach { name -> connection.getHeaderField(name)?.let { put(name, it) } }
                    }
                }.toString())
                if (method != "HEAD") {
                    val input = if (status >= 400) connection.errorStream else connection.inputStream
                    input?.use { stream ->
                        var available = initialCredit
                        val buffer = ByteArray(64 * 1024)
                        while (isActive) {
                            while (available <= 0) available += credit.receive()
                            val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), available).toInt())
                            if (read < 0) break
                            val frame = ByteBuffer.allocate(4 + read).putInt(sid).put(buffer, 0, read).array()
                            if (!ws.send(frame.toByteString())) break
                            available -= read
                            onActivity(streams.size, bytesSent.addAndGet(read.toLong()), network)
                        }
                    }
                }
                if (isActive) ws.send(buildJsonObject { put("t", "end"); put("sid", sid) }.toString())
            } catch (error: Exception) {
                if (isActive) {
                    ws.send(buildJsonObject { put("t", "error"); put("sid", sid); put("status", 502); put("reason", "phone_error") }.toString())
                }
            } finally {
                connection?.disconnect()
                streams.remove(sid)
                onActivity(streams.size, bytesSent.get(), network)
            }
        }
        streams[sid] = Stream(job, credit)
    }

    private fun cancelAll() {
        streams.values.forEach { it.job.cancel() }
        streams.clear()
    }

    companion object {
        /** `https://api.example/` → `wss://api.example/relay/v1/phone`. */
        fun phoneUrl(apiBaseUrl: String): String? {
            val base = apiBaseUrl.trim().trimEnd('/')
            return when {
                base.startsWith("https://") -> "wss://" + base.removePrefix("https://") + "/relay/v1/phone"
                base.startsWith("http://") -> "ws://" + base.removePrefix("http://") + "/relay/v1/phone"
                else -> null
            }
        }
    }
}
