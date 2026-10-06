package app.cablegram.phone

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaDataSource
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@Serializable data class SubtitlePreferences(val languages: List<String> = listOf("fa", "en", "de"), val sdh: String = "normal", val includeForced: Boolean = true, val autoSelect: Boolean = false)
@Serializable data class SubtitleCue(val start: Double, val end: Double, val text: String)
@Serializable data class SubtitleAlignment(val verified: Boolean = false, val offset: Double = 0.0, val scale: Double = 1.0, val reason: String = "", val correlation: Double = 0.0)
@Serializable data class SubtitleMatch(val id: String, val language: String, val confidence: String = "Unverified", val alignment: SubtitleAlignment? = null, val cues: List<SubtitleCue>? = null, val error: String? = null, val provider: String = "", val releaseName: String = "", val explanations: List<String> = emptyList())
@Serializable data class SubtitleSelection(val id: String, val language: String = "", val confidence: String = "Unverified")
@Serializable data class SubtitleProviderFailure(val provider: String = "", val code: String, val retryAfter: Int? = null)
@Serializable data class SubtitleDiscovery(val id: String, val sourceId: String, val candidates: List<SubtitleMatch> = emptyList(), val status: String, val errors: List<SubtitleProviderFailure> = emptyList(), val selected: SubtitleSelection? = null, val existingLanguages: List<String> = emptyList())
@Serializable data class SubtitleActivityWindow(val start: Double, val step: Double = 0.1, val bits: String)
@Serializable data class SubtitleFingerprint(val version: Int = 1, val windows: List<SubtitleActivityWindow>)
@Serializable data class SubtitleTechnical(val hash: String? = null, val size: Long? = null, val duration: Double? = null, val fps: Double? = null, val codec: String? = null, val resolution: String? = null, val audioLanguages: List<String> = emptyList(), val embeddedLanguages: List<String> = emptyList())
@Serializable data class SubtitleSearchRequest(val id: String, val identity: String, val language: String? = null, val languages: List<String>? = null, val technical: SubtitleTechnical? = null, val fingerprint: SubtitleFingerprint? = null)

/** A video the phone can analyse and preview: a local file, or a Telegram title read in small windows. */
interface SubtitleMediaInput : AutoCloseable {
    val size: Long
    /** True when reading means fetching over the network, which can be slow and must stay cancellable. */
    val remote: Boolean get() = false
    /** Bytes fetched over the network so far (0 for local files); shown so a slow fetch doesn't look like nothing happening. */
    val bytesFetched: Long get() = 0
    /** Caps what the audio check may fetch (whole check, and each section); past it reads end as if the file stopped. */
    fun limitAnalysis(totalBytes: Long, sectionBytes: Long) {}
    fun startSection() {}
    val budgetHit: Boolean get() = false
    fun attach(extractor: MediaExtractor)
    fun attach(player: MediaPlayer)
    /** Up to [length] bytes at [position], or null when nothing could be read. */
    fun readAt(position: Long, length: Int): ByteArray?
}

class FileMediaInput(private val pfd: ParcelFileDescriptor) : SubtitleMediaInput {
    override val size: Long get() = pfd.statSize
    override fun attach(extractor: MediaExtractor) = extractor.setDataSource(pfd.fileDescriptor)
    override fun attach(player: MediaPlayer) = player.setDataSource(pfd.fileDescriptor)
    override fun readAt(position: Long, length: Int): ByteArray? = runCatching {
        val bytes = ByteArray(length); var read = 0
        while (read < length) { val n = Os.pread(pfd.fileDescriptor, bytes, read, length - read, position + read); if (n <= 0) break; read += n }
        bytes.copyOf(read).takeIf { it.isNotEmpty() }
    }.getOrNull()
    override fun close() = pfd.close()
}

/**
 * Reads a Telegram title through the phone's own session; nothing is stored beyond the reader's small window.
 * Every read is bounded and can be abandoned: [close] makes pending and later reads return at once, so skipping
 * or leaving the screen never waits on Telegram.
 */
