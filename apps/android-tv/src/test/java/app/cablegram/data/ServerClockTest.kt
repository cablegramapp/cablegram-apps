package app.cablegram.data

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClockTest {
    private val serverNow = 1_700_000_000_000L

    /** A device: a wall clock that can jump, and time since boot that only moves forward. */
    private class Device(var wall: Long, var sinceBoot: Long = 50_000L) {
        fun advance(ms: Long) { wall += ms; sinceBoot += ms }
        fun clock() = ServerClock({ wall }, { sinceBoot })
    }

    private class MemoryStore(var saved: ServerClock.Saved? = null) : ServerClock.Store {
        override fun load() = saved
        override fun save(saved: ServerClock.Saved) { this.saved = saved }
    }

    @Test fun `a wall-clock jump after the server was seen does not move the stamps`() {
        val device = Device(wall = serverNow + 600_000) // ten minutes fast
        val clock = device.clock()
        clock.observe(Date(serverNow))
        device.advance(5_000)
        device.wall -= 600_000 // NTP corrects the clock while the control plane is unreachable
        assertEquals(serverNow + 5_000, clock.now())
        device.wall += 3_600_000 // or someone sets it an hour ahead
        device.advance(1_000)
        assertEquals(serverNow + 6_000, clock.now())
    }

    @Test fun `after an app restart in the same boot the learned correction still holds`() {
        val store = MemoryStore()
        val device = Device(wall = serverNow - 300_000) // five minutes slow
        device.clock().apply { attach(store, bootId = "7"); observe(Date(serverNow)) }
        device.advance(20_000)
        val restarted = device.clock().apply { attach(store, bootId = "7") } // offline from the start
        assertTrue(restarted.learnedThisBoot)
        assertEquals(serverNow + 20_000, restarted.now())
    }

    @Test fun `after a reboot only the wall-clock offset is trusted, and it beats the bare device clock`() {
        val store = MemoryStore()
        val device = Device(wall = serverNow - 300_000)
        device.clock().apply { attach(store, bootId = "7"); observe(Date(serverNow)) }
        val rebooted = Device(wall = device.wall + 60_000, sinceBoot = 4_000) // a minute later, a new boot
        val clock = rebooted.clock().apply { attach(store, bootId = "8") }
        assertFalse("the old boot's anchor means nothing now", clock.learnedThisBoot)
        assertEquals(serverNow + 60_000, clock.now()) // the device's own clock would say five minutes less
    }

    @Test fun `nothing saved means the device clock, and an observation in this run wins over the saved one`() {
        val device = Device(wall = 10_000L)
        val clock = device.clock()
        clock.attach(MemoryStore(), bootId = "1")
        assertEquals(10_000L, clock.now())
        assertFalse(clock.learnedThisBoot)
        clock.observe(Date(70_000L))
        clock.attach(MemoryStore(ServerClock.Saved("1", bootAnchor = 0L, wallOffset = 0L)), bootId = "1")
        assertEquals(70_000L, clock.now())
    }

    @Test fun `a missing boot id never trusts a saved anchor`() {
        val store = MemoryStore(ServerClock.Saved(null, bootAnchor = 999_999L, wallOffset = 1_000L))
        val clock = Device(wall = 10_000L).clock().apply { attach(store, bootId = null) }
        assertFalse(clock.learnedThisBoot)
        assertEquals(11_000L, clock.now())
    }

    /** The control plane's progress rule (`progress/service.ts`): replace unless the stored time is later. */
    private class FakeServer {
        var position: Int? = null
        var updatedAt = Long.MIN_VALUE
        fun put(positionSeconds: Int, at: Long) { if (updatedAt <= at) { position = positionSeconds; updatedAt = at } }
    }

    @Test fun `two live updates that arrive out of order keep the newer position when they carry their time`() {
        val server = FakeServer()
        val clock = Device(wall = serverNow).clock().apply { observe(Date(serverNow)) }
        val first = clock.now()
        val second = first + 10_000 // ten seconds of playback later
        server.put(610, at = second) // the newer update overtakes the older one on the network
        server.put(600, at = first)
        assertEquals(610, server.position)
    }
}
