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
}
