package app.cablegram.phone

import androidx.compose.ui.unit.TextUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneTypographyTest {
    @Test fun latinHeadlinesStayTightlySpaced() {
        val t = phoneTypography(joinedScript = false)
        assertEquals(-1f, t.headlineLarge.letterSpacing.value, 0f)
        assertEquals(-0.6f, t.headlineMedium.letterSpacing.value, 0f)
        assertEquals(-0.5f, t.headlineSmall.letterSpacing.value, 0f)
    }

    @Test fun joinedScriptsGetNoLetterSpacingAtAll() {
        val t = phoneTypography(joinedScript = true)
        listOf(t.headlineLarge, t.headlineMedium, t.headlineSmall).forEach { assertTrue(it.letterSpacing == TextUnit.Unspecified) }
        // Everything else about the styles is unchanged.
        assertEquals(34f, t.headlineLarge.fontSize.value, 0f)
        assertEquals(phoneTypography(false).bodyLarge, t.bodyLarge)
    }
}
