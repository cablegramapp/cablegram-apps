package app.cablegram.telegram

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PasswordSealTest {
    @Test
    fun `a sealed password opens for the request and keys it was made for`() {
        val tv = PasswordSeal.newTvKeys()
        val sealed = PasswordSeal.seal(tv.publicKey, "req-1", "correct horse ünïcode")
        assertEquals("correct horse ünïcode", PasswordSeal.open(tv, "req-1", sealed))
    }

    @Test
    fun `it does not open for another request, another TV, or after a change`() {
        val tv = PasswordSeal.newTvKeys()
        val sealed = PasswordSeal.seal(tv.publicKey, "req-1", "secret")
        assertNull(PasswordSeal.open(tv, "req-2", sealed))
        assertNull(PasswordSeal.open(PasswordSeal.newTvKeys(), "req-1", sealed))
        val raw = Base64.getDecoder().decode(sealed).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(PasswordSeal.open(tv, "req-1", Base64.getEncoder().encodeToString(raw)))
        assertNull(PasswordSeal.open(tv, "req-1", "not base64 at all"))
    }

    @Test
    fun `sealing twice gives different bytes and the password is not in them`() {
        val tv = PasswordSeal.newTvKeys()
        val a = PasswordSeal.seal(tv.publicKey, "req-1", "hunter2hunter2")
        val b = PasswordSeal.seal(tv.publicKey, "req-1", "hunter2hunter2")
        assertNotEquals(a, b)
        assert(!String(Base64.getDecoder().decode(a), Charsets.ISO_8859_1).contains("hunter2"))
    }

    /** Made by the control plane's Node test implementation: the two ends must agree on the format. */
    @Test
    fun `it opens a password sealed by the other implementation`() {
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode("MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgd6hz1VCMmSJHHZWjlI2PhgxKnPtmy7mkEWmn/jvuOd6hRANCAAS4ZZFtaUkrEOQIcHXzjWiBlfLi6F0yLnoVDFZ6+bK4YiLdRxS5lW3AyTMjSYGSvPu0OdblfqypzKmshPisH+S/")),
        )
        val sealed = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAERfgTGx8Usy9c4fdK0Ok377/Tt6pjRYn81rDsMTvML/X/4qwBM10a/xa6nevKrn6p9VViDyZ+5/Oj3LWdRXy+ZwECAwQFBgcICQoLDKb79ll2gKLoeV98C9NAM73VlUiWXiocskj9BvGd"
        assertEquals("pässword 123", PasswordSeal.open(privateKey, "6f1c2d3e-4a5b-4c6d-8e7f-0a1b2c3d4e5f", sealed))
    }
}
