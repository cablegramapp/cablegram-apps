package app.cablegram.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceBadgeTest {
    @Test
    fun `names each kind of source once, in a fixed order`() {
        assertEquals("Telegram", sourceBadge(listOf("telegram")))
        assertEquals("Telegram · Phone", sourceBadge(listOf("phone_local", "telegram")))
        assertEquals("Cloud", sourceBadge(listOf("own_cloud", "cloud_object")))
        assertEquals("Cloud · Phone · Web", sourceBadge(setOf("web", "phone_local", "own_cloud")))
    }

    @Test
    fun `no badge without a known source`() {
        assertNull(sourceBadge(emptyList()))
        assertNull(sourceBadge(listOf("something_new")))
    }
}
