package app.cablegram.phone

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubtitlePlaybackDirectionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun persianTimelineRunsLeftToRight() = verifyTimeline("fa")
    @Test fun arabicTimelineRunsLeftToRight() = verifyTimeline("ar")

    private fun verifyTimeline(locale: String) {
        val position = mutableDoubleStateOf(25.0)
        compose.setContent {
            val base = LocalContext.current
            val config = Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(locale))
            }
            CompositionLocalProvider(
                LocalContext provides base.createConfigurationContext(config),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                MaterialTheme {
                    Column {
                        SubtitlePlaybackControls(100_000, position.doubleValue, false, true,
                            onSeek = { position.doubleValue = it / 1000.0 }, onTogglePlayback = {})
                    }
                }
            }
        }
        val slider = compose.onNodeWithTag(MaestroIds.SUBTITLES_SEEK)
        // A physical quarter-width tap must seek near the beginning in an RTL locale.
        slider.performTouchInput { click(Offset(width * 0.25f, centerY)) }
        compose.runOnIdle {
            assertTrue("Physical left quarter should be before midpoint", position.doubleValue in 15.0..35.0)
        }
        val before = position.doubleValue
        val minus = compose.onNodeWithText("−10 s").fetchSemanticsNode().boundsInRoot
        val plus = compose.onNodeWithText("+10 s").fetchSemanticsNode().boundsInRoot
        assertTrue("Rewind must be physically left of forward", minus.center.x < plus.center.x)
        saveScreenshot(locale + "-quarter")
        slider.performTouchInput {
            swipe(Offset(width * 0.25f, centerY), Offset(width * 0.70f, centerY), 500)
        }
        compose.runOnIdle {
            assertTrue("Dragging right must increase playback time", position.doubleValue > before + 20.0)
        }
        saveScreenshot(locale + "-after-right-drag")
    }

    private fun saveScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Capture our Compose content without competing with Maestro's UiAutomation connection.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
