package app.cablegram.phone

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RemoteCommandsTest {
    @Test
    fun `seek sends a numeric relative offset to one TV`() {
        val body = remoteCommandBody("seek", "living-room", arguments = buildJsonObject { put("seconds", -15) })
        assertEquals("living-room", body["target_device_id"]?.jsonPrimitive?.content)
        assertEquals("seek", body["command"]?.jsonPrimitive?.content)
        assertEquals(-15, body["payload"]?.jsonObject?.get("seconds")?.jsonPrimitive?.int)
    }

    @Test
    fun `resume does not reissue a title start`() {
        val body = remoteCommandBody("play", "bedroom")
        assertEquals("play", body["command"]?.jsonPrimitive?.content)
        assertFalse(body.getValue("payload").jsonObject.containsKey("videoId"))
    }

    @Test
    fun `starting a title preserves the selected identity`() {
        val body = remoteCommandBody("play", "living-room", videoId = "movie-42")
        assertEquals("movie-42", body["payload"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing target cannot broadcast a remote action`() {
        remoteCommandBody("stop", "")
    }

    @Test
    fun `the body carries a client-generated UUID id`() {
        val body = remoteCommandBody("pause", "living-room")
        val id = body["id"]?.jsonPrimitive?.content
        assertEquals(id, java.util.UUID.fromString(id).toString())
        assertEquals("fixed", remoteCommandBody("pause", "living-room", id = "fixed")["id"]?.jsonPrimitive?.content)
    }

    private fun device(id: String, name: String? = null) = MeDevice(id = id, kind = "tv", displayName = name)
    private val living = PairedTv(pin = "1", name = "Living room", deviceId = "tv-1")

    @Test
    fun `a selected TV present in the household is the target`() {
        val target = resolveTarget(living, listOf(device("tv-2", "Bedroom"), device("tv-1")))
        assertEquals(TargetResult.Target("tv-1", "Living room"), target)
    }

    @Test
    fun `a selected TV missing from the household fails instead of using another TV`() {
        val result = resolveTarget(living, listOf(device("tv-2", "Bedroom")))
        assertEquals(TargetResult.Failed("Living room is no longer connected. Choose a TV in Settings."), result)
    }

    @Test
    fun `with no selection the first household TV is the default`() {
        assertEquals(TargetResult.Target("tv-2", "Bedroom"), resolveTarget(null, listOf(device("tv-2", "Bedroom"), device("tv-3"))))
        assertTrue(resolveTarget(null, emptyList()) is TargetResult.Failed)
    }

    @Test
    fun `offline uses the selected TV's stored id`() {
        assertEquals(TargetResult.Target("tv-1", "Living room"), resolveTarget(living, null))
        assertTrue(resolveTarget(null, null) is TargetResult.Failed)
    }

    private val legacy = PairedTv(pin = "9", name = "Old TV")

    @Test
    fun `a TV paired without a device id uses the household's only TV`() {
        assertEquals(TargetResult.Target("tv-2", "Old TV"), resolveTarget(legacy, listOf(device("tv-2", "Bedroom"))))
    }

    @Test
    fun `a TV paired without a device id asks for a choice when there are several`() {
        assertEquals(
            TargetResult.Failed("Choose which TV to control in Settings."),
            resolveTarget(legacy, listOf(device("tv-2"), device("tv-3"))),
        )
        assertTrue(resolveTarget(legacy, emptyList()) is TargetResult.Failed)
        assertTrue(resolveTarget(legacy, null) is TargetResult.Failed)
    }

    @Test
    fun `superseded and cancelled title starts say what happened`() {
        assertEquals("Another title was started on Den.", rejectionMessage("superseded", "Den"))
        assertEquals("Playback was cancelled on Den.", rejectionMessage("cancelled", "Den"))
    }

    @Test
    fun `a title being started takes over the mini-player instead of the one playing`() {
        val lines = miniPlayerLines("Old film", "New film", "Den", "Starting on Den…")!!
        assertEquals("New film", lines.title)
        assertEquals("Starting on Den…", lines.caption)
        assertFalse(lines.canToggle)
    }

    @Test
    fun `the mini-player shows the playing title and falls back to the TV name`() {
        assertEquals(MiniPlayerLines("Old film", "Den", canToggle = true), miniPlayerLines("Old film", null, "Den", null))
        assertEquals("Den didn't confirm. Check the TV.", miniPlayerLines("Old film", null, "Den", "Den didn't confirm. Check the TV.")!!.caption)
    }

    @Test
    fun `the mini-player is hidden with nothing playing or starting`() {
        assertNull(miniPlayerLines(null, null, "Den", "Done on Den"))
    }
}
