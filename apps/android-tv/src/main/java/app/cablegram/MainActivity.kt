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

    override fun onResume() {
        super.onResume()
        cablegramViewModel.refreshLibraryOnResume()
    }
}
