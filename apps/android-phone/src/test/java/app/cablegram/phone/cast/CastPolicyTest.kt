package app.cablegram.phone.cast
import app.cablegram.phone.PairedTv
import app.cablegram.phone.MeDevice
import app.cablegram.phone.TargetResult
import org.junit.Assert.*
import org.junit.Test
class CastPolicyTest {
    private val tv = PairedTv("123456", "Living", deviceId = "tv", castDeviceId = "route")
    @Test fun `route mapping requires selected paired route`() {
        val route = CastRoute("route", "Living")
        assertEquals(route, matchingCastRoute(tv, listOf(route)))
        assertNull(matchingCastRoute(tv.copy(castDeviceId = null), listOf(route)))
        assertNull(matchingCastRoute(tv, listOf(CastRoute("other", "Living"))))
        assertNull(matchingCastRoute(tv, emptyList()))
    }
    @Test fun `wrong TV can retry only existing household pairing once`() {
        val status = CastDeviceStatus("tv", true)
        assertEquals(tv, wrongTvRetry(status, listOf(tv), setOf("tv"), false))
        assertNull(wrongTvRetry(status, emptyList(), setOf("tv"), false))
        assertNull(wrongTvRetry(status, listOf(tv), emptySet(), false))
        assertNull(wrongTvRetry(status, listOf(tv), setOf("tv"), true))
        assertNull(wrongTvRetry(status.copy(wrongTv = false), listOf(tv), setOf("tv"), false))
    }
    @Test fun `offline Cast target stays selected and revoked pairing is refused`() {
        val household = listOf(MeDevice(id = "tv", online = false), MeDevice(id = "other", online = true))
        assertEquals(TargetResult.Target("tv", "Living"), castCommandTarget(tv, household))
        assertTrue(castCommandTarget(tv, household.drop(1)) is TargetResult.Failed)
        assertTrue(castCommandTarget(tv.copy(deviceId = null), household) is TargetResult.Failed)
    }
    @Test fun `flag off no Play services and no route all keep relay fallback`() {
        assertTrue(useCastForTitle(true, true, true))
        assertFalse(useCastForTitle(false, true, true))
        assertFalse(useCastForTitle(true, false, true))
        assertFalse(useCastForTitle(true, true, false))
    }
    @Test fun `only terminal idle reasons clear playback`() {
        listOf(1, 2, 4).forEach { assertTrue(terminalCastIdle(1, it)) }
        assertFalse(terminalCastIdle(1, 0)); assertFalse(terminalCastIdle(1, 3)); assertFalse(terminalCastIdle(2, 1))
    }
    @Test fun `poster excludes signed and authenticated URLs`() {
        assertEquals("https://images.example/p.jpg", publicPosterUrl("https://images.example/p.jpg"))
        listOf(null, "file:///secret", "http://example/p", "https://user:pass@example/p",
            "https://example/p?token=secret", "https://example/p#secret", "bad url").forEach { assertNull(publicPosterUrl(it)) }
    }
}
