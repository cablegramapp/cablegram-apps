package app.cablegram.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferingLevelTest {
    @Test
    fun `a seek's stream of percentages reports only the three levels`() {
        val levels = listOf(0f, 3f, 12f, 49.9f, 50f, 77f, 99.9f, 100f, 100f).map(::bufferingLevel)
        assertEquals(listOf(0, 1, 2), levels.distinct())
        assertEquals(1, bufferingLevel(50f))
        assertEquals(2, bufferingLevel(100f))
    }
}
