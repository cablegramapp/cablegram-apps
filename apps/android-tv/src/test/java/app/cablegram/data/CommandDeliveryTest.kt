package app.cablegram.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CommandDeliveryTest {
    private class FakeTransport : CommandTransport {
        class Socket(val ready: () -> Unit, val command: (TvCommand) -> Unit,
                     val receipt: (String) -> Unit, val closed: () -> Unit) : CommandConnection {
            var acceptsSend = true
            var closedByClient = false
            val messages = mutableListOf<String>()
            override fun send(text: String): Boolean { messages.add(text); return acceptsSend }
            override fun close() { closedByClient = true; closed() }
        }
        val sockets = mutableListOf<Socket>()
        val results = mutableListOf<CommandRecord>()
        val pollCommands = mutableListOf<TvCommand>()
        var failHttp = false
        var polls = 0
        override fun connect(token: String, onReady: () -> Unit, onCommand: (TvCommand) -> Unit,
                             onReceipt: (String) -> Unit, onClosed: () -> Unit): CommandConnection =
            Socket(onReady, onCommand, onReceipt, onClosed).also { sockets.add(it) }
        override suspend fun poll(token: String): List<TvCommand> { polls++; return pollCommands.toList() }
        override suspend fun result(token: String, record: CommandRecord) {
            if (failHttp) throw java.io.IOException("offline")
            results.add(record)
        }
    }

    private suspend fun until(predicate: () -> Boolean) {
        withTimeout(2000) { while (!predicate()) delay(1) }
    }
    private val account = AccountCredential("session", "token")
    private fun command(id: String = "x") = TvCommand(id, "seek", expiresAtMs = 30_000)

    @Test fun `Cast signal polls immediately while socket is ready without duplicate execution`() = runBlocking {
        val transport = FakeTransport()
        val executed = mutableListOf<String>()
        val delivery = CommandDelivery(this, transport, CommandJournal(null, {}, { 1000 }),
            { command, _ -> executed.add(command.id) }, { 1000 }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { transport.polls == 1 }
            transport.sockets.single().ready()
            transport.pollCommands.add(command("cast"))
            delivery.pollNow()
            until { executed.size == 1 }
            delivery.pollNow()
            until { transport.polls >= 3 }
            assertEquals(listOf("cast"), executed)
        } finally { delivery.stop() }
    }

    @Test fun `a TV clock running fast does not expire a command that still has time left`() = runBlocking {
        val tvClock = 10 * 60_000L // ten minutes ahead of the server's 30 s deadline
        val transport = FakeTransport().apply {
            pollCommands.add(TvCommand("skew", "pause", expiresAtMs = 30_000, expiresInMs = 25_000))
        }
        val journal = CommandJournal(null, {}, { tvClock })
        val executed = mutableListOf<TvCommand>()
        val delivery = CommandDelivery(this, transport, journal, { c, _ -> executed.add(c) }, { tvClock }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { executed.size == 1 }
            assertEquals(tvClock + 25_000, executed.single().expiresAtMs)
            assertNull(executed.single().validationError(tvClock))
        } finally { delivery.stop() }
    }

    @Test fun `receipt is sent only after execution finishes and pending duplicates are suppressed`() = runBlocking {
        var clock = 1000L
        val transport = FakeTransport().apply { pollCommands.add(command()) }
        val journal = CommandJournal(null, {}, { clock })
        var executions = 0
        val delivery = CommandDelivery(this, transport, journal, { _, _ -> executions++ }, { clock }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { executions == 1 }
            transport.sockets.single().ready()
            transport.sockets.single().command(command())
            clock += 5000
            delay(10)
            assertTrue(transport.results.isEmpty())
            assertTrue(transport.sockets.single().messages.isEmpty())
            assertEquals(1, executions)
            journal.finish("x")
            clock += 1000
            until { transport.sockets.single().messages.size == 1 }
        } finally { delivery.stop() }
    }

    @Test fun `reconnects repeatedly and stopped sessions cannot reconnect from stale callbacks`() = runBlocking {
        var clock = 1000L
        val transport = FakeTransport()
        val delivery = CommandDelivery(this, transport, CommandJournal(null, {}, { clock }), { _, _ -> }, { clock }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { transport.sockets.size == 1 }
            val first = transport.sockets[0]
            first.closed()
            delay(10)
            clock += 1000
            until { transport.sockets.size == 2 }
            transport.sockets[1].closed()
            delay(10)
            clock += 2000
            until { transport.sockets.size == 3 }
            first.closed() // stale callback cannot remove the new connection
            transport.sockets[2].ready()
            delivery.stop()
            clock += 60_000
            delay(10)
            assertEquals(3, transport.sockets.size)
            assertTrue(transport.sockets.last().closedByClient)
        } finally { delivery.stop() }
    }

    @Test fun `lost socket receipt retries over HTTP without executing a replay twice`() = runBlocking {
        var clock = 1000L
        val journal = CommandJournal(null, {}, { clock })
        val transport = FakeTransport()
        var executions = 0
        val delivery = CommandDelivery(this, transport, journal, { c, _ ->
            executions++; journal.finish(c.id)
        }, { clock }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { transport.sockets.isNotEmpty() }
            val socket = transport.sockets.single()
            socket.ready()
            socket.command(command())
            until { executions == 1 }
            clock += 1000
            until { socket.messages.isNotEmpty() }
            assertEquals(1, journal.pending(account.sessionId).size)
            socket.command(command())
            clock += 3001
            until { transport.results.size == 1 }
            assertTrue(journal.pending(account.sessionId).isEmpty())
            assertEquals(1, executions)
        } finally { delivery.stop() }
    }

    @Test fun `failed socket send falls back and offline HTTP retains rejection until retry succeeds`() = runBlocking {
        var clock = 1000L
        val journal = CommandJournal(null, {}, { clock })
        val transport = FakeTransport().apply { failHttp = true }
        val delivery = CommandDelivery(this, transport, journal, { c, _ ->
            journal.finish(c.id, "unsupported_command")
        }, { clock }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { transport.sockets.isNotEmpty() }
            transport.sockets.single().apply { acceptsSend = false; ready(); command(command()) }
            until { journal.pending(account.sessionId).size == 1 }
            clock += 1000
            delay(10)
            assertFalse(journal.get("x")!!.confirmed)
            transport.failHttp = false
            clock += 1000
            until { journal.get("x")!!.confirmed }
            assertEquals("reject", transport.results.single().status)
        } finally { delivery.stop() }
    }

    @Test fun `server receipt confirms result and duplicate transports share execution boundary`() = runBlocking {
        var clock = 1000L
        val journal = CommandJournal(null, {}, { clock })
        val transport = FakeTransport().apply { pollCommands.add(command()) }
        var executions = 0
        val delivery = CommandDelivery(this, transport, journal, { c, _ ->
            executions++; journal.finish(c.id)
        }, { clock }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { executions == 1 }
            val socket = transport.sockets.single()
            socket.ready(); socket.command(command()); socket.receipt("x")
            until { journal.get("x")!!.confirmed }
            clock += 16_000
            until { transport.polls > 1 }
            assertEquals(1, executions)
            assertTrue(transport.results.isEmpty())
        } finally { delivery.stop() }
    }

    @Test fun `expired and missing expiry commands are rejected without execution`() = runBlocking {
        val transport = FakeTransport().apply {
            pollCommands.add(TvCommand("old", "pause", expiresAtMs = 500))
            pollCommands.add(TvCommand("legacy", "pause"))
        }
        val journal = CommandJournal(null, {}, { 1000 })
        var executions = 0
        val delivery = CommandDelivery(this, transport, journal, { _, _ -> executions++ }, { 1000 }, 1)
        try {
            delivery.setAccounts(listOf(account))
            until { journal.pending(account.sessionId).size == 2 }
            assertEquals(0, executions)
            assertTrue(journal.pending(account.sessionId).all { it.reason == "expired" })
        } finally { delivery.stop() }
    }
}
