package app.cablegram.phone

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A transient attachment: neither its document URI nor its filename enters household storage. */
internal data class LocalSubtitleFile(val reference: String, val cues: List<SubtitleCue>) {
    fun selected(source: SubtitleSourceContext, language: String): SelectedSubtitleTrack {
        require(Regex("^[a-z]{2}$").matches(language))
        return SelectedSubtitleTrack(source.sourceId, source.identity, language, "local", reference,
            cues, offset = 0.0, scale = 1.0, forced = false, confidence = "Unverified")
    }

    companion object {
        const val MAX_BYTES = 2_000_000

        fun read(stream: InputStream, checkCancelled: () -> Unit = {}): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                checkCancelled()
                val count = stream.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - out.size()))
                if (count < 0) break
                if (out.size() + count > MAX_BYTES) throw LocalSubtitleError("local_subtitle_too_large")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }

        fun parse(name: String, bytes: ByteArray, language: String): LocalSubtitleFile {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in setOf("srt", "vtt")) throw LocalSubtitleError("local_subtitle_format")
            if (bytes.size > MAX_BYTES) throw LocalSubtitleError("local_subtitle_too_large")
            // Local attachment accepts a single text document, never an archive or provider response.
            if (bytes.take(2) == listOf(0x50.toByte(), 0x4b.toByte())) throw LocalSubtitleError("local_subtitle_format")
            val text = LocalSubtitleTimeline.decode(bytes, language, null, null)
                .removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
            if (extension == "vtt" && !Regex("^WEBVTT(?:[ \\t][^\\n]*)?(?:\\n|$)").containsMatchIn(text)) {
                throw LocalSubtitleError("invalid_subtitle")
            }
            if (Regex("(?im)^\\s*\\[Script Info]|^Dialogue:").containsMatchIn(text)) throw LocalSubtitleError("local_subtitle_format")
            // WebVTT comments and style/region blocks are metadata, not spoken cues.
            val content = if (extension == "vtt") text.split(Regex("\n[ \\t]*\n"))
                .filterNot { Regex("^(NOTE(?:[ \\t]|$)|STYLE$|REGION$)").containsMatchIn(it.trimStart().lineSequence().first()) }
                .joinToString("\n\n") else text
            val cues = LocalSubtitleTimeline.parse(content)
            // A stray "<" or ">" (">> NARRATOR:", "<3") is ordinary caption text; the server shows it safely.
            if (cues.any { cue -> cue.start < 0 || cue.text.any { it.code < 32 && it !in "\t\n\r" } }) {
                throw LocalSubtitleError("invalid_subtitle")
            }
            if (Json.encodeToString(cues).toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw LocalSubtitleError("local_subtitle_too_large")
            val reference = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return LocalSubtitleFile(reference, cues)
        }
    }
}
