package app.cablegram.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLanguageTest {
    private fun resolve(profiles: List<String>, selected: String?) =
        tvUiLanguage(profiles, selected, setOf("en", "de", "ar", "fa", "ru", "es", "it"))

    @Test fun `mixed profiles show English until one is selected`() {
        assertEquals("en", resolve(listOf("de-DE", "fa-IR"), null))
        assertEquals("fa", resolve(listOf("de-DE", "fa-IR"), "fa-IR"))
        assertEquals("de", resolve(listOf("de-DE", "fa-IR"), "de-DE"))
        assertEquals("en", resolve(listOf("de-DE", "fa-IR"), null))
    }

    @Test fun `single language works before selection regardless of TV device language`() {
        assertEquals("de", resolve(listOf("de-DE"), null))
        assertEquals("de", resolve(listOf("de-DE", "de-AT"), null))
        assertEquals("en", resolve(emptyList(), null))
        assertEquals("en", resolve(listOf(""), null))
        assertEquals("ar", resolve(listOf("ar-EG", "ar-SA"), null))
        assertEquals("en", resolve(listOf("ar", "fa"), null))
        for (tag in listOf("de", "ar", "fa", "ru", "es", "it")) {
            assertEquals(tag, resolve(listOf("en", tag), tag))
        }
    }

    @Test fun `unsupported language falls back to English`() {
        assertEquals("en", tvUiLanguage(listOf("fa-IR"), "fa-IR"))
        assertEquals("en", resolve(listOf("fr-FR"), "fr-FR"))
    }
}
