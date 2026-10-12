package app.cablegram

import android.os.Bundle
import android.content.Intent
import android.annotation.SuppressLint
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.cablegram.ui.CablegramApp
import app.cablegram.ui.CablegramTheme

class MainActivity : ComponentActivity() {
    private var playerKeyHandler: ((KeyEvent) -> Boolean)? = null
    private val cablegramViewModel: CablegramViewModel by viewModels()

    fun setPlayerKeyHandler(handler: ((KeyEvent) -> Boolean)?) {
        playerKeyHandler = handler
    }

    // Override the public android.app.Activity callback to route physical TV keys
    // before focused Compose children. AndroidX annotates its inherited implementation.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playerKeyHandler?.invoke(event) == true) return true
        if (cablegramViewModel.onReviewerBypassKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app.cablegram.data.SignatureClips.init(this)
        if (savedInstanceState == null && openingDue(intent)) cablegramViewModel.showOpeningSignature()
        cablegramViewModel.attachCastReceiver()
        (application as? app.cablegram.cast.CablegramApplication)?.handleIntent(intent)
        enableEdgeToEdge()
        setContent {
            CablegramTheme {
                CablegramApp(viewModel = cablegramViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        (application as? app.cablegram.cast.CablegramApplication)?.handleIntent(intent)
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * The opening clip greets a cold launch from the home screen, at most once a day. Returning to a running app, a
     * Cast launch or a deep link goes straight in; replaying it would also tear down a title that is playing.
     */
    private fun openingDue(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_MAIN) return false
        val prefs = getSharedPreferences("signature", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("opening_shown_at", 0L)
        if (last in (now - OPENING_INTERVAL_MS + 1)..now) return false
        prefs.edit().putLong("opening_shown_at", now).apply()
        return true
    }

    private companion object {
        const val OPENING_INTERVAL_MS = 20L * 60 * 60 * 1000
    }

    override fun onResume() {
        super.onResume()
        cablegramViewModel.refreshLibraryOnResume()
    }
}
