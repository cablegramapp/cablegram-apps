package app.cablegram.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PairLinkTest {
    @Test
    fun `the QR opens the web remote with the PIN and the TV's name`() {
        assertEquals(
            "https://app.cablegram.app/?pair=123456&tv=Living+room+TV",
            pairLink("https://app.cablegram.app/", "123456", "Living room TV"),
        )
    }

    @Test
    fun `a name with symbols is encoded`() {
        assertEquals("http://localhost:8080/?pair=000042&tv=Sony+%26+Co+%2F+4K", pairLink("http://localhost:8080", "000042", "Sony & Co / 4K"))
    }
}
