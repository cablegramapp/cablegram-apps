package app.cablegram.cast

import app.cablegram.data.TvCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CastLaunchTest {
    private fun command(id: String = "command", expires: Long? = 100) = TvCommand(
        id, "play", buildJsonObject { put("videoId", "title") }, expiresAtMs = expires)

    @Test fun `launch accepts only the two string IDs`() {
        assertEquals(CastLaunch("command", "tv"), CastLaunch.parse("""{"commandId":"command","targetDeviceId":"tv"}"""))
        listOf(null, "[]", "{}", """{"commandId":1,"targetDeviceId":"tv"}""",
            """{"commandId":"","targetDeviceId":"tv"}""",
            """{"commandId":"command","targetDeviceId":"tv","url":"https://media"}""")
            .forEach { assertNull(CastLaunch.parse(it)) }
    }

    @Test fun `wrong TV signal cannot replace a valid hold`() {
        val hold = CastTitleHold()
        assertTrue(hold.signal(CastLaunch("command", "tv"), setOf("tv")))
        assertTrue(hold.hold(command(), "tv"))
        assertFalse(hold.signal(CastLaunch("other", "wrong"), setOf("tv")))
        assertEquals("command", hold.launch?.commandId)
        assertEquals("command", hold.command?.id)
    }

    @Test fun `only matching command and credential may wait on picker`() {
        val hold = CastTitleHold()
        hold.signal(CastLaunch("command", "tv"), setOf("tv"))
        assertFalse(hold.hold(command("other"), "tv"))
        assertFalse(hold.hold(command(), "other-tv"))
        assertFalse(hold.hold(TvCommand("command", "pause"), "tv"))
        assertTrue(hold.hold(command(), "tv"))
        assertNull(hold.select(hasTitle = true, now = 50))
        assertEquals("title_unavailable", hold.select(hasTitle = false, now = 50))
    }

    @Test fun `command first waits only for marked title starts within expiry`() {
        val marked = command(expires = 300_000).copy(payload = buildJsonObject {
            put("videoId", "title"); put("castLaunch", true)
        })
        assertEquals(30_000L, castSignalWaitMs(marked, 0))
        assertEquals(100L, castSignalWaitMs(marked.copy(expiresAtMs = 100), 0))
        assertNull(castSignalWaitMs(command(), 0))
        assertNull(castSignalWaitMs(marked.copy(command = "pause"), 0))
        assertNull(castSignalWaitMs(marked.copy(expiresAtMs = null), 0))
        val hold = CastTitleHold()
        assertFalse(hold.hold(marked, "tv"))
        assertEquals("profile_required", hold.select(true, 0))
    }

    @Test fun `expiry and leaving clear the held command`() {
        val hold = CastTitleHold()
        hold.signal(CastLaunch("command", "tv"), setOf("tv"))
        hold.hold(command(), "tv")
        assertEquals("expired", hold.select(hasTitle = true, now = 100))
        assertEquals("command", hold.clear()?.id)
        assertNull(hold.command)
        assertNull(hold.launch)
        assertEquals("profile_required", hold.select(hasTitle = true, now = 50))
    }
}
