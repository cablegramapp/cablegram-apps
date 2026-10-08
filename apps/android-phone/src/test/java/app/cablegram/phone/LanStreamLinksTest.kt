package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanStreamLinksTest {
    private var now = 1_000_000L
    private val links = LanStreamLinks(nowMs = { now }, idleMs = 15 * 60_000L, maxMs = 60 * 60_000L)
    private val accepted = mutableSetOf("cap-tv1")
    private fun redeem(id: String?, path: String = "/media/film") = links.redeem(id, path) { it in accepted }

    @Test
    fun `a link plays the title it was issued for, for its TV`() {
        val id = links.issue("/media/film", "cap-tv1", "tv-1")
        assertEquals("tv-1", redeem(id)?.tvDeviceId)
    }

    @Test
    fun `a link plays no other title and is not the capability`() {
        val id = links.issue("/media/film", "cap-tv1", "tv-1")
        assertNull(redeem(id, "/media/other"))
        assertNull(redeem(id, "/telegram/AgADabcdEFGH"))
        assertNull(redeem(id, "/library"))
        assertNull(redeem("cap-tv1"))
        assertNotEquals("cap-tv1", id)
    }

    @Test
    fun `each link is new and unguessable`() {
        val first = links.issue("/media/film", "cap-tv1", "tv-1")
        val second = links.issue("/media/film", "cap-tv1", "tv-1")
        assertNotEquals(first, second)
        assertTrue(first.length >= 43)
        assertNull(redeem(null))
        assertNull(redeem(""))
        assertNull(redeem("not-a-link"))
    }

    @Test
    fun `an unused link ends after fifteen minutes`() {
        val id = links.issue("/media/film", "cap-tv1", "tv-1")
        now += 15 * 60_000L - 1
        assertTrue(redeem(id) != null)
        val idle = links.issue("/media/film", "cap-tv1", "tv-1")
        now += 15 * 60_000L
        assertNull(redeem(idle))
    }

    @Test
    fun `a link in use lasts the film, but never past its cap`() {
        val id = links.issue("/media/film", "cap-tv1", "tv-1")
        repeat(5) {
            now += 10 * 60_000L
            assertTrue(redeem(id) != null)
        }
        now += 9 * 60_000L // 59 minutes in, still in use
        assertTrue(redeem(id) != null)
        now += 2 * 60_000L // past the hour cap, though just used
        assertNull(redeem(id))
    }

    @Test
    fun `a removed or revoked TV's link stops at once and stays stopped`() {
        val id = links.issue("/media/film", "cap-tv1", "tv-1")
        accepted.clear()
        assertNull(redeem(id))
        accepted.add("cap-tv1")
        assertNull(redeem(id))
    }

    @Test
    fun `a TV asking again and again cannot grow the table without bound`() {
        val first = links.issue("/media/film", "cap-tv1", "tv-1")
        repeat(300) { now += 1; links.issue("/media/film", "cap-tv1", "tv-1") }
        assertNull(redeem(first))
    }

    @Test
    fun `only this phone itself may send the capability as a query parameter`() {
        assertTrue(LanLibraryServer.isLoopback("127.0.0.1"))
        assertTrue(LanLibraryServer.isLoopback("::1"))
        assertFalse(LanLibraryServer.isLoopback("192.168.1.20"))
        assertFalse(LanLibraryServer.isLoopback("localhost"))
        assertFalse(LanLibraryServer.isLoopback(null))
    }
}
