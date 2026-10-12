package app.cablegram.ui

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import app.cablegram.MainActivity
import app.cablegram.ScreenState
import app.cablegram.data.Video
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Host the real approval screen for a bounded remote/UI check on an isolated installation.
 * Pass -e url <10-second signature clip>; drive Back after two loops, within 60 seconds.
 * Optional -e autoBackAfterLoops 2 sends the real Back key after the observed loop boundaries.
 * Checks actual decoder progress across loop boundaries and release when Back removes the screen.
 * This checks the screen in isolation; it does not simulate phone approval or backend polling.
 */
@RunWith(AndroidJUnit4::class)
class PrivateApprovalScreenHarnessTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun signatureKeepsLoopingUntilTheViewerCancels() {
        val url = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(url)
        val autoBackAfterLoops = InstrumentationRegistry.getArguments().getString("autoBackAfterLoops")?.toIntOrNull()
        val cancelled = CountDownLatch(1)
        var retainedVideo: VideoView? = null
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CablegramTheme {
                    var showing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
                    if (showing) {
                        PrepareStatusScreen(
                            ScreenState.Resolving(
                                video = Video(id = "approval-harness", title = "A private title with a longer name for the TV approval screen"),
                                loadingVideoUrl = url,
                                awaitingApproval = true,
                            ),
                            onCancel = { showing = false; cancelled.countDown() },
                        )
                    } else {
                        Text("Approval cancelled")
                    }
                }
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var lastPosition = -1
        var loops = 0
        var lastProgressAt = SystemClock.elapsedRealtime()
        var longestGap = 0L
        while (cancelled.count > 0 && SystemClock.elapsedRealtime() < deadline) {
            var position = -1
            var playing = false
            activityRule.scenario.onActivity { activity ->
                val view = findVideo(activity.window.decorView)
                if (view != null) {
                    retainedVideo = view
                    position = view.currentPosition
                    playing = view.isPlaying
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (position >= 0 && playing && position != lastPosition) {
                if (lastPosition >= 0) longestGap = maxOf(longestGap, now - lastProgressAt)
                if (position < lastPosition - 1000) {
                    loops++
                    Log.i("ApprovalHarness", "loop=$loops longestProgressGapMs=$longestGap")
                }
                lastPosition = position
                lastProgressAt = now
            }
            if (autoBackAfterLoops != null && loops >= autoBackAfterLoops) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            }
            SystemClock.sleep(250)
        }
        assertTrue("Back must cancel within 60 seconds", cancelled.count == 0L)
        assertTrue("Observe at least two signature-video loops before Back", loops >= 2)
        assertTrue("No 10-second replay pause: longest progress gap=$longestGap", longestGap < 2000)
        SystemClock.sleep(500)
        activityRule.scenario.onActivity {
            assertFalse("Removed signature video must stop playing", retainedVideo?.isPlaying == true)
        }
        Log.i("ApprovalHarness", "PASS loops=$loops longestProgressGapMs=$longestGap; Back removed and stopped video")
        // Keep the post-Back screen available long enough for the external UI driver to assert it.
        SystemClock.sleep(8_000)
    }

    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findVideo(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
