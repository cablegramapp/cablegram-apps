package app.cablegram.ui

import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import app.cablegram.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hosts the one-time "This TV is connected" card and presses OK with the remote's key: "Start watching" must hold
 * focus, so a single OK closes it. Optional -e holdMs <ms> keeps the card up first, for a screenshot.
 */
@RunWith(AndroidJUnit4::class)
class ConnectedWelcomeHarnessTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun okOnTheRemoteClosesTheCard() {
        val holdMs = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull() ?: 0L
        val dismissed = CountDownLatch(1)
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CablegramTheme {
                    var showing by remember { mutableStateOf(true) }
                    Text(if (showing) "Library behind the card" else "Card closed")
                    ConnectedWelcomeDialog(showing) { showing = false; dismissed.countDown() }
                }
            }
        }
        // Focus is requested a frame after the dialog appears.
        SystemClock.sleep(1_500 + holdMs)
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue("OK did not close the card: Start watching had no focus", dismissed.await(5, TimeUnit.SECONDS))
    }
}
