package app.cablegram.phone

import app.cablegram.telegram.ByteRange
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * LAN media server. Authenticates callers only with a paired TV's device
 * capability (R-1); the pairing PIN is never a LAN credential.
 *
 * R-2 / CAB-37: [credentials] is kept in step with the paired TVs by the service, so a removed or revoked TV is
 * refused at once, without restarting the server.
 */
class LanLibraryServer(
    private val store: LibraryStore,
    private val commands: CommandQueue,
    private val credentials: LanCredentials,
    /** Required for private titles; without it private media is never served. */
    private val privatePasses: PrivatePassVerifier? = null,
    /** Streams Telegram titles from this phone's session for TVs that hold none (spec 004 US8). */
    private val telegram: TelegramMedia? = null,
    port: Int = PORT,
) : NanoHTTPD(port) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", """{"error":"invalid_lan_token"}""")
        }
        val path = session.uri.trimEnd('/')
        val isHead = session.method == Method.HEAD
        // T076 / R-6: validate remote commands against the supported set before
        // queueing anything; unknown or malformed commands are rejected 400.
        if (session.method == Method.POST && path == "/commands") {
            val body = HashMap<String, String>()
            return runCatching {
                session.parseBody(body)
                val raw = body["postData"] ?: ""
                val cmd = runCatching { json.decodeFromString<RemoteCommand>(raw) }.getOrNull()
                if (cmd == null) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"invalid_command"}""")
                } else if (cmd.command !in SUPPORTED_COMMANDS) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"unsupported_command"}""")
                } else if ((cmd.command in COMMANDS_REQUIRING_VIDEO && cmd.videoId.isNullOrBlank()) ||
                    (cmd.videoId != null && cmd.videoId.length > 200) ||
                    cmd.command.length > 32
                ) {
                    newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"invalid_command_payload"}""")
                } else {
                    commands.add(cmd.command, cmd.videoId)
                    jsonResponse("""{"ok":true}""")
                }
            }.getOrElse {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", """{"error":"invalid_body"}""")
            }
        }
        return when {
            (session.method == Method.GET || isHead) && path == "/library" -> {
                val items = store.list().map { it.copy(posterPath = null) }
                headAware(jsonResponse(json.encodeToString(LibraryFile(items = items))), isHead)
            }
            (session.method == Method.GET || isHead) && path.startsWith("/media/") -> {
                val id = path.removePrefix("/media/")
                val item = store.get(id) ?: return notFound()
                if (item.isPrivate) {
                    val pass = session.parameters["pass"]?.firstOrNull()
                    val tvDeviceId = suppliedCredentials(session).firstNotNullOfOrNull(credentials::tvDeviceId)
                    if (privatePasses?.allows(pass, id, tvDeviceId) != true) {
                        return newFixedLengthResponse(Response.Status.FORBIDDEN, "application/json", """{"error":"approval_required"}""")
                    }
                }
                val wrapped = serveItem(item, session.headers["range"], contentTypeFor(item))
                if (isHead) headAware(wrapped, true, keepContentLength = true) else wrapped
            }
            (session.method == Method.GET || isHead) && path.startsWith("/telegram/") -> {
                val media = telegram ?: return notFound()
                val uniqueId = path.removePrefix("/telegram/").takeIf { it.matches(UNIQUE_ID) } ?: return notFound()
                val file = media.resolve(uniqueId) ?: return notFound()
                if (file.size <= 0) return notFound()
                val response = when (val range = ByteRange.parse(session.headers["range"], file.size)) {
                    is ByteRange.Unsatisfiable -> unsatisfiable(file.size)
                    is ByteRange.Partial -> newFixedLengthResponse(
                        Response.Status.PARTIAL_CONTENT, file.mime,
                        TelegramRangeStream({ p, w -> media.read(file.fileId, p, w) }, range.start, range.end), range.length,
                    ).apply {
                        addHeader("Content-Range", range.contentRange)
                        addHeader("Accept-Ranges", "bytes")
                    }
                    is ByteRange.Full -> newFixedLengthResponse(
                        Response.Status.OK, file.mime,
                        TelegramRangeStream({ p, w -> media.read(file.fileId, p, w) }, 0, file.size - 1), file.size,
                    ).apply { addHeader("Accept-Ranges", "bytes") }
                }
                if (isHead) headAware(response, true, keepContentLength = true) else response
            }
            (session.method == Method.GET || isHead) && path.startsWith("/poster/") -> {
                val id = path.removePrefix("/poster/")
                val item = store.get(id) ?: return notFound()
                val poster = store.posterFile(item) ?: return notFound()
                headAware(serveFile(poster, null, "image/jpeg"), isHead)
            }
            session.method == Method.GET && path == "/commands" -> {
                jsonResponse(json.encodeToString(commands.drain()))
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not_found"}""")
        }
    }

    /** T076: HEAD must carry the same headers with no body. */
    private fun headAware(response: Response, isHead: Boolean, keepContentLength: Boolean = false): Response =
        if (!isHead) response
        else {
            val stream = response.data
            runCatching { stream.close() }
            val head = newFixedLengthResponse(response.status, response.mimeType, java.io.ByteArrayInputStream(ByteArray(0)), 0)
            for (name in listOf("content-range", "accept-ranges", "content-length")) {
                val value = response.getHeader(name)
                if (value != null) head.addHeader(name, value)
            }
            head
        }

    /** Device capabilities only; the pairing PIN is never a LAN credential (CAB-43). */
    private fun authorized(session: IHTTPSession): Boolean = credentials.authorized(suppliedCredentials(session))

    private fun suppliedCredentials(session: IHTTPSession): List<String> = listOfNotNull(
        session.headers["authorization"]?.removePrefix("Bearer ")?.trim(),
        session.parameters["token"]?.firstOrNull(),
    )

    private fun jsonResponse(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun notFound() =
        newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", """{"error":"not_found"}""")

    /** Send the real media type when known; do not assume video/mp4 (R-6). */
    private fun contentTypeFor(item: LibraryItem): String {
        val name = item.filename.lowercase().substringAfterLast('.', "")
        return when (name) {
            "mkv" -> "video/x-matroska"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "avi" -> "video/x-msvideo"
            "webm" -> "video/webm"
            "ts", "m2ts" -> "video/mp2t"
            "wmv" -> "video/x-ms-wmv"
            "flv" -> "video/x-flv"
            "3gp" -> "video/3gpp"
            "ogv" -> "video/ogg"
            "mpg", "mpeg" -> "video/mpeg"
            else -> "video/mp4"
        }
    }

    private fun serveItem(item: LibraryItem, rangeHeader: String?, mime: String): Response {
        val file = store.videoFile(item)
        if (file.exists()) return serveFile(file, rangeHeader, mime)
        val pfd = store.openPfd(item) ?: return notFound()
        return servePfd(pfd, store.videoLength(item).takeIf { it > 0 } ?: pfd.statSize, rangeHeader, mime)
    }

    private fun servePfd(
        pfd: android.os.ParcelFileDescriptor,
        length: Long,
        rangeHeader: String?,
        mime: String,
    ): Response {
        val range = parseRange(rangeHeader, length)
        if (range == null && !rangeHeader.isNullOrBlank() && length > 0) {
            // Same as serveFile: an unsatisfiable range (e.g. `bytes=<size>-`, which players send to
            // probe the end of a file) is 416. Answering 200 with the whole file made VLC give up
            // seeking and drop the audio track after a LAN→relay switch.
            runCatching { pfd.close() }
            return unsatisfiable(length)
        }
        val partial = range != null
        val start: Long
        val end: Long
        if (range == null) {
            start = 0L
            end = (length - 1).coerceAtLeast(0)
        } else {
            start = range.first
            end = range.second
        }
        val size = (end - start + 1).coerceAtLeast(0)
        val input = java.io.FileInputStream(pfd.fileDescriptor)
        runCatching { input.channel.position(start) }
        val stream = BoundedMediaInputStream(input, size) { pfd.close() }
        val response = newFixedLengthResponse(
            if (partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK,
            mime,
            stream,
            size,
        )
        if (partial) response.addHeader("Content-Range", "bytes $start-$end/$length")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    private fun serveFile(file: File, rangeHeader: String?, mime: String): Response {
        if (!file.exists()) return notFound()
        val length = file.length()
        val range = parseRange(rangeHeader, length)
        if (range == null && !rangeHeader.isNullOrBlank() && length > 0) {
            // Malformed / unsatisfiable range (e.g. start beyond EOF) → 416.
            return unsatisfiable(length)
        }
        val (start, end) = if (range != null) range else 0L to (length - 1).coerceAtLeast(0)
        val partial = range != null
        val size = end - start + 1
        val input = FileInputStream(file)
        try {
            input.channel.position(start)
        } catch (error: Exception) {
            input.close()
            throw error
        }
        val stream = BoundedMediaInputStream(input, size)
        val response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, size)
        response.addHeader("Content-Range", "bytes $start-$end/$length")
        response.addHeader("Accept-Ranges", "bytes")
        return response
    }

    /**
     * T076 / R-6: strict single-range parsing. Supports `bytes=a-b`, open-ended
     * `bytes=a-`, and suffix `bytes=-n` (last n bytes). Returns null for
     * absent, multi-part (first part already taken), or unsatisfiable ranges
     * so callers can emit 416.
     */
    private fun parseRange(rangeHeader: String?, length: Long): Pair<Long, Long>? {
        if (rangeHeader.isNullOrBlank() || !rangeHeader.startsWith("bytes=") || length <= 0) return null
        val spec = rangeHeader.removePrefix("bytes=").substringBefore(',').trim()
        if (spec.contains(',')) return null // multipart ranges unsupported
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val first = spec.substring(0, dash).trim()
        val second = spec.substring(dash + 1).trim()
        return when {
            first.isEmpty() && second.isEmpty() -> null
            first.isEmpty() -> { // suffix: last n bytes
                val n = second.toLongOrNull() ?: return null
                if (n <= 0) return null
                val start = (length - n).coerceAtLeast(0)
                start to (length - 1)
            }
            else -> {
                val start = first.toLongOrNull() ?: return null
                if (start >= length) return null // unsatisfiable
                val end = if (second.isEmpty()) length - 1 else (second.toLongOrNull() ?: return null)
                if (end < start) return null
                start to end.coerceAtMost(length - 1)
            }
        }
    }

    private fun unsatisfiable(length: Long): Response {
        val r = newFixedLengthResponse(
            Response.Status.RANGE_NOT_SATISFIABLE,
            "application/json",
            """{"error":"range_not_satisfiable"}""",
        )
        r.addHeader("Content-Range", "bytes */$length")
        return r
    }

    companion object {
        const val PORT = 8765
        /** Telegram file unique ids: URL-safe base64-like text. */
        private val UNIQUE_ID = Regex("[A-Za-z0-9_-]{8,200}")
        const val NSD_TYPE = "_cablegram._tcp."
        const val NSD_NAME = "CablegramPhone"

        /** T076 / R-6: commands the TV player actually implements. */
        private val SUPPORTED_COMMANDS = setOf(
            "pause", "play", "stop", "seek", "seek_back", "seek_forward", "next", "previous",
            "move", "select", "volume", "mute", "text_input", "play_video",
        )

        /** Commands that are meaningless without a target video. */
        private val COMMANDS_REQUIRING_VIDEO = setOf("seek", "seek_back", "seek_forward", "next", "previous", "play_video")
    }
}
