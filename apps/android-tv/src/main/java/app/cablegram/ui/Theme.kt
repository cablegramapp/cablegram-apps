package app.cablegram.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Ink = Color(0xFF0A0B0E)
val InkDeep = Color(0xFF050608)
val Panel = Color(0xFF17191E)
val PanelRaised = Color(0xFF22252C)
val PanelSoft = Color(0xFF111318)
val Cyan = Color(0xFFF4BD75)
val Sky = Color(0xFFFFD39A)
val Slate = Color(0xFF9DA1AB)
val Acid = Cyan
val Coral = Color(0xFFFF806F)
val Aqua = Sky
val Paper = Color(0xFFF8FAFC)
val Muted = Slate
/** Neutral fill for focused or selected controls on dark panels. */
val FocusFill = Color(0xFF2C2F37)
/** Hairline outlines and empty progress tracks. */
val Outline = Color(0xFF3A3E47)
/** Soft warning text (wrong PIN, failed action). */
val Warning = Color(0xFFEBA77A)

/**
 * Deterministic artwork for titles without a poster: personal videos often
 * have none, and identical blank tiles made a row impossible to tell apart.
 */
internal fun placeholderGradient(seed: String): List<Color> {
    val pairs = listOf(
        Color(0xFF8A5A2B) to Color(0xFF1E140C),
        Color(0xFF2D6B66) to Color(0xFF0C1A19),
        Color(0xFF6E3558) to Color(0xFF1A0D16),
        Color(0xFF3B4F8A) to Color(0xFF0E1322),
        Color(0xFF6B6A2E) to Color(0xFF19180B),
        Color(0xFF8A3F32) to Color(0xFF1F0E0B),
        Color(0xFF4A3F7A) to Color(0xFF110F1F),
        Color(0xFF2F6B45) to Color(0xFF0B1A10),
    )
    // String.hashCode() clusters for similar titles; mix it before bucketing.
    val mixed = (seed.hashCode() * -0x61c88647) ushr 16
    val (top, bottom) = pairs[mixed % pairs.size]
    return listOf(top, bottom)
}

/** One or two letters that identify a title on its placeholder artwork. */
internal fun monogram(title: String): String {
    val words = title.split(' ', '-', '_', '.', ':').filter { it.firstOrNull()?.isLetterOrDigit() == true }
    return when {
        words.isEmpty() -> "C"
        words.size == 1 -> words[0].take(2).uppercase()
        else -> "${words[0].first()}${words[1].first()}".uppercase()
    }
}

private val CablegramColors = darkColorScheme(
    primary = Cyan,
    onPrimary = Ink,
    secondary = Sky,
    onSecondary = Ink,
    tertiary = Coral,
    background = Ink,
    onBackground = Paper,
    surface = Panel,
    onSurface = Paper,
    surfaceVariant = PanelRaised,
    onSurfaceVariant = Slate,
    error = Coral,
)

@Composable
fun CablegramTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CablegramColors, content = content)
}
