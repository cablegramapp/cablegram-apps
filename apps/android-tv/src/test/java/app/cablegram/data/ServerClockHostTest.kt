package app.cablegram.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClockHostTest {
    @Test
    fun `only the control plane's answers teach the TV server time`() {
        val base = "https://api.cablegram.app/"
        assertTrue(isControlPlane("https://api.cablegram.app/api/me".toHttpUrl(), base))
        assertFalse("a phone on the LAN", isControlPlane("http://192.168.3.179:8765/library".toHttpUrl(), base))
        assertFalse("another port on the same host", isControlPlane("http://127.0.0.1:8765/library".toHttpUrl(), "http://127.0.0.1:3000/"))
        assertFalse("an unparseable base", isControlPlane("https://api.cablegram.app/".toHttpUrl(), "not a url"))
    }
}
