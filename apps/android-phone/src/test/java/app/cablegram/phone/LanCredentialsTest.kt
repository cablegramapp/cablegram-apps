package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanCredentialsTest {
    private val living = PairedTv(pin = "111111", name = "Living room", capability = "cap-living", deviceId = "tv-living")
    private val bedroom = PairedTv(pin = "222222", name = "Bedroom", capability = "cap-bedroom", deviceId = "tv-bedroom")
    private val oldTv = PairedTv(pin = "333333", name = "Old TV")

    /** What the control plane says about capabilities this phone does not hold; mutable per test. */
    private val serverKnows = mutableMapOf<String, String>()
    private var verifyCalls = 0
    private val verifier = LanCapabilityVerifier(verify = { verifyCalls++; serverKnows[it] })

    private fun serving(vararg tvs: PairedTv) = LanCredentials(verifier).apply { sync(tvs.toList()) }

    @Test
    fun `removing one of two TVs refuses its capability at once and keeps the other`() {
        val credentials = serving(living, bedroom)
        assertTrue(credentials.authorized(listOf("cap-living")))

        credentials.sync(listOf(bedroom))

        assertFalse(credentials.authorized(listOf("cap-living")))
        assertNull(credentials.tvDeviceId("cap-living"))
        assertTrue(credentials.authorized(listOf("cap-bedroom")))
    }

    @Test
    fun `a paired TV's PIN is never accepted, with or without a capability`() {
        val credentials = serving(living, oldTv)
        assertFalse(credentials.authorized(listOf("111111")))
        assertFalse(credentials.authorized(listOf("333333")))
        assertTrue(credentials.authorized(listOf("cap-living")))
    }

    @Test
    fun `a removed TV paired by another phone is asked about again instead of served from the cache`() {
        val adopted = PairedTv(pin = "household-x", name = "Kitchen", deviceId = "tv-kitchen")
        serverKnows["cap-kitchen"] = "tv-kitchen"
        val credentials = serving(living, adopted)
        assertTrue(credentials.authorized(listOf("cap-kitchen")))
        assertEquals(1, verifyCalls)

        serverKnows.remove("cap-kitchen") // revoked on the server
        credentials.sync(listOf(living))

        assertFalse(credentials.authorized(listOf("cap-kitchen")))
        assertEquals(2, verifyCalls)
    }

    @Test
    fun `a revoked TV is refused by device id even after it left the store`() {
        val credentials = serving(living, bedroom)
        credentials.sync(listOf(bedroom))
        credentials.revokeDevice("tv-living")
        assertFalse(credentials.authorized(listOf("cap-living")))
    }

    @Test
    fun `a TV revoked elsewhere stays refused while it is still in the store and the server still vouches for it`() {
        serverKnows["cap-living"] = "tv-living" // the server's revocation cache has not caught up yet
        val credentials = serving(living, bedroom)
        credentials.revokeDevice("tv-living")
        credentials.sync(listOf(living, bedroom)) // e.g. another TV paired meanwhile
        assertFalse(credentials.authorized(listOf("cap-living")))
        assertNull(credentials.tvDeviceId("cap-living"))
    }

    @Test
    fun `pairing a revoked TV again lets it back in`() {
        val credentials = serving(living, bedroom)
        credentials.revokeDevice("tv-living")
        credentials.sync(listOf(bedroom))
        val repaired = living.copy(pin = "444444", capability = "cap-living-2")
        credentials.sync(listOf(bedroom, repaired))
        assertTrue(credentials.authorized(listOf("cap-living-2")))
    }

    @Test
    fun `nothing supplied is never authorized`() {
        assertFalse(serving(living).authorized(emptyList()))
        assertFalse(LanCredentials().authorized(listOf("")))
    }
}
