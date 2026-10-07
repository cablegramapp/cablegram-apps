package app.cablegram.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal interface CommandConnection {
    fun send(text: String): Boolean
    fun close()
}

internal interface CommandTransport {
    fun connect(token: String, onReady: () -> Unit, onCommand: (TvCommand) -> Unit,
                onReceipt: (String) -> Unit, onClosed: () -> Unit): CommandConnection
    suspend fun poll(token: String): List<TvCommand>
    suspend fun result(token: String, record: CommandRecord)
}

/** All state belongs to scope's dispatcher (the main dispatcher in the app). */
internal class CommandDelivery(
    private val scope: CoroutineScope,
    private val transport: CommandTransport,
    private val journal: CommandJournal,
    private val onCommand: (TvCommand, AccountCredential) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 250,
) {
    private class Session(val account: AccountCredential) {
        var connection: CommandConnection? = null
        var ready = false
        var generation = 0
        var retryAt = 0L
        var backoff = 1_000L
        var connectedAt = 0L
        var polledAt = Long.MIN_VALUE / 2
        var resultAt = Long.MIN_VALUE / 2
        val sentAt = mutableMapOf<String, Long>()
    }
    private val sessions = mutableMapOf<String, Session>()
    private var job: Job? = null

    /** Wake-triggered poll; receive() still journals and validates every server-delivered command. */
    fun pollNow() {
        sessions.values.toList().forEach { session ->
            scope.launch {
                session.polledAt = now()
                try { transport.poll(session.account.token).forEach { receive(it, session) } }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Normal delivery keeps reconnecting and polling. */ }
            }
        }
    }

    fun setAccounts(accounts: List<AccountCredential>) {
        val wanted = accounts.associateBy { it.sessionId }
        sessions.keys.toList().forEach { id ->
            if (wanted[id] != sessions[id]?.account) {
                sessions.remove(id)?.let { it.generation++; it.connection?.close() }
            }
        }
        accounts.forEach { sessions.getOrPut(it.sessionId) { Session(it) } }
        if (job?.isActive != true && sessions.isNotEmpty()) {
            job = scope.launch {
                while (isActive) {
                    for (session in sessions.values.toList()) service(session)
                    delay(tickMs)
                }
            }
        }
    }

    private suspend fun service(s: Session) {
        if (sessions[s.account.sessionId] !== s) return
        if (s.connection == null && now() >= s.retryAt) {
            try { connect(s) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                s.retryAt = now() + s.backoff
                s.backoff = (s.backoff * 2).coerceAtMost(30_000)
            }
        }
        if (!s.ready && s.connection != null && now() - s.connectedAt > 10_000) {
            s.generation++
            s.connection?.close()
            s.connection = null
            s.retryAt = now() + s.backoff
        }
        if (now() - s.resultAt >= 1_000) {
            s.resultAt = now()
            for (record in journal.pending(s.account.sessionId)) {
                val sentAt = s.sentAt[record.command.id]
                if (sentAt != null && now() - sentAt < 3_000) continue
                val message = buildJsonObject {
                    put("type", record.status); put("id", record.command.id)
                    record.reason?.let { put("reason", it) }
                }.toString()
                // A queued send is not a receipt. If the receipt is missing,
                // retry over HTTP even when the socket still looks healthy.
                if (sentAt == null && s.ready && s.connection?.send(message) == true) {
                    s.sentAt[record.command.id] = now()
                } else {
                    try {
                        transport.result(s.account.token, record)
                        journal.confirm(record.command.id, s.account.sessionId)
                        s.sentAt.remove(record.command.id)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { break /* durable results remain pending; do not stall on the whole backlog */ }
                }
            }
        }
        val pollInterval = if (s.ready) 15_000 else 3_000
        if (now() - s.polledAt >= pollInterval) {
            s.polledAt = now()
            try { transport.poll(s.account.token).forEach { receive(it, s) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* reconnect and fallback remain active */ }
        }
    }

    private fun connect(s: Session) {
        val generation = ++s.generation
        s.connectedAt = now()
        fun deliver(block: () -> Unit) {
            scope.launch { if (sessions[s.account.sessionId] === s && generation == s.generation) block() }
        }
        s.connection = transport.connect(s.account.token,
            onReady = { deliver { s.ready = true; s.backoff = 1_000 } },
            onCommand = { command -> deliver { receive(command, s) } },
            onReceipt = { id -> deliver {
                journal.confirm(id, s.account.sessionId); s.sentAt.remove(id)
            } },
            onClosed = { deliver {
                s.ready = false; s.connection = null
                s.retryAt = now() + s.backoff
                s.backoff = (s.backoff * 2).coerceAtMost(30_000)
            } },
        )
    }

    private fun receive(incoming: TvCommand, s: Session) {
        // Re-anchor the deadline on this TV's clock. Comparing the server's absolute expiry with a
        // TV clock that runs fast rejected every 30 s command as "expired".
        val command = incoming.expiresInMs?.let { incoming.copy(expiresAtMs = now() + it) } ?: incoming
        if (sessions[s.account.sessionId] !== s) return
        if (journal.get(command.id) != null) return
        if (!journal.begin(command, s.account.sessionId)) return
        if (command.expiresAtMs == null || command.expiresAtMs <= now()) {
            journal.finish(command.id, "expired")
        } else onCommand(command, s.account)
    }

    fun stop() {
        job?.cancel(); job = null
        val old = sessions.values.toList()
        sessions.clear()
        old.forEach { it.generation++; it.connection?.close() }
    }
}
