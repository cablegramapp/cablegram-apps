package app.cablegram.player

import android.util.Log
import androidx.activity.compose.setContent
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cablegram.MainActivity
import app.cablegram.data.PlaybackResponse
import app.cablegram.ui.PlayerScreen
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A manual fault-journey harness (CAB-15), not an assertion test: it hosts the real [PlayerScreen] on a source the
 * driver controls and logs what the screen reports, so a script can inject faults (silent source, frozen stream,
 * dropped network) and watch the screen with `uiautomator dump` and logcat (tags FaultHarness, CableGramPlayback).
 *
 * Arguments: `-e url <primary>`, optional `-e fallback <url>`, `-e resume <seconds>`, `-e hold <seconds>` (default 120).
 * It ends when the screen calls `onBack`, or after `hold` seconds.
 */
@RunWith(AndroidJUnit4::class)
class PlayerFaultHarnessTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun hostThePlayerScreenOnAControlledSource() {
        val args = InstrumentationRegistry.getArguments()
        val url = requireNotNull(args.getString("url")) { "pass -e url <source url>" }
        val fallback = args.getString("fallback")
        val resume = args.getString("resume")?.toIntOrNull()
        val hold = args.getString("hold")?.toLongOrNull() ?: 120L
        val finished = CountDownLatch(1)
        val tag = "FaultHarness"
        activityRule.scenario.onActivity { activity ->
            activity.setContent {
                PlayerScreen(
                    videoId = "fault-journey",
                    title = "Fault journey",
                    playback = PlaybackResponse(
                        status = "ready", url = url, mimeType = "video/mp4", fallbackUrl = fallback,
                        resumePositionSeconds = resume,
                    ),
                    durationSeconds = 240,
                    onState = { position, duration, playing, volume, muted, engine ->
                        Log.i(tag, "onState position=${position}s duration=$duration playing=$playing engine=$engine")
                    },
                    remoteCommand = null,
                    remoteTitleCommandId = if (resume != null) "harness" else null,
                    onRemoteCommandConsumed = { _, _ -> },
                    onRemotePlaybackResult = { },
                    onPlayerEvent = { event, position, duration -> Log.i(tag, "player event=$event position=$position duration=$duration") },
                    onRenewPlayback = { null },
                    onBack = { Log.i(tag, "onBack called"); finished.countDown() },
                )
            }
        }
        Log.i(tag, "hosting $url (fallback=$fallback resume=$resume hold=${hold}s)")
        finished.await(hold, TimeUnit.SECONDS)
        Log.i(tag, "harness finished (back=${finished.count == 0L})")
    }
}
