package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairUriTest {
    @Test
    fun `malformed QR input never crashes pairing`() {
        assertNull(parsePairUri("cablegram://pair?token=%ZZ"))
        assertNull(parsePairUri("cablegram://pair?token="))
        assertNull(parsePairUri("cablegram://pair?token=%20"))
    }

    @Test
    fun `pairing rejects lookalike hosts and extra paths`() {
        assertNull(parsePairUri("cablegram://pair.evil?token=abc"))
        assertNull(parsePairUri("cablegram://pair/other?token=abc"))
        assertNull(parsePairUri("cablegram://pair@evil?token=abc"))
    }

    @Test
    fun `server URLs require a host and reject credentials and query strings`() {
        org.junit.Assert.assertTrue(isValidServerUrl("https://api.example.test/"))
        org.junit.Assert.assertTrue(isValidServerUrl("http://10.0.2.2:3107/"))
        listOf("https://", "file:///tmp/api", "https://user:password@example.test", "https://example.test?token=secret", "http://localhost:99999").forEach {
            org.junit.Assert.assertFalse(it, isValidServerUrl(it))
        }
    }

    @Test
    fun `parses cablegram pair deep link`() {
        val parsed = parsePairUri("cablegram://pair?token=abc123&name=Living%20Room")
        assertEquals("abc123", parsed?.first)
        assertEquals("Living Room", parsed?.second)
    }

    @Test
    fun `rejects other schemes`() {
        assertNull(parsePairUri("https://cablegram.app/pair?token=x"))
    }

    @Test
    fun `treats telegram hash names as weak titles`() {
        org.junit.Assert.assertTrue(isWeakCatalogLabel("a1b2c3d4e5f6a7b8c9d0e1f2.mp4"))
        org.junit.Assert.assertTrue(isWeakCatalogLabel("AgADBAAD4KcxG0abc123xyz.mkv"))
        org.junit.Assert.assertTrue(isWeakCatalogLabel("1_4981178877662464.mp4"))
        org.junit.Assert.assertTrue(isWeakCatalogLabel("38.mp4"))
        org.junit.Assert.assertFalse(isWeakCatalogLabel("Inception.2010.1080p.mkv"))
        org.junit.Assert.assertEquals("Inception 2010", firstCatalogHint("a1b2c3d4e5f6.mp4", "Inception 2010"))
    }

    @Test
    fun `accepts the six digit TV pin`() {
        val parsed = parsePairCode(" 042891 ")
        assertEquals("042891", parsed?.first)
        assertEquals("TV", parsed?.second)
    }

    @Test
    fun `parses the web pairing link from a TV's QR code`() {
        val parsed = parsePairUri("https://app.cablegram.app/?pair=042891&tv=Living+room+TV")
        assertEquals("042891", parsed?.first)
        assertEquals("Living room TV", parsed?.second)
        assertEquals("TV", parsePairUri("https://app.cablegram.app/?pair=042891")?.second)
    }

    @Test
    fun `web pairing links need our origin, a six digit code and no extra path`() {
        assertNull(parsePairUri("https://app.cablegram.app.evil.test/?pair=042891"))
        assertNull(parsePairUri("http://app.cablegram.app/?pair=042891"))
        assertNull(parsePairUri("https://user@app.cablegram.app/?pair=042891"))
        assertNull(parsePairUri("https://app.cablegram.app/other?pair=042891"))
        assertNull(parsePairUri("https://app.cablegram.app/?pair=4289"))
        assertNull(parsePairUri("https://app.cablegram.app/?pair=abcdef"))
        assertNull(parsePairUri("https://app.cablegram.app/"))
    }

    @Test
    fun `a test build accepts its own web remote address`() {
        assertEquals("123456", parsePairUri("http://localhost:8080/?pair=123456", setOf("http://localhost:8080"))?.first)
        assertNull(parsePairUri("http://localhost:8080/?pair=123456", setOf("https://app.cablegram.app")))
    }
}
