package app.cablegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramPasswordStatusTest {
    @Test
    fun `a password on its way keeps the step busy when Telegram asks again`() {
        val shown = TvTelegramStatus.NeedsPassword("hint", busy = false)
        assertTrue(passwordStatus("hint", shown, checking = true).busy)
        assertTrue(passwordStatus("hint", TvTelegramStatus.Connecting, checking = true).busy)
    }

    @Test
    fun `without a password the step asks for a keyboard`() {
        assertFalse(passwordStatus("hint", TvTelegramStatus.Connecting, checking = false).busy)
    }

    @Test
    fun `an open phone prompt keeps its code and message`() {
        val before = TvTelegramStatus.NotSignedIn(twoStep = true, phoneRequestError = "offline", phoneCode = "1234", phoneSentAt = 42L)
        val after = passwordStatus("hint", before, checking = false)
        assertEquals("1234", after.phoneCode)
        assertEquals(42L, after.phoneSentAt)
        assertEquals("offline", after.phoneRequestError)
        assertEquals("hint", after.hint)
        assertNull(passwordStatus("hint", TvTelegramStatus.Connecting, checking = false).phoneCode)
    }
}
