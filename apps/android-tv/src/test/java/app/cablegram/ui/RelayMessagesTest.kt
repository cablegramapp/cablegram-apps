package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayMessagesTest {
    @Test fun `relay URLs are recognised and announced`() {
        assertTrue(isRelayUrl("https://api.cablegram.app/relay/v1/p/abc/media/x?token=t&rt=r"))
        assertTrue(!isRelayUrl("http://192.168.1.4:8765/media/x?token=t"))
        assertTrue(transportNoticeFor("https://h/relay/v1/p/a/media/b").contains("relay"))
    }

    @Test fun `relay refusals map to what the viewer can do`() {
        assertTrue(relayMessage(503, "phone_offline").contains("phone isn't reachable"))
        assertTrue(relayMessage(403, "mobile_data_not_allowed").contains("mobile data"))
        assertTrue(relayMessage(402, "relay_quota_exceeded").contains("used up"))
        assertTrue(relayMessage(429, "relay_streams_exceeded").contains("Too many"))
        assertTrue(relayMessage(504, "phone_timeout").contains("didn't respond"))
        // Proxy error without a relay code: the relay itself is down, not the phone.
        assertTrue(relayMessage(502, null).contains("relay isn't reachable"))
        assertTrue(relayMessage(null, null).contains("TV's internet"))
    }

    @Test fun `playback URLs in logs hide tokens, passes and relay tickets`() {
        val logged = playbackUrlForLog("https://h/relay/v1/p/a/media/b?token=cap&pass=p&rt=ticket")
        assertEquals(false, logged.contains("cap") || logged.contains("=p&") || logged.contains("ticket"))
    }

    @Test fun `a phone's stream link is hidden in logs and kept alive only on the LAN`() {
        val lan = "http://192.168.1.4:8765/media/x?link=secret-link"
        assertEquals(false, playbackUrlForLog(lan).contains("secret-link"))
        assertTrue(isLanStreamLink(lan))
        assertTrue(isLanStreamLink("http://192.168.1.4:8765/telegram/AgADabcdEFGH?pass=p&link=l"))
        assertTrue(!isLanStreamLink("http://192.168.1.4:8765/media/x"))
        assertTrue(!isLanStreamLink("https://api.cablegram.app/relay/v1/p/abc/media/x?link=l&rt=r"))
        assertTrue(!isLanStreamLink("http://127.0.0.1:41234/tg/17"))
    }

    @Test fun `Telegram sources are told apart from the phone and the relay`() {
        val own = "http://127.0.0.1:41234/tg/17"
        val viaPhone = "http://192.168.1.4:8765/telegram/AgADabcdEFGH?token=t"
        val viaRelay = "https://api.cablegram.app/relay/v1/p/abc/telegram/AgADabcdEFGH?token=t&rt=r"
        assertTrue(isTelegramLocalUrl(own) && !isTelegramLocalUrl(viaPhone))
        assertTrue(isPhoneTelegramUrl(viaPhone) && isPhoneTelegramUrl(viaRelay) && !isPhoneTelegramUrl("http://192.168.1.4:8765/media/x"))
        assertEquals("Playing straight from Telegram", transportNoticeFor(own))
        assertTrue(transportNoticeFor(viaPhone).contains("through your phone") && !transportNoticeFor(viaPhone).contains("relay"))
        assertTrue(transportNoticeFor(viaRelay).contains("relay"))
        assertTrue(stallSwitchSeconds(own) > stallSwitchSeconds(viaPhone))
        assertTrue(lostSourceMessage(own).contains("Telegram"))
        assertTrue(transitionTo(viaPhone).title.contains("phone") && transitionTo(own).title.contains("Telegram"))
    }
}
