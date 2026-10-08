package app.cablegram.ui

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.VideoView
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cablegram.CablegramViewModel
import app.cablegram.MainActivity
import app.cablegram.TvTelegramStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TelegramPasswordChoiceTest {
    @get:Rule val activityRule = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** A bounded UI host for an external Maestro flow; callbacks use no real account. */
    @Test fun hostChoiceForMaestro() {
        val url = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(url)
        val finished = java.util.concurrent.CountDownLatch(1)
        activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[CablegramViewModel::class.java].enterDemoMode()
            activity.setContent {
                CablegramTheme {
                    if (InstrumentationRegistry.getArguments().getString("settings") == "true") {
                        androidx.activity.compose.BackHandler { finished.countDown() }
                        AboutSettingsPanel(isDemoMode = true, tvLanIp = null, telegramStatus = TvTelegramStatus.NeedsPassword("A test hint"))
                    } else {
                        TelegramPasswordScreen(
                            status = TvTelegramStatus.NeedsPassword("A test hint"), onPassword = {}, onAskPhone = {},
                            onSignedIn = {}, onPlayThroughPhone = {}, onBack = { finished.countDown() }, passwordVideoUrl = url!!,
                        )
                    }
                }
            }
        }
        assertTrue("Maestro completes before the 120-second host deadline", finished.await(120, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test fun chooseKeyboardWithoutAutomaticPhonePromptAndKeepSignatureLooping() {
        val url = InstrumentationRegistry.getArguments().getString("url")
        assumeNotNull(url)
        val status = mutableStateOf<TvTelegramStatus>(TvTelegramStatus.NeedsPassword("A test hint"))
        val clip = mutableStateOf(url!!)
        val requests = AtomicInteger()
        val cancellations = AtomicInteger()
        val submissions = AtomicReference<String?>()
        val signedIn = AtomicInteger()
        activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[CablegramViewModel::class.java].enterDemoMode()
            activity.setContent {
                CablegramTheme {
                    TelegramPasswordScreen(
                        status = status.value, onPassword = { submissions.set(it) }, onAskPhone = { requests.incrementAndGet() },
                        onCancelPasswordRequest = { cancellations.incrementAndGet() }, onSignedIn = { signedIn.incrementAndGet() },
                        onPlayThroughPhone = {}, onBack = {}, passwordVideoUrl = clip.value,
                    )
                }
            }
        }
        waitUntil { findText("TV keyboard") != null && video()?.isPlaying == true }
        assertEquals(0, requests.get())
        assertNull(submissions.get())
        screenshot("keyboard-choice.png")
        val retained = video()!!
        var previous = 0
        var looped = false
        waitUntil(15_000) {
            val position = retained.currentPosition
            if (position < previous - 1000) looped = true
            previous = position
            looped && retained.isPlaying
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { requests.get() == 1 && findText("Type on your phone") != null }
        assertEquals("Entering phone mode must not immediately cancel its request", 0, cancellations.get())
        screenshot("phone-keyboard.png")
        activityRule.scenario.onActivity { status.value = TvTelegramStatus.NeedsPassword("A test hint", busy = true) }
        SystemClock.sleep(250)
        assertEquals(1, requests.get())
        activityRule.scenario.onActivity { status.value = TvTelegramStatus.NeedsPassword("A test hint") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil { findText("TV keyboard") != null && cancellations.get() == 1 }
        assertSame("Choice changes keep the same native signature player", retained, video())
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        waitUntil { findText("Type with the TV keyboard") != null }
        SystemClock.sleep(700)
        screenshot("tv-keyboard.png")
        instrumentation.sendStringSync("password-qa")
        // Physical Enter on the focused single-line input activates its Done action.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        waitUntil { submissions.get() == "password-qa" }
        assertEquals(1, requests.get())
        // A missing decorative clip must leave both keyboard choices usable.
        click("Choose keyboard")
        waitUntil { findText("Phone keyboard") != null }
        activityRule.scenario.onActivity { clip.value = "file:///missing-password-clip.mp4" }
        waitUntil { video() == null }
        click("Phone keyboard")
        waitUntil { requests.get() == 2 }
        activityRule.scenario.onActivity { status.value = TvTelegramStatus.Connected("QA", null) }
        waitUntil { signedIn.get() == 1 && cancellations.get() == 2 }
        assertFalse(retained.isPlaying)
    }

    private fun video(): VideoView? {
        var found: VideoView? = null
        activityRule.scenario.onActivity { found = findVideo(it.window.decorView) }
        return found
    }
    private fun findVideo(view: View): VideoView? {
        if (view is VideoView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findVideo(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun findText(text: String): AccessibilityNodeInfo? = findText(instrumentation.uiAutomation.rootInActiveWindow, text)
    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) findText(node.getChild(i), text)?.let { return it }
        return null
    }
    private fun click(text: String) {
        var node = findText(text) ?: error("Missing $text")
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun waitUntil(timeout: Long = 6000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail("Expected condition within $timeout ms")
    }
    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
