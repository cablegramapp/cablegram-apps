package app.cablegram

import fi.iki.elonen.NanoHTTPD
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Plays a cloud copy that needs a request header (Google Drive's bearer token) in LibVLC, which cannot send one
 * (spec 006). It listens on 127.0.0.1 only and forwards `GET /cloud/<id>` to the registered HTTPS URL with the header
 * added, passing `Range` through and the answer back, status included, so seeking works and errors reach the player
 * as they are. The header lives in memory only: it is in no URL and no log line.
 *
 * A renewal calls [register] again with the same URL and a fresh header; the local URL stays the same, so the
 * player never has to reload.
 *
 * CAB-48: a second instance plays the phone's LAN stream, which is HTTPS with a certificate LibVLC cannot pin; its
 * [http] client pins it ([app.cablegram.data.LanTls]). It serves `/lan/<id>/<upstream path>`, so the local URL still
 * says whether it is a title or a Telegram file.
 */
class CloudStreamServer(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    /** Only these upstream URLs are ever fetched; the default is any HTTPS URL. */
    private val allowed: (HttpUrl) -> Boolean = { it.isHttps },
    /** Notes for the log. They never contain a URL or a header. */
    private val log: (String) -> Unit = {},
    /** The first path segment of the local URLs. */
    private val prefix: String = "cloud",
    /** Append the upstream path to the local URL, for code that reads the kind of stream from it. */
    private val keepPath: Boolean = false,
) : NanoHTTPD("127.0.0.1", 0) {
    private class Upstream(val url: String, @Volatile var headers: Map<String, String>)

    private val byId = ConcurrentHashMap<String, Upstream>()
    private val idByUrl = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    /** A local URL that serves [url] with [headers]; null when [url] is not one this server will fetch. */
    fun register(url: String, headers: Map<String, String>): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!allowed(parsed)) return null
        // An unguessable id per upstream URL: another app on the TV cannot find the stream by trying paths.
        val id = idByUrl.computeIfAbsent(url) { ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) } }
        val clean = headers.filterKeys { it.matches(Regex("[A-Za-z0-9-]+")) }.filterValues { !it.contains('\n') && !it.contains('\r') }
        byId.compute(id) { _, existing ->
            log(if (existing == null) "Cloud stream: registered a stream" else "Cloud stream: renewed the header of a stream")
            existing?.also { it.headers = clean } ?: Upstream(url, clean)
        }
        return "http://127.0.0.1:$listeningPort/$prefix/$id" + if (keepPath) parsed.encodedPath else ""
    }

    override fun serve(session: IHTTPSession): Response {
        val id = session.uri.takeIf { it.startsWith("/$prefix/") }?.removePrefix("/$prefix/")?.substringBefore('/')
        val upstream = id?.let { byId[it] } ?: return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        if (session.method != Method.GET && session.method != Method.HEAD) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "")
        }
        val request = Request.Builder().url(upstream.url).method(session.method.name, null).apply {
            upstream.headers.forEach { (name, value) -> header(name, value) }
            session.headers["range"]?.let { header("Range", it) }
        }.build()
        val response = try {
            http.newCall(request).execute()
        } catch (_: java.io.IOException) {
            return newFixedLengthResponse(statusOf(502, "Bad Gateway"), MIME_PLAINTEXT, "upstream unreachable")
        }
        val body = response.body
        val mime = response.header("Content-Type") ?: "application/octet-stream"
        val status = statusOf(response.code, response.message.ifBlank { "Upstream" })
        // Closing the player's connection closes the upstream one too.
        val stream: InputStream = if (body == null || session.method == Method.HEAD) {
            response.close()
            java.io.ByteArrayInputStream(ByteArray(0))
        } else object : FilterInputStream(body.byteStream()) {
            override fun close() {
                try { super.close() } finally { response.close() }
            }
        }
        val length = if (session.method == Method.HEAD) 0L else (body?.contentLength() ?: 0L)
        val out = if (body != null && length < 0) newChunkedResponse(status, mime, stream) else newFixedLengthResponse(status, mime, stream, length)
        for (name in PASSED_HEADERS) response.header(name)?.let { out.addHeader(name, it) }
        return out
    }

    /** Any status code, so 429, 502 and the rest reach the player as they are, not as a generic error. */
    private fun statusOf(code: Int, reason: String) = object : Response.IStatus {
        override fun getDescription() = "$code $reason"
        override fun getRequestStatus() = code
    }

    private companion object {
        val PASSED_HEADERS = listOf("Content-Range", "Accept-Ranges", "ETag", "Last-Modified")
    }
}
