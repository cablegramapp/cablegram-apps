package app.cablegram.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class CommandRecord(
    val command: TvCommand,
    val accountId: String,
    val status: String = "executing",
    val reason: String? = null,
    val confirmed: Boolean = false,
    val recordedAt: Long,
)

/** Durable execution boundary shared by socket and polling delivery.
 * Persist before dispatch: after a process crash an interrupted action is
 * rejected as uncertain, never blindly executed a second time. */
internal class CommandJournal(
    initial: String?,
    private val save: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var lastSaved: String? = initial
    private val records = (initial?.let { json.decodeFromString<List<CommandRecord>>(it) } ?: emptyList())
        .associateByTo(linkedMapOf()) { it.command.id }

    init {
        records.replaceAll { _, record ->
            if (record.status == "executing") record.copy(status = "reject", reason = "execution_interrupted") else record
        }
        persist()
    }

    fun get(id: String): CommandRecord? = records[id]
    fun pending(accountId: String): List<CommandRecord> = records.values.filter {
        it.accountId == accountId && it.status != "executing" && !it.confirmed
    }
    fun executing(): List<CommandRecord> = records.values.filter { it.status == "executing" }

    fun begin(command: TvCommand, accountId: String): Boolean {
        if (records.containsKey(command.id)) return false
        records[command.id] = CommandRecord(command, accountId, recordedAt = now())
        persist()
        return true
    }

    fun finish(id: String, reason: String? = null) {
        val record = records[id] ?: return
        if (record.status != "executing") return
        records[id] = record.copy(status = if (reason == null) "complete" else "reject", reason = reason)
        persist()
    }

    fun confirm(id: String, accountId: String) {
        val record = records[id] ?: return
        if (record.accountId != accountId || record.status == "executing") return
        records[id] = record.copy(confirmed = true)
        persist()
    }

    /** An account was revoked or removed from this TV (CAB-37): nothing of its commands stays on disk. */
    fun forgetAccount(accountId: String) {
        records.entries.removeAll { (_, r) -> r.accountId == accountId }
        persist()
    }

    fun forgetAll() {
        records.clear()
        persist()
    }

    fun prune() {
        // No count-based eviction: every unexpired ID remains protected even
        // during bursts. Keep results for a day beyond expiry for receipt retries.
        val cutoff = now() - 86_400_000L
        records.entries.removeAll { (_, r) ->
            r.status != "executing" && (r.command.expiresAtMs ?: r.recordedAt + 300_000L) < cutoff
        }
        persist()
    }

    private fun persist() {
        val encoded = json.encodeToString(records.values.toList())
        if (encoded != lastSaved) {
            save(encoded)
            lastSaved = encoded
        }
    }
}