class TelegramMediaInput(private val media: TelegramMedia, private val file: TelegramFileRef) : SubtitleMediaInput {
    override val size: Long get() = file.size
    override val remote: Boolean get() = true
    @Volatile private var closed = false
    private val fetched = java.util.concurrent.atomic.AtomicLong(0)
    override val bytesFetched: Long get() = fetched.get()
    @Volatile private var totalLimit = Long.MAX_VALUE
    @Volatile private var sectionLimit = Long.MAX_VALUE
    @Volatile private var sectionStart = 0L
    @Volatile private var hit = false
    override val budgetHit: Boolean get() = hit
    override fun limitAnalysis(totalBytes: Long, sectionBytes: Long) { totalLimit = totalBytes; sectionLimit = sectionBytes }
    override fun startSection() { sectionStart = fetched.get() }
    private fun overBudget(): Boolean {
        val now = fetched.get()
        return (now > totalLimit || now - sectionStart > sectionLimit).also { if (it) hit = true }
    }
    private val readers = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "subtitle-telegram-read").apply { isDaemon = true } }
    // Android's extractors ask for a few hundred bytes at a time (one audio frame). One Telegram round trip per request
    // would take minutes, so the file is read in aligned chunks and small requests are answered from memory.
    private val chunks = object : LinkedHashMap<Long, ByteArray>(4, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<Long, ByteArray>) = size > 3 }

    private fun fetchChunk(index: Long): ByteArray? {
        val start = index * CHUNK; val length = minOf(CHUNK, file.size - start).toInt()
        if (length <= 0) return null
        val out = java.io.ByteArrayOutputStream(length)
        val began = System.nanoTime(); val deadline = began + READ_TIMEOUT_MS * 1_000_000
        while (out.size() < length) {
            if (closed || overBudget() || System.nanoTime() > deadline) break
            val pending = try { readers.submit<ByteArray?> { media.read(file.fileId, start + out.size(), length - out.size()) } } catch (e: java.util.concurrent.RejectedExecutionException) { break }
            var part: ByteArray? = null
            while (true) {
                try { part = pending.get(200, java.util.concurrent.TimeUnit.MILLISECONDS); break }
                catch (e: java.util.concurrent.TimeoutException) { if (closed || System.nanoTime() > deadline) { pending.cancel(true); break } }
                catch (e: Exception) { break }
            }
            if (part == null || part.isEmpty()) break
            out.write(part); fetched.addAndGet(part.size.toLong())
        }
        val ms = (System.nanoTime() - began) / 1_000_000
        if (ms > 1500 || out.size() < length) runCatching { android.util.Log.d("SubtitleTg", "chunk ${start / 1024} KB: ${out.size() / 1024} of ${length / 1024} KB in $ms ms") }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private fun read(position: Long, length: Int): ByteArray? {
        if (closed || position < 0 || position >= file.size) return null
        val out = java.io.ByteArrayOutputStream(length)
        var at = position
        while (out.size() < length && at < file.size) {
            val index = at / CHUNK
            val chunk = synchronized(chunks) { chunks[index] } ?: run {
                if (overBudget()) break
                val fetchedChunk = fetchChunk(index) ?: break
                synchronized(chunks) { chunks[index] = fetchedChunk }
                fetchedChunk
            }
            val offset = (at - index * CHUNK).toInt()
            if (offset >= chunk.size) break
            val n = minOf(length - out.size(), chunk.size - offset)
            out.write(chunk, offset, n); at += n
            if (chunk.size < CHUNK && index * CHUNK + chunk.size < file.size) break  // a short chunk: stop rather than guess
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }
    private val source by lazy { object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= file.size) return -1
            val bytes = read(position, minOf(size.toLong(), file.size - position).toInt()) ?: return -1
            System.arraycopy(bytes, 0, buffer, offset, bytes.size)
            return bytes.size.takeIf { it > 0 } ?: -1
        }
        override fun getSize(): Long = file.size
        override fun close() {}
    } }
    override fun attach(extractor: MediaExtractor) = extractor.setDataSource(source)
    override fun attach(player: MediaPlayer) = player.setDataSource(source)
    override fun readAt(position: Long, length: Int): ByteArray? = read(position, length)
    override fun close() {
        closed = true
        readers.shutdownNow()
        // Stop Telegram's download of this file now rather than after the reader's idle minute.
        media.release(file.fileId)
    }

    private companion object { const val READ_TIMEOUT_MS = 30_000L; const val CHUNK = 1024L * 1024 }
}

/** Only sparse PCM is decoded; raw audio never leaves memory. The energy/ZCR gate is intentionally
 * conservative. Correlation must independently establish consistency across the film. */
