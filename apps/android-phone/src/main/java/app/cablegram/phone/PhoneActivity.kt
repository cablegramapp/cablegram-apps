package app.cablegram.phone

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels

class PhoneActivity : ComponentActivity() {
    private val viewModel: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        val extra = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) extra += Manifest.permission.POST_NOTIFICATIONS
        if (extra.isNotEmpty()) requestPermissions(extra.toTypedArray(), 0)
        handleIncoming(intent)
        setContent {
            PhoneTheme { PhoneApp(viewModel) }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorage()
        viewModel.refreshPendingApprovals()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: android.content.Intent?) {
        intent?.getStringExtra(EXTRA_TAB)?.let { name ->
            PhoneTab.entries.firstOrNull { it.name == name }?.let { viewModel.tab = it }
            return
        }
        val uri = intent?.data?.toString() ?: return
        if (parseStorageReturn(uri) != null) {
            viewModel.applyStorageReturn(uri)
            return
        }
        viewModel.applyPairUri(uri)
    }

    companion object {
        private const val EXTRA_TAB = "open_tab"

        /** Tap target for notifications: bring the app forward, optionally on a tab. */
        fun openIntent(context: Context, tab: PhoneTab? = null): PendingIntent {
            val intent = Intent(context, PhoneActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            tab?.let { intent.putExtra(EXTRA_TAB, it.name) }
            return PendingIntent.getActivity(
                context, tab?.ordinal ?: -1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
