package app.cablegram.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cablegram.CablegramViewModel
import app.cablegram.MainActivity
import app.cablegram.ScreenState
import app.cablegram.data.demoLibraryVideos
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the real view model and screen wiring with an isolated account and a controlled brand clip. */
@RunWith(AndroidJUnit4::class)
class SignatureJourneysTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test fun readyMovieWaitsForTheSignatureAndOpeningCanBeSkippedRepeatedly() {
        val clip = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(clip)
        val store = ViewModelStore()
        lateinit var model: CablegramViewModel
        activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return CablegramViewModel(activity.application as Application, { clip!! }) as T
                }
            })[CablegramViewModel::class.java]
            activity.setContent { CablegramTheme { CablegramApp(model) } }
        }
        try {
            waitUntil(30_000) { model.screen !is ScreenState.Loading }
            // The library can be available under the intro, but cannot bypass it.
            activityRule.scenario.onActivity {
                model.enterDemoMode()
                model.showOpeningSignature()
            }
            val openedAt = SystemClock.elapsedRealtime()
            waitUntil(30_000) { model.openingSignature == null }
            assertTrue("The 10-second opening clip must finish, not fail open", SystemClock.elapsedRealtime() - openedAt >= 9500)
            Log.i("SignatureJourneys", "opening completed automatically")
            activityRule.scenario.onActivity {
                model.showOpeningSignature()
                val first = model.openingSignature!!
                model.showOpeningSignature()
                assertNotEquals(first.id, model.openingSignature!!.id)
                model.finishOpeningSignature(first.id)
                assertNotNull(model.openingSignature)
                model.finishOpeningSignature(model.openingSignature!!.id)
                assertNull(model.openingSignature)
                model.play(demoLibraryVideos().first())
                assertTrue(model.screen is ScreenState.Resolving)
            }
            val preparingAt = SystemClock.elapsedRealtime()
            // A ready demo URL must remain behind the preparing screen while the brand video plays.
            SystemClock.sleep(1500)
            activityRule.scenario.onActivity { assertTrue(model.screen is ScreenState.Resolving) }
            waitUntil(30_000) { model.screen is ScreenState.Player }
            assertTrue("Ready playback waits for a full 10-second signature", SystemClock.elapsedRealtime() - preparingAt >= 9500)
            Log.i("SignatureJourneys", "ready movie waited for a real signature cycle")
            // Cancellation during the gate cannot be undone by a late completion from that clip.
            activityRule.scenario.onActivity {
                model.play(demoLibraryVideos().first())
                val oldAttempt = (model.screen as ScreenState.Resolving).signatureAttemptId
                model.cancelResolving()
                model.finishPlaybackSignature(oldAttempt)
                assertEquals(ScreenState.Library, model.screen)
                model.showOpeningSignature()
            }
            SystemClock.sleep(500)
            activityRule.scenario.onActivity { activity ->
                activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
                activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
            }
            waitUntil(2000) { model.openingSignature == null }
            Log.i("SignatureJourneys", "PASS opening, ready playback, cancellation, repeated opening")
        } finally {
            activityRule.scenario.onActivity { store.clear() }
        }
    }

    @Test fun openingShowsTheClipAndMissingClipsDoNotBlockReadyPlayback() {
        var clip = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(clip)
        val store = ViewModelStore()
        lateinit var model: CablegramViewModel
        activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(store, object : ViewModelProvider.Factory {
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return CablegramViewModel(activity.application, { clip!! }) as T
                }
            })[CablegramViewModel::class.java]
            activity.setContent { CablegramTheme { CablegramApp(model) } }
        }
        try {
            waitUntil(30_000) { model.screen !is ScreenState.Loading }
            activityRule.scenario.onActivity { model.enterDemoMode(); model.showOpeningSignature() }
            SystemClock.sleep(2000)
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val target = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "opening-playing.png")
            target.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            activityRule.scenario.onActivity {
                model.finishOpeningSignature(model.openingSignature!!.id)
                clip = "file:///does-not-exist-signature.mp4"
                model.showOpeningSignature()
            }
            waitUntil(5000) { model.openingSignature == null }
            activityRule.scenario.onActivity { model.play(demoLibraryVideos().first()) }
            waitUntil(5000) { model.screen is ScreenState.Player }
            Log.i("SignatureJourneys", "PASS missing signature did not block opening or ready playback")
        } finally {
            activityRule.scenario.onActivity { store.clear() }
        }
    }

    private fun waitUntil(timeout: Long, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        var result = false
        while (!result && SystemClock.elapsedRealtime() < deadline) {
            activityRule.scenario.onActivity { result = predicate() }
            if (!result) SystemClock.sleep(100)
        }
        assertTrue("Expected state within ${timeout}ms", result)
    }
}
