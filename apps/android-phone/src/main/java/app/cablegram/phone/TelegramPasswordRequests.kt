package app.cablegram.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cablegram.telegram.TelegramState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

import app.cablegram.telegram.PasswordSeal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A TV that Telegram asks for the two-step password gets it from this phone's keyboard, on a full password screen
 * ([TelegramPasswordActivity]): masked, kept out of screenshots and recents, and only after the phone is unlocked. The
 * screen opens by itself while Cablegram is open; otherwise a notification opens it. The password is sealed to the
 * TV's one-time key before it leaves the phone: the control plane relays bytes it cannot read, and nothing is stored
 * or logged here.
 */
object TelegramPasswordRequests {
    private const val CHANNEL = "telegram_tv_password"
    private const val NOTIFICATION_ID = 7305
    internal const val ACTION_CANCEL = "app.cablegram.phone.telegram.TV_PASSWORD_CANCEL"
    internal const val EXTRA_REQUEST = "request_id"
    internal const val EXTRA_TV_NAME = "tv_name"

    /** The request the user typed for, taken from the server's current list and never from the intent. */
    internal fun requestFor(pending: List<PendingTvPasswordRequest>?, requestId: String): PendingTvPasswordRequest? =
        pending?.firstOrNull { it.requestId == requestId }

    private fun screen(context: Context, request: PendingTvPasswordRequest) =
        Intent(context, TelegramPasswordActivity::class.java)
            .putExtra(EXTRA_REQUEST, request.requestId)
            .putExtra(EXTRA_TV_NAME, request.tvName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    /** Opens the password screen at once when Cablegram is in front; the notification is posted either way. */
    fun ask(context: Context, request: PendingTvPasswordRequest, failed: Boolean = false) {
        if (PrivateApprovals.appVisible) runCatching { context.startActivity(screen(context, request)) }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Telegram password for TVs", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, 31, screen(context, request), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getBroadcast(
            context, 32,
            Intent(context, TelegramPasswordReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_REQUEST, request.requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val title = if (failed) "Couldn't send the password to ${request.tvName}" else "${request.tvName} needs your Telegram password"
        // The lock screen shows only that a TV is waiting; the password screen needs the phone unlocked.
        val publicVersion = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(context))
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setContentTitle(title)
            .build()
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_cablegram)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentTitle(title)
            .setContentText("Tap to type it in Cablegram. It's encrypted for that TV only.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "Enter password", open).build())
            .addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

class TelegramPasswordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelegramPasswordRequests.ACTION_CANCEL) return
        val requestId = intent.getStringExtra(TelegramPasswordRequests.EXTRA_REQUEST) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = PairingStore(context)
                val token = store.accountToken ?: return@launch
                CatalogClient(store.apiBaseUrl, store).cancelTvPassword(token, requestId)
                TelegramPasswordRequests.cancel(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The phone keyboard for a TV's Telegram password: a masked field on a screen that can't be captured. */
class TelegramPasswordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val requestId = intent.getStringExtra(TelegramPasswordRequests.EXTRA_REQUEST) ?: return finish()
        val tvName = intent.getStringExtra(TelegramPasswordRequests.EXTRA_TV_NAME)?.take(64) ?: "your TV"
        setContent { PhoneTheme { PasswordScreen(requestId, tvName) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A newer request (after a wrong password, say) replaces the one on screen.
        setIntent(intent)
        recreate()
    }

    @androidx.compose.runtime.Composable
    private fun PasswordScreen(requestId: String, tvName: String) {
        val context = this
        val scope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var hint by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focus.requestFocus()
            // The hint from this phone's own Telegram session; the TV may not know it before it signs in.
            hint = withTimeoutOrNull(5_000) {
                val session = PhoneTelegram.session(context)
                session.state.first { it is TelegramState.Ready }
                runCatching { session.api.passwordState().hint }.getOrNull()
            }.orEmpty()
        }
        fun close() { TelegramPasswordRequests.cancel(context); finish() }
        fun send() {
            if (password.isEmpty() || sending) return
            sending = true
            error = null
            val typed = password
            scope.launch {
                val store = PairingStore(context)
                val token = store.accountToken
                val client = CatalogClient(store.apiBaseUrl, store)
                val request = token?.let { TelegramPasswordRequests.requestFor(client.pendingTvPasswordRequests(it), requestId) }
                if (token == null || request == null) {
                    // Answered elsewhere, withdrawn or expired.
                    error = "$tvName no longer needs the password."
                    sending = false
                    return@launch
                }
                val sent = runCatching { client.sealTvPassword(token, requestId, PasswordSeal.seal(request.tvPublicKey, requestId, typed)) }
                    .getOrDefault(false)
                // Never log the password or the sealed bytes: only whether it went.
                PairLog.i("Telegram password for ${request.tvName}: ${if (sent) "sent" else "not sent"}")
                if (sent) {
                    password = ""
                    close()
                } else {
                    error = "Couldn't send it. Check your connection and try again."
                    sending = false
                }
            }
        }
        Column(
            Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Telegram password", color = androidx.compose.ui.graphics.Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "$tvName is signing in to your Telegram. Type your two-step verification password. It's encrypted for that TV only; Cablegram can't read it.",
                color = VlcMuted, fontSize = 15.sp, lineHeight = 21.sp,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text("Password") },
                placeholder = hint.takeIf { it.isNotBlank() }?.let { { Text("Hint: $it") } },
                singleLine = true,
                enabled = !sending,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            hint.takeIf { it.isNotBlank() }?.let { Text("Hint: $it", color = VlcMuted, fontSize = 14.sp) }
            error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::send, enabled = password.isNotEmpty() && !sending) { Text(if (sending) "Sending…" else "Send to $tvName") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val store = PairingStore(context)
                        store.accountToken?.let { CatalogClient(store.apiBaseUrl, store).cancelTvPassword(it, requestId) }
                        close()
                    }
                }) { Text("Cancel") }
            }
        }
    }
}
