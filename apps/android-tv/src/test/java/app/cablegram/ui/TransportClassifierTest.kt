package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportClassifierTest {
    private fun kind(url: String?, live: Boolean = false) = classifyTransport(url, live)

    @Test
    fun `Telegram on this TV is not shown as the local network`() {
        assertEquals(Transport.TELEGRAM, kind("http://127.0.0.1:41000/tg/17"))
    }

    @Test
    fun `Telegram carried by the phone says so, and over the relay it is the relay`() {
        assertEquals(Transport.TELEGRAM_VIA_PHONE, kind("http://192.168.1.20:8765/telegram/AgADabc?token=t"))
        assertEquals(Transport.RELAY, kind("https://api.cablegram.app/relay/v1/telegram/AgADabc"))
    }

    @Test
    fun `the phone on the home network is LAN and the relay is the relay`() {
        assertEquals(Transport.LAN, kind("http://192.168.1.20:8765/media/abc"))
        assertEquals(Transport.RELAY, kind("https://relay.cablegram.app/relay/v1/media/abc"))
    }

    @Test
    fun `storage links are cloud and live channels are live`() {
        assertEquals(Transport.CLOUD, kind("https://bucket.r2.cloudflarestorage.com/v.mp4?sig=1"))
        assertEquals(Transport.CLOUD, kind("https://www.googleapis.com/drive/v3/files/1?alt=media"))
        // A storage path that merely has a "telegram" folder is not the phone.
        assertEquals(Transport.CLOUD, kind("https://bucket.r2.cloudflarestorage.com/telegram/AgADabc/v.mp4?sig=1"))
        assertEquals(Transport.CLOUD, kind("https://example.com/files/telegram/AgADabc"))
        assertEquals(Transport.CLOUD, kind(null))
        assertEquals(Transport.LIVE, kind("https://example.com/live.m3u8", live = true))
    }
}