object LocalSubtitleAnalysis {
    private fun trace(message: String) { runCatching { android.util.Log.d("SubtitleAudio", message) } }
    fun hash(input: SubtitleMediaInput): String? {
        val size = input.size
        if (size < 131072) return null
        return runCatching {
            var total = size
            for (position in listOf(0L, size - 65536)) {
                val bytes = ByteArray(65536); var read = 0
                while (read < bytes.size) {
                    val part = input.readAt(position + read, bytes.size - read) ?: error("short read")
                    System.arraycopy(part, 0, bytes, read, part.size); read += part.size
                }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 8) total += buffer.long
            }
            java.lang.Long.toUnsignedString(total, 16).padStart(16, '0')
        }.getOrNull()
    }
    /** The sound format of a video whose audio this phone has no decoder for (Dolby Digital, DTS...), else null. */
    fun unsupportedAudio(input: SubtitleMediaInput): String? {
        val extractor = MediaExtractor()
        return try {
            input.attach(extractor)
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val audio = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.filter { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            audio.forEach { trace("audio track mime=${it.getString(MediaFormat.KEY_MIME)} channels=${runCatching { it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull()} rate=${runCatching { it.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()} decoder=${codecs.findDecoderForFormat(it)}") }
            trace("tracks=${extractor.trackCount} audio=${audio.size} size=${input.size}")
            // The file reader lists only audio this phone can decode: a film whose sound (Dolby Digital, DTS...) is missing
            // from the list has sound the phone can neither play nor analyse, not a film without sound.
            if (audio.isEmpty()) "an unsupported format (usually Dolby Digital or DTS)"
            else if (audio.any { codecs.findDecoderForFormat(it) != null }) null
            else when (val mime = audio.first().getString(MediaFormat.KEY_MIME).orEmpty()) {
                "audio/ac3" -> "Dolby Digital"
                "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus"
                "audio/true-hd" -> "Dolby TrueHD"
                "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
                else -> mime.removePrefix("audio/")
            }
        } catch (e: Exception) { trace("audio check failed: ${e::class.simpleName}: ${e.message}"); null } finally { extractor.release() }
    }
    /**
     * Android plays the first audio track. With several (say Dolby first, AAC second) pick one this phone can decode.
     * MediaPlayer does not report track formats, so they come from the file's own track list: the n-th audio track
     * there is the n-th audio track of the player. Blocking (it reads the file header); call off the UI thread.
     */
    fun selectPlayableAudio(player: MediaPlayer, input: SubtitleMediaInput) {
        val extractor = MediaExtractor()
        try {
            input.attach(extractor)
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }.filter { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            val playerAudio = player.trackInfo.withIndex().filter { it.value.trackType == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO }.map { it.index }
            val supported = formats.map { codecs.findDecoderForFormat(it) != null }
            runCatching { android.util.Log.d("SubtitleAudio", "audio tracks: ${formats.map { it.getString(MediaFormat.KEY_MIME) }} playable=$supported") }
            if (formats.size != playerAudio.size || supported.isEmpty()) return
            val current = runCatching { player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) }.getOrDefault(-1)
            val currentPosition = playerAudio.indexOf(current)
            if (currentPosition >= 0 && supported[currentPosition]) return
            supported.indexOfFirst { it }.takeIf { it >= 0 }?.let { player.selectTrack(playerAudio[it]); runCatching { android.util.Log.d("SubtitleAudio", "selected audio track ${playerAudio[it]}") } }
        } catch (e: Exception) { runCatching { android.util.Log.d("SubtitleAudio", "track choice failed: ${e.message}") } } finally { extractor.release() }
    }
    fun probe(input: SubtitleMediaInput): SubtitleTechnical {
        val extractor = MediaExtractor()
        try {
            input.attach(extractor)
            var duration: Double? = null; var fps: Double? = null; var codec: String? = null; var resolution: String? = null
            val audio = mutableListOf<String>(); val subtitles = mutableListOf<String>()
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (f.containsKey(MediaFormat.KEY_DURATION)) duration = maxOf(duration ?: 0.0, f.getLong(MediaFormat.KEY_DURATION) / 1e6)
                val lang = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) ?: "und" else "und"
                when {
                    mime.startsWith("audio/") -> audio += lang
                    mime.startsWith("video/") -> { codec = mime; if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = f.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble(); if (f.containsKey(MediaFormat.KEY_WIDTH) && f.containsKey(MediaFormat.KEY_HEIGHT)) resolution = "${f.getInteger(MediaFormat.KEY_WIDTH)}×${f.getInteger(MediaFormat.KEY_HEIGHT)}" }
                    mime.startsWith("text/") || mime.contains("subtitle") || mime.contains("subrip") || mime.contains("cea-") -> subtitles += lang
                }
            }
            return SubtitleTechnical(hash(input), input.size.takeIf { it > 0 }, duration, fps, codec, resolution, audio, subtitles)
        } finally { extractor.release() }
    }
    /** Six zones across the film; inside a zone a quiet stretch (action, music, no dialogue) is replaced by a nearby
     * one, because a window without speech can neither confirm nor contradict a subtitle. */
    suspend fun fingerprint(input: SubtitleMediaInput, duration: Double, onZone: (done: Int, total: Int) -> Unit = { _, _ -> }): SubtitleFingerprint? {
        if (duration < 120) return null
        val windows = mutableListOf<SubtitleActivityWindow>()
        // Over the network each section means fetching from a different part of the file, so give up sooner.
        val budgetEnd = android.os.SystemClock.elapsedRealtime() + if (input.remote) 45_000 else 120_000
        val zones = listOf(.03, .15, .35, .55, .75, .90)
        for ((index, zone) in zones.withIndex()) {
            onZone(index, zones.size)
            var best: SubtitleActivityWindow? = null
            for (shift in if (input.remote) listOf(0.0, .06) else listOf(0.0, .06, -.06, .11)) {
                currentCoroutineContext().ensureActive()
                if (android.os.SystemClock.elapsedRealtime() > budgetEnd) break
                // Over the network a shorter section means fewer bytes fetched (100+ bins are still plenty to match).
                val seconds = if (input.remote) 20 else 30
                val start = (duration * (zone + shift).coerceIn(.02, .93)).coerceAtMost(duration - seconds)
                input.startSection()
                val window = (try { decodeWindow(input, start, seconds) } catch (e: kotlin.coroutines.cancellation.CancellationException) { throw e } catch (e: Exception) { trace("window at ${start.toInt()}s failed: ${e::class.simpleName}: ${e.message}"); null }) ?: continue
                if (speechFraction(window) > speechFraction(best)) best = window
                if (speechFraction(window) in INFORMATIVE) break
            }
            best?.takeIf { speechFraction(it) >= .08 }?.let { windows += it }
        }
        // Two informative sections are enough for the server to suggest a shift (never to verify); a film with little dialogue often has no more.
        return windows.takeIf { it.size >= 2 }?.let { SubtitleFingerprint(windows = it) }
    }
    private val INFORMATIVE = .12..0.85
    private fun speechFraction(w: SubtitleActivityWindow?): Double = w?.let { it.bits.count { c -> c == '1' }.toDouble() / it.bits.length } ?: -1.0
    private suspend fun decodeWindow(input: SubtitleMediaInput, start: Double, seconds: Int): SubtitleActivityWindow? {
        val extractor = MediaExtractor(); var codec: MediaCodec? = null
        try {
            input.attach(extractor)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = extractor.getTrackFormat(track); extractor.selectTrack(track)
            extractor.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); codec = decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val bins = seconds * 10; val energy = DoubleArray(bins); val crossings = IntArray(bins); val counts = IntArray(bins)
            var inputDone = false; var outputDone = false; val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 12000
            while (!outputDone && android.os.SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index)!!; val size = extractor.readSampleData(input, 0); val time = extractor.sampleTime
                        if (size < 0 || time > (start + seconds + .5) * 1e6) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(index, 0, size, time, 0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 1000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) { trace("decoder output encoding ${output.getInteger(MediaFormat.KEY_PCM_ENCODING)} is not 16-bit PCM"); return null }
                } else if (index >= 0) {
                    val output = decoder.getOutputBuffer(index)!!; output.position(info.offset); output.limit(info.offset + info.size); output.order(ByteOrder.LITTLE_ENDIAN)
                    var frame = 0; var previous = 0.0
                    while (output.remaining() >= channels * 2) {
                        var sample = 0.0; repeat(channels) { sample += output.short / 32768.0 }; sample /= channels
                        val time = info.presentationTimeUs / 1e6 + frame.toDouble() / rate
                        val bin = ((time - start) * 10).toInt()
                        if (time >= start && bin in energy.indices) { energy[bin] += sample * sample; counts[bin]++; if ((sample > 0) != (previous > 0)) crossings[bin]++ }
                        previous = sample; frame++
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs > (start + seconds) * 1e6
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            if (!outputDone || counts.count { it > 0 } < bins - 20) { trace("window at ${start.toInt()}s unusable: outputDone=$outputDone binsWithAudio=${counts.count { it > 0 }}/$bins"); return null }
            val rms = energy.mapIndexed { i, value -> sqrt(value / counts[i].coerceAtLeast(1)) }
            val floor = rms.sorted()[rms.size / 5]; val threshold = maxOf(.008, floor * 2.5)
            val bits = rms.mapIndexed { i, value -> val zcr = crossings[i].toDouble() / counts[i].coerceAtLeast(1); if (value > threshold && zcr in .015..0.35) '1' else '0' }.joinToString("")
            return SubtitleActivityWindow(start, bits = bits)
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}

@Serializable data class SavedSubtitle(val language: String)
@Serializable data class SavedSubtitleList(val subtitles: List<SavedSubtitle> = emptyList())

/** A title whose bytes can't be read for subtitle matching; the message is shown to the person as is. */
class VideoUnavailable(message: String) : Exception(message)
