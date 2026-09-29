package app.cablegram.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Shared phone tokens. Keep the legacy names so every existing flow adopts the palette.
val VlcOrange = Color(0xFFF4BD75)
val VlcBlack = Color(0xFF101114)
val VlcPanel = Color(0xFF1B1D22)
val VlcMuted = Color(0xFFAAADB7)
val PhoneOutline = Color(0xFF34363E)
val PhoneSuccess = Color(0xFFA5CBB1)

private val PhoneTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-1).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
)

@Composable
fun PhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VlcOrange,
            onPrimary = Color.Black,
            background = VlcBlack,
            onBackground = Color.White,
            surface = VlcPanel,
            onSurface = Color.White,
            surfaceVariant = VlcPanel,
            onSurfaceVariant = VlcMuted,
            outline = PhoneOutline,
            outlineVariant = PhoneOutline,
            primaryContainer = Color(0xFF3D3022),
            onPrimaryContainer = VlcOrange,
            secondary = PhoneSuccess,
            secondaryContainer = Color(0xFF28372F),
            onSecondaryContainer = PhoneSuccess,
            error = Color(0xFFFFB4AB),
            surfaceContainer = VlcPanel,
            surfaceContainerHigh = Color(0xFF25272E),
        ),
        typography = PhoneTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
        ),
        content = {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).maestroRoot()) {
                content()
            }
        },
    )
}
