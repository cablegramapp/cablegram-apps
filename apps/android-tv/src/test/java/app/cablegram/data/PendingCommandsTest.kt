package app.cablegram.data

import org.junit.Assert.*
import org.junit.Test

class PendingCommandsTest {
    @Test
    fun `a polled batch retains every command in order`() {
        val pending = PendingCommands()
        val pause = TvCommand("1", "pause")
        val seek = TvCommand("2", "seek")
        pending.add(pause)
        pending.add(seek)
        pending.add(pause)
        assertEquals(pause, pending.first)
        pending.consume()
        assertEquals(seek, pending.first)
        pending.consume()
        assertNull(pending.first)
        pending.consume()
    }
}
