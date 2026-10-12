package app.cablegram.phone

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.CRC32
import kotlin.math.*

class LocalSubtitleError(val code: String, val retryAfter: Int? = null) : Exception(code)

/** All archive work is in memory. Only the uniquely selected episode is inflated. */
object LocalSubtitleTimeline {
    private const val LIMIT = 2_000_000
    private fun invalid(): Nothing = throw LocalSubtitleError("invalid_subtitle")
    fun episode(name: String): Pair<Int, Int>? {
        val m = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,3})(?!\\d)|(?<![a-z0-9])(\\d{1,2})x(\\d{2,3})(?!\\d)").find(name.substringAfterLast('/')) ?: return null
        return if (m.groupValues[1].isNotEmpty()) m.groupValues[1].toInt() to m.groupValues[2].toInt() else m.groupValues[3].toInt() to m.groupValues[4].toInt()
    }
    fun pickEpisode(names: List<String>, season: Int?, episode: Int?): Int? {
        if (episode == null) return null
        val s = season?.let { "0*$it" } ?: "\\d{1,2}"
        val tiers = listOf("s$s[ ._-]*e0*$episode(?!\\d)", "(?<!\\d)${s}x0*$episode(?!\\d)", "(?<![a-z0-9])e(?:p|pisode)?[ ._-]*0*$episode(?!\\d)", "(?<![a-z0-9])0*$episode(?![a-z0-9])")
        for (tier in tiers) {
            val found = names.indices.filter { i ->
                val n = names[i].substringAfterLast('/').substringAfterLast('\\').lowercase().replace(Regex("\\.[a-z0-9]{2,4}$"), "")
                val named = episode(n)
                (season == null || named == null || named.first == season) && Regex(tier).containsMatchIn(n)
            }
            if (found.size == 1) return found.single()
            if (found.size > 1) return null
        }
        return null
    }
    fun decode(bytes: ByteArray, language: String, season: Int?, episode: Int?): String {
        if (bytes.isEmpty() || bytes.size > LIMIT) invalid()
        var content = bytes
        if (bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()) {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun u16(at: Int): Int { if (at < 0 || at + 2 > bytes.size) invalid(); return b.getShort(at).toInt() and 65535 }
            fun u32(at: Int): Long { if (at < 0 || at + 4 > bytes.size) invalid(); return b.getInt(at).toLong() and 0xffffffffL }
            var end = bytes.size - 22
            while (end >= maxOf(0, bytes.size - 65557) && u32(end) != 0x06054b50L) end--
            if (end < 0 || end < bytes.size - 65557 || u16(end + 4) != 0 || u16(end + 6) != 0) invalid()
            val count = u16(end + 10); if (count > 400) invalid()
            var at = u32(end + 16).toInt()
            data class Entry(val name: String, val flags: Int, val method: Int, val size: Long, val raw: Long, val local: Long, val crc: Long)
            val entries = mutableListOf<Entry>()
            repeat(count) {
                if (u32(at) != 0x02014b50L) invalid()
                val n = u16(at + 28); val next = at.toLong() + 46 + n + u16(at + 30) + u16(at + 32)
                if (next > end || next < at) invalid()
                val name = bytes.copyOfRange(at + 46, at + 46 + n).toString(Charsets.UTF_8)
                if (name.startsWith('/') || name.split('/', '\\').any { it == ".." } || name.contains('\u0000')) invalid()
                if (Regex("(?i)\\.(srt|vtt|ass|ssa)$").containsMatchIn(name)) entries += Entry(name, u16(at + 8), u16(at + 10), u32(at + 20), u32(at + 24), u32(at + 42), u32(at + 16))
                at = next.toInt()
            }
            val chosen = if (entries.size == 1) {
                val named = episode(entries.single().name)
                if (episode != null && named != null && (named.second != episode || season != null && named.first != season)) throw LocalSubtitleError("ambiguous_subtitle_archive")
                entries.single()
            } else entries.getOrNull(pickEpisode(entries.map { it.name }, season, episode) ?: -1) ?: throw LocalSubtitleError("ambiguous_subtitle_archive")
            if (chosen.flags and 1 != 0 || chosen.size > LIMIT || chosen.raw > LIMIT || chosen.local > bytes.size - 30) invalid()
            val local = chosen.local.toInt(); if (u32(local) != 0x04034b50L) invalid()
            val start = local.toLong() + 30 + u16(local + 26) + u16(local + 28)
            if (start + chosen.size > bytes.size || start < 0) invalid()
            val data = bytes.copyOfRange(start.toInt(), (start + chosen.size).toInt())
            content = when (chosen.method) {
                0 -> data
                8 -> {
                    val inflater = Inflater(true)
                    try {
                        inflater.setInput(data); val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (!inflater.finished()) { val read = inflater.inflate(buffer); if (read == 0 || out.size() + read > LIMIT) invalid(); out.write(buffer, 0, read) }
                        out.toByteArray()
                    } finally { inflater.end() }
                }
                else -> invalid()
            }
            if (content.size.toLong() != chosen.raw || CRC32().apply { update(content) }.value != chosen.crc) invalid()
        }
        val utf = when { content.size >= 2 && content[0] == (-1).toByte() && content[1] == (-2).toByte() -> "UTF-16LE"
            content.size >= 2 && content[0] == (-2).toByte() && content[1] == (-1).toByte() -> "UTF-16BE"; else -> "UTF-8" }
        return try { java.nio.charset.Charset.forName(utf).newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString() }
        catch (_: Exception) { content.toString(java.nio.charset.Charset.forName(when(language) { "fa", "ar", "ur", "ku" -> "windows-1256"; "ru", "uk", "bg", "sr", "mk", "be" -> "windows-1251"; "el" -> "windows-1253"; "tr", "az" -> "windows-1254"; "he" -> "windows-1255"; "pl", "cs", "sk", "hu", "ro", "hr", "sl", "bs" -> "windows-1250"; "lt", "lv", "et" -> "windows-1257"; "vi" -> "windows-1258"; "th" -> "windows-874"; else -> "windows-1252" })) }
    }
    private fun time(value: String, ass: Boolean = false): Double? {
        val m = Regex(if (ass) "^(\\d+):(\\d{2}):(\\d{2})[.](\\d{1,3})$" else "^(?:(\\d{1,3}):)?(\\d{2}):(\\d{2})[,.](\\d{3})$").matchEntire(value.trim()) ?: return null
        if (m.groupValues[2].toInt() > 59 || m.groupValues[3].toInt() > 59) return null
        return (m.groupValues[1].toDoubleOrNull() ?: 0.0) * 3600 + m.groupValues[2].toDouble() * 60 + m.groupValues[3].toDouble() + m.groupValues[4].padEnd(3, '0').toDouble() / 1000
    }
    fun parse(text: String): List<SubtitleCue> {
        if (text.toByteArray().size > LIMIT || text.contains('\u0000')) invalid()
        val cues = mutableListOf<SubtitleCue>(); var skipped = 0
        fun add(start: Double?, end: Double?, body: String) {
            val clean = body.replace(Regex("<[^>]*>"), "").trim()
            if (start == null || end == null || end <= start || end > 86400 || end - start > 120 || clean.isEmpty() || clean.length > 4000) { skipped++; return }
            cues += SubtitleCue(start, end, clean); if (cues.size > 20000) invalid()
        }
        val normalized = text.removePrefix("\uFEFF").replace("\r", "")
        if (Regex("(?im)^\\s*\\[Script Info]|^Dialogue:").containsMatchIn(normalized)) {
            var events = false; var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
            for (raw in normalized.lines()) {
                val line = raw.trim()
                if (line.startsWith('[')) { events = line.equals("[events]", true); continue }
                if (!events) continue
                if (line.startsWith("Format:", true)) { fields = line.substringAfter(':').split(',').map { it.trim().lowercase() }; continue }
                if (!line.startsWith("Dialogue:", true) || fields.lastOrNull() != "text") continue
                val parts = line.substringAfter(':').trimStart().split(',', limit = fields.size)
                fun field(name: String) = parts.getOrNull(fields.indexOf(name)) ?: ""
                val source = field("text"); if (Regex("\\{[^}]*\\\\p[1-9]").containsMatchIn(source)) continue
                add(time(field("start"), true), time(field("end"), true), source.replace(Regex("\\{[^}]*}"), "").replace(Regex("\\\\[Nn]"), "\n").replace("\\h", " "))
            }
        } else for (block in normalized.split(Regex("\n\\s*\n"))) {
            val lines = block.lines(); val i = lines.indexOfFirst { "-->" in it }; if (i < 0) continue
            val parts = lines[i].trim().split(Regex("\\s*-->\\s*"))
            if (parts.size != 2) { skipped++; continue }
            add(time(parts[0]), time(parts[1].substringBefore(' ')), lines.drop(i + 1).joinToString("\n"))
        }
        if (cues.isEmpty() || skipped > cues.size) invalid()
        return cues.sortedBy { it.start }
    }
}
