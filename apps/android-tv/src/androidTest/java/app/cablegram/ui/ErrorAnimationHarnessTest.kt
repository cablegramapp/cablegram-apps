package app.cablegram.ui

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cablegram.MainActivity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosts the real error screen with no clip URL, so it shows a bundled error animation, and presses OK on Retry:
 * the animation must not take focus from the recovery actions. Optional -e holdMs <ms> keeps it up for screenshots.
 */
@RunWith(AndroidJUnit4::class)
class ErrorAnimationHarnessTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun bundledAnimationLeavesRetryUsable() {
        val holdMs = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull() ?: 0L
        val retries = AtomicInteger()
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CablegramTheme {
                    ErrorScreen(
                        title = "Your phone is offline",
                        message = "Open Cablegram on your phone and check its internet connection, then try again.",
                        onRetry = { retries.incrementAndGet() }, onRemove = null, onConvert = null, onSignOut = null,
                    )
                }
            }
        }
        SystemClock.sleep(2_000 + holdMs)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(500)
        assertEquals(1, retries.get())
    }

    /** Every bundled animation decodes through the app's image loader as an animation, not a still frame. */
    @Test
    fun everyBundledAnimationDecodesAndAnimates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val loader = coil3.SingletonImageLoader.get(context)
        for (res in listOf(app.cablegram.R.raw.error_signal, app.cablegram.R.raw.error_rain, app.cablegram.R.raw.error_frame)) {
            val result = kotlinx.coroutines.runBlocking {
                loader.execute(coil3.request.ImageRequest.Builder(context).data(res).memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build())
            }
            assertTrue("raw/$res did not decode: $result", result is coil3.request.SuccessResult)
            val drawable = (result as coil3.request.SuccessResult).image.let { (it as? coil3.DrawableImage)?.drawable }
            assertTrue("raw/$res decoded as a still image: $drawable", drawable is android.graphics.drawable.Animatable)
        }
    }
}
