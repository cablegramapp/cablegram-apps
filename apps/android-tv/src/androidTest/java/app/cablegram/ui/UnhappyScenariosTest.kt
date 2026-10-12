package app.cablegram.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.VideoView
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.tv.material3.Text
import app.cablegram.MainActivity
import app.cablegram.data.PlaybackResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled HTTP faults on the real player UI; no paired account or production API is used. */
@RunWith(AndroidJUnit4::class)
class UnhappyScenariosTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun allErrorClipsDecodeAndRecoveryRemainsUsableAcrossLoopsAndMediaFailure() {
        val first = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(first)
        val clips = listOf(first!!, first.replace("error-1", "error-2"), first.replace("error-1", "error-3"))
        val current = mutableStateOf(clips.first())
        val visible = mutableStateOf(true)
        val retries = AtomicInteger()
        activityRule.scenario.onActivity { activity ->
            androidx.lifecycle.ViewModelProvider(activity)[app.cablegram.CablegramViewModel::class.java].enterDemoMode()
            activity.setContent {
                CablegramTheme {
                    if (visible.value) ErrorScreen(
                        title = "Your phone is offline",
                        message = "Open Cablegram on your phone and check its internet connection, then try again.",
                        onRetry = { retries.incrementAndGet() }, onRemove = null, onConvert = null, onSignOut = null,
                        errorVideoUrl = current.value,
                    ) else Text("Recovered")
                }
            }
        }
        val firstView = playingVideo()
        screenshot("error-playing.png")
        var previous = 0
        var looped = false
        waitUntil(15_000) {
            val position = firstView.currentPosition
            if (position < previous - 1000) looped = true
            previous = position
            looped && firstView.isPlaying
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil(2000) { retries.get() == 1 }
        var previousView = firstView
        for (clip in clips.drop(1)) {
            activityRule.scenario.onActivity { current.value = clip }
            previousView = playingVideo(previousView)
        }
        activityRule.scenario.onActivity { current.value = "file:///missing-error-signature.mp4" }
        waitUntil(4000) { findVideo() == null }
        screenshot("error-missing-clip.png")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil(2000) { retries.get() == 2 }
        activityRule.scenario.onActivity { visible.value = false }
        waitUntil(2000) { !firstView.isPlaying }
        if (InstrumentationRegistry.getArguments().getString("seedAccount") == "true") {
            assertEquals("http://127.0.0.1:1/", app.cablegram.BuildConfig.API_BASE)
            assertTrue(instrumentation.targetContext.packageName.endsWith(".unhappyqa"))
            kotlinx.coroutines.runBlocking {
                app.cablegram.data.AuthStore(instrumentation.targetContext).saveAccount(
                    app.cablegram.data.AccountCredential(sessionId = "offline-qa", token = "offline-qa-invalid", userId = "offline-qa"),
                )
            }
        }
    }

    @Test fun failedLanAndRelayShowWarningThenErrorAndRemoteRetryRecovers() {
        val clip = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(clip)
        val media = File(android.net.Uri.parse(clip).path!!).readBytes()
        val server = MockWebServer()
        val healthy = AtomicBoolean(false)
        val relayRequests = AtomicInteger()
        val playing = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (!request.path.orEmpty().startsWith("/relay/")) return MockResponse().setResponseCode(503)
                relayRequests.incrementAndGet()
                if (!healthy.get()) return MockResponse().setResponseCode(502)
                    .setBody("{\"error\":\"phone_offline\"}").setHeadersDelay(5, TimeUnit.SECONDS)
                return MockResponse().setHeader("Content-Type", "video/mp4")
                    .setBody(Buffer().write(media)).setHeadersDelay(2, TimeUnit.SECONDS)
            }
        }
        server.start()
        val primaryUrl = server.url("/lan/media.mp4").toString()
        val relayUrl = server.url("/relay/v1/p/fixture/media/file").toString()
        try {
            activityRule.scenario.onActivity { activity ->
                activity.setContent {
                    CablegramTheme {
                        PlayerScreen(
                            videoId = "unhappy-fault", title = "Controlled recovery",
                            playback = PlaybackResponse(status = "ready", url = primaryUrl,
                                fallbackUrl = relayUrl, mimeType = "video/mp4"),
                            onState = { _, _, isPlaying, _, _, _ -> if (isPlaying) playing.set(true) },
                            remoteCommand = null, remoteTitleCommandId = null,
                            onRemoteCommandConsumed = { _, _ -> }, onRemotePlaybackResult = {},
                            onPlayerEvent = { _, _, _ -> }, onRenewPlayback = { null }, onBack = {},
                            errorVideoUrl = clip,
                        )
                    }
                }
            }
            val warningView = playingVideo()
            screenshot("lan-relay-warning.png")
            waitUntil(12_000) { !warningView.isPlaying && relayRequests.get() >= 1 }
            val errorView = playingVideo()
            screenshot("relay-offline-error.png")
            val countBeforeRetry = relayRequests.get()
            healthy.set(true)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
            waitUntil(20_000) { playing.get() && relayRequests.get() > countBeforeRetry }
            assertFalse("Error signature released when Retry starts recovery", errorView.isPlaying)
            screenshot("recovered-playback.png")
        } finally {
            activityRule.scenario.onActivity { it.setContent { Text("Finished") } }
            server.shutdown()
        }
    }

    private fun playingVideo(excluding: VideoView? = null): VideoView {
        lateinit var video: VideoView
        waitUntil(6000) {
            val candidate = findVideo()
            if (candidate !== excluding && candidate?.isPlaying == true && candidate.currentPosition > 300) { video = candidate; true } else false
        }
        return video
    }
    private fun findVideo(): VideoView? {
        var found: VideoView? = null
        activityRule.scenario.onActivity { found = findVideo(it.window.decorView) }
        return found
    }
    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findVideo(view.getChildAt(index))?.let { return it }
        return null
    }
    private fun waitUntil(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var matched = false
        while (!matched && SystemClock.elapsedRealtime() < deadline) {
            activityRule.scenario.onActivity { matched = predicate() }
            if (!matched) SystemClock.sleep(100)
        }
        assertTrue("Expected condition within $timeout ms", matched)
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.filesDir, name).outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
