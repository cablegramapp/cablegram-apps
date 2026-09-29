package app.cablegram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvChannelsTest {
    @Test
    fun `every live station has its own thumbnail resource`() {
        val posters = FREE_LIVE_TV_CHANNELS.map { it.posterRes }
        assertEquals(FREE_LIVE_TV_CHANNELS.size, posters.distinct().size)
        assertTrue(posters.all { it != 0 })
    }
}
