package app.cablegram.data

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*

class CommandJournalTest {
    @Test fun `retains more than 200 unexpired execution results across restart`() {
        var disk: String? = null
        val journal = CommandJournal(disk, { disk = it }, { 1000 })
        repeat(250) {
            assertTrue(journal.begin(TvCommand("$it", "seek", expiresAtMs = 30_000), "account"))
            journal.finish("$it")
        }
        val restored = CommandJournal(disk, {}, { 2000 })
        assertEquals(250, restored.pending("account").size)
        repeat(250) { assertFalse(restored.begin(TvCommand("$it", "seek", expiresAtMs = 30_000), "account")) }
    }

    @Test fun `interrupted execution is rejected after restart and never dispatched twice`() {
        var disk: String? = null
        val journal = CommandJournal(null, { disk = it }, { 1000 })
        journal.begin(TvCommand("seek", "seek", expiresAtMs = 30_000), "account")
        val restored = CommandJournal(disk, {}, { 2000 })
        assertFalse(restored.begin(TvCommand("seek", "seek", expiresAtMs = 30_000), "account"))
        assertEquals("reject", restored.get("seek")?.status)
        assertEquals("execution_interrupted", restored.get("seek")?.reason)
    }

    @Test fun `only a receipt for the matching account clears pending results`() {
        val journal = CommandJournal(null, {}, { 1000 })
        journal.begin(TvCommand("x", "pause", expiresAtMs = 30_000), "a")
        journal.finish("x")
        journal.confirm("x", "b")
        assertEquals(1, journal.pending("a").size)
        journal.confirm("x", "a")
        assertTrue(journal.pending("a").isEmpty())
        assertNotNull(journal.get("x"))
    }

    @Test fun `terminal rejection cannot become success and expiry governs retention`() {
        var clock = 1000L
        val journal = CommandJournal(null, {}, { clock })
        journal.begin(TvCommand("x", "text_input", expiresAtMs = 30_000), "a")
        journal.finish("x", "unsupported_command")
        journal.finish("x")
        assertEquals("reject", journal.get("x")?.status)
        clock = 50_000
        journal.prune()
        assertNotNull(journal.get("x"))
        clock = 86_500_000
        journal.prune()
        assertNull(journal.get("x"))
    }

    @Test fun `unsupported and malformed actions cannot receive execution success`() {
        fun command(name: String, payload: Map<String, JsonElement> = emptyMap()) =
            TvCommand("x", name, payload, expiresAtMs = 30_000)
        assertEquals("unsupported_command", command("text_input").validationError(1000))
        assertEquals("invalid_payload", command("volume").validationError(1000))
        assertEquals("invalid_payload", command("move", mapOf("direction" to JsonPrimitive("diagonal"))).validationError(1000))
        assertEquals("expired", command("pause").validationError(30_000))
        assertNull(command("seek", mapOf("seconds" to JsonPrimitive(15))).validationError(1000))
    }
}
