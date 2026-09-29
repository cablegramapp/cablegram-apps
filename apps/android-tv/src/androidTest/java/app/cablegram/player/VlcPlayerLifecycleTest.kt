package app.cablegram.player

import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cablegram.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guards LibVLC's process-wide engine / per-session player lifecycle contract. */
@RunWith(AndroidJUnit4::class)
class VlcPlayerLifecycleTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun repeatedlyOpeningAndClosingSessionsReattachesTheVlcSurface() {
        val finished = CountDownLatch(1)
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                var session by remember { mutableIntStateOf(0) }
                val player = remember(session) { VlcCableGramPlayer(activity) }
                DisposableEffect(player) { onDispose { player.release() } }
                player.VideoSurface(Modifier, showController = false)
                LaunchedEffect(session) {
                    player.load(PlayerSource("https://example.invalid/session-$session.mkv", 0))
                    if (session == 19) finished.countDown() else session++
                }
            }
        }
        assertTrue("Timed out switching LibVLC playback sessions", finished.await(30, TimeUnit.SECONDS))
    }
}
