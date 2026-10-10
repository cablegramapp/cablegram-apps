package app.cablegram.phone

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalSubtitleFileTest {
    private val srt = "1\r\n00:00:01,500 --> 00:00:03,000\r\nHello <i>world</i>\r\nSecond line\r\n"
    private fun parse(name: String, text: String, language: String = "en") = LocalSubtitleFile.parse(name, text.toByteArray(), language)
    private fun rejected(code: String, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: LocalSubtitleError) { assertEquals(code, e.code) }
    }

    @Test fun srtBecomesAnExplicitUnverifiedTrackWithoutFilePath() {
        val file = parse("My private filename.SRT", srt)
        assertEquals(listOf(SubtitleCue(1.5, 3.0, "Hello world\nSecond line")), file.cues)
        val context = SubtitleSourceContext("source-id", "source-version", "film.mp4", "Film")
        val selected = file.selected(context, "en")
        assertEquals("local", selected.provider); assertEquals("source-version", selected.identity)
        assertEquals("Unverified", selected.confidence); assertFalse(selected.automatic)
        assertEquals(0.0, selected.offset, 0.0); assertEquals(1.0, selected.scale, 0.0)
        assertTrue(Regex("^[a-f0-9]{64}$").matches(selected.providerRef))
        assertEquals(file.reference, parse("renamed.srt", srt).reference)
        val payload = Json.encodeToString(selected)
        assertFalse(payload.contains("private filename")); assertFalse(payload.contains("content://"))
    }

    @Test fun vttKeepsUnicodeAndIgnoresCommentsAndStyles() {
        val file = parse("dialogue.vtt", "\uFEFFWEBVTT\n\nNOTE\n00:00:00.000 --> 00:00:01.000\nDo not display\n\nSTYLE\n::cue { color: lime; }\n\ncue-id\n00:01.250 --> 00:03.500 align:start\nسلام دنیا\n日本語\n")
        assertEquals(listOf(SubtitleCue(1.25, 3.5, "سلام دنیا\n日本語")), file.cues)
    }

    @Test fun utf16AndLegacyPersianFilesAreDecoded() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nسلام\n"
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals("سلام", LocalSubtitleFile.parse("fa.srt", utf16, "fa").cues.single().text)
        assertEquals("سلام", LocalSubtitleFile.parse("fa.srt", text.toByteArray(charset("windows-1256")), "fa").cues.single().text)
    }

    @Test fun wrongFormatEmptyMalformedAndBinaryDocumentsAreRefused() {
        rejected("local_subtitle_format") { parse("film.mp4", srt) }
        rejected("local_subtitle_format") { parse("file.srt", "PKnot-a-subtitle") }
        rejected("local_subtitle_format") { parse("file.srt", "[Script Info]\nDialogue: hello") }
        for (text in listOf("", "not subtitles", "1\n00:00:03,000 --> 00:00:01,000\nBad\n", "1\n00:00:01,000 --> 00:00:02,000\nBad\u0000\n")) {
            rejected("invalid_subtitle") { parse("file.srt", text) }
        }
        rejected("invalid_subtitle") { parse("file.vtt", srt) }
    }

    @Test fun captionMarksWithAngleBracketsAreKeptAndControlCharactersDropped() {
        val file = parse("cc.srt", "1\n00:00:01,000 --> 00:00:02,000\n>> NARRATOR: I <3 it\n\n2\n00:00:03,000 --> 00:00:04,000\nform\u000cfeed\n")
        assertEquals(listOf(">> NARRATOR: I <3 it", "formfeed"), file.cues.map { it.text })
    }

    @Test fun unknownLengthStreamIsBoundedAndCancellationIsObserved() {
        var consumed = 0
        val infinite = object : InputStream() {
            override fun read(): Int { consumed++; return 65 }
            override fun read(b: ByteArray, off: Int, len: Int): Int { consumed += len; b.fill(65, off, off + len); return len }
        }
        rejected("local_subtitle_too_large") { LocalSubtitleFile.read(infinite) }
        assertEquals(LocalSubtitleFile.MAX_BYTES + 1, consumed)
        assertArrayEquals(srt.toByteArray(), LocalSubtitleFile.read(ByteArrayInputStream(srt.toByteArray())))
        try { LocalSubtitleFile.read(infinite) { throw java.util.concurrent.CancellationException() }; fail("Expected cancellation") }
        catch (_: java.util.concurrent.CancellationException) {}
    }

    @Test fun serializedCueLimitIsCheckedBeforeUpload() {
        // Valid source text can expand past the wire limit after JSON escaping and cue metadata.
        val body = "\\".repeat(3000)
        val text = (1..400).joinToString("\n\n") { "$it\n00:00:01,000 --> 00:00:02,000\n$body" }
        assertTrue(text.toByteArray().size < LocalSubtitleFile.MAX_BYTES)
        rejected("local_subtitle_too_large") { parse("expanded.srt", text) }
    }
}
