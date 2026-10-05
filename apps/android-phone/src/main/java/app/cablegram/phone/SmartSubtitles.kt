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
@Serializable data class SubtitleSearchRequest(val id: String, val identity: String, val language: String? = null, val technical: SubtitleTechnical? = null, val fingerprint: SubtitleFingerprint? = null)

/** A video the phone can analyse and preview: a local file, or a Telegram title read in small windows. */
interface SubtitleMediaInput : AutoCloseable {
    val size: Long
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

/** Reads a Telegram title through the phone's own session; nothing is stored beyond the reader's small window. */
class TelegramMediaInput(private val media: TelegramMedia, private val file: TelegramFileRef) : SubtitleMediaInput {
    override val size: Long get() = file.size
    private val source = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= file.size) return -1
            val bytes = media.read(file.fileId, position, minOf(size.toLong(), file.size - position).toInt()) ?: return -1
            System.arraycopy(bytes, 0, buffer, offset, bytes.size)
            return bytes.size.takeIf { it > 0 } ?: -1
        }
        override fun getSize(): Long = file.size
        override fun close() {}
    }
    override fun attach(extractor: MediaExtractor) = extractor.setDataSource(source)
    override fun attach(player: MediaPlayer) = player.setDataSource(source)
    override fun readAt(position: Long, length: Int): ByteArray? = media.read(file.fileId, position, length)
    override fun close() {}
}

/** Only sparse PCM is decoded; raw audio never leaves memory. The energy/ZCR gate is intentionally
 * conservative. Correlation must independently establish consistency across the film. */
object LocalSubtitleAnalysis {
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
    suspend fun fingerprint(input: SubtitleMediaInput, duration: Double): SubtitleFingerprint? {
        if (duration < 120) return null
        val windows = mutableListOf<SubtitleActivityWindow>()
        val budgetEnd = android.os.SystemClock.elapsedRealtime() + 120_000
        for (zone in listOf(.03, .15, .35, .55, .75, .90)) {
            var best: SubtitleActivityWindow? = null
            for (shift in listOf(0.0, .06, -.06, .11)) {
                currentCoroutineContext().ensureActive()
                if (android.os.SystemClock.elapsedRealtime() > budgetEnd) break
                val start = (duration * (zone + shift).coerceIn(.02, .93)).coerceAtMost(duration - 30)
                val window = decodeWindow(input, start) ?: continue
                if (speechFraction(window) > speechFraction(best)) best = window
                if (speechFraction(window) in INFORMATIVE) break
            }
            best?.takeIf { speechFraction(it) >= .08 }?.let { windows += it }
        }
        return windows.takeIf { it.size >= 3 }?.let { SubtitleFingerprint(windows = it) }
    }
    private val INFORMATIVE = .12..0.85
    private fun speechFraction(w: SubtitleActivityWindow?): Double = w?.let { it.bits.count { c -> c == '1' }.toDouble() / it.bits.length } ?: -1.0
    private suspend fun decodeWindow(input: SubtitleMediaInput, start: Double): SubtitleActivityWindow? {
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
            val energy = DoubleArray(300); val crossings = IntArray(300); val counts = IntArray(300)
            var inputDone = false; var outputDone = false; val info = MediaCodec.BufferInfo()
            val deadline = android.os.SystemClock.elapsedRealtime() + 12000
            while (!outputDone && android.os.SystemClock.elapsedRealtime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(1000)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index)!!; val size = extractor.readSampleData(input, 0); val time = extractor.sampleTime
                        if (size < 0 || time > (start + 30.5) * 1e6) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(index, 0, size, time, 0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 1000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = decoder.outputFormat
                    rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING) && output.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) return null
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
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || info.presentationTimeUs > (start + 30) * 1e6
                    decoder.releaseOutputBuffer(index, false)
                }
            }
            if (!outputDone || counts.count { it > 0 } < 280) return null
            val rms = energy.mapIndexed { i, value -> sqrt(value / counts[i].coerceAtLeast(1)) }
            val floor = rms.sorted()[rms.size / 5]; val threshold = maxOf(.008, floor * 2.5)
            val bits = rms.mapIndexed { i, value -> val zcr = crossings[i].toDouble() / counts[i].coerceAtLeast(1); if (value > threshold && zcr in .015..0.35) '1' else '0' }.joinToString("")
            return SubtitleActivityWindow(start, bits = bits)
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }
}

@Serializable data class SavedSubtitle(val language: String)
@Serializable data class SavedSubtitleList(val subtitles: List<SavedSubtitle> = emptyList())
