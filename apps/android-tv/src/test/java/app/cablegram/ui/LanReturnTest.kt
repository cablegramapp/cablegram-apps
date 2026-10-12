package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A title that fell back to the relay goes back to the phone over Wi-Fi when it answers there again. */
class LanReturnTest {
    private val relay = "https://api.cablegram.app/relay/v1/p/phone/media/abc?token=t&rt=r"
    private val lan = "http://192.168.3.179:8765/media/abc?token=t"
    private val phoneTelegram = "http://192.168.3.179:8765/telegram/abc?token=t"

    @Test fun `on the relay with the phone's LAN address in reserve, that address is the way back`() {
        assertEquals(lan, lanReturnCandidate(relay, lan))
        assertEquals(phoneTelegram, lanReturnCandidate(relay, phoneTelegram))
    }

    @Test fun `nothing to return to when already on LAN, without a reserve, or when the reserve is not on this Wi-Fi`() {
        assertNull("already on LAN (the relay is the reserve)", lanReturnCandidate(lan, relay))
        assertNull(lanReturnCandidate(relay, null))
        assertNull("cloud storage is not the phone", lanReturnCandidate(relay, "https://storage.example.com/film.mkv?sig=x"))
        assertNull("the TV's own Telegram", lanReturnCandidate(relay, "http://127.0.0.1:4567/tg/12"))
        assertNull(lanReturnCandidate(relay, "https://api.cablegram.app/relay/v1/p/other/media/abc"))
        assertNull(lanReturnCandidate(null, lan))
    }

    @Test fun `the check runs about once a minute`() {
        assertEquals(60_000L, LAN_RETURN_CHECK_MS)
    }
}
