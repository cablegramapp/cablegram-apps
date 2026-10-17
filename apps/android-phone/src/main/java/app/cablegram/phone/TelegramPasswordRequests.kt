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
import kotlinx.coroutines.withContext

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
        val ui = context.deviceLanguageResources()
        if (PrivateApprovals.appVisible) runCatching { context.startActivity(screen(context, request)) }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, ui.getString(R.string.ui_telegram_password_for_tvs_3e1cd9), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(context, 31, screen(context, request), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getBroadcast(
            context, 32,
            Intent(context, TelegramPasswordReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_REQUEST, request.requestId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(context)
        val title = if (failed) ui.getString(R.string.ui_couldn_t_send_the_password_to_88150f, request.tvName) else ui.getString(R.string.ui_needs_your_telegram_password_15b274, request.tvName)
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
            .setContentText(ui.getString(R.string.ui_tap_to_type_it_in_cablegram_it_s_encrypted_for_that_tv_e9550f))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, ui.getString(R.string.ui_enter_password_7014af), open).build())
            .addAction(Notification.Action.Builder(null, ui.getString(R.string.ui_cancel_19766e), cancel).build())
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/**
 * TVs this phone just sent the Telegram password to (CAB-73). Typing the password for a TV is the owner agreeing that it
 * signs in, so its sign-in that follows within [WINDOW_MS] is approved without a second "Let <TV> use your Telegram?".
 * Only for the TV the server named in that request, never by name; held in memory only, so it ends with the process.
 */
object RecentTvPasswords {
    internal const val WINDOW_MS = 2 * 60_000L
    private val sentAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun sent(tvDeviceId: String, now: Long = System.currentTimeMillis()) {
        sentAt[tvDeviceId] = now
    }

    fun covers(tvDeviceId: String, now: Long = System.currentTimeMillis()): Boolean {
        val at = sentAt[tvDeviceId] ?: return false
        if (now - at in 0 until WINDOW_MS) return true
        sentAt.remove(tvDeviceId, at)
        return false
    }

    /** Used up by the approval it allowed: a later sign-in of that TV asks again. */
    fun used(tvDeviceId: String) {
        sentAt.remove(tvDeviceId)
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
        setContent { PhoneLanguage { PhoneTheme { PasswordScreen(requestId, tvName) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // A newer request (after a wrong password, say) replaces the one on screen.
        setIntent(intent)
        recreate()
    }

    @androidx.compose.runtime.Composable
    private fun PasswordScreen(requestId: String, tvName: String) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
        val context = this
        val scope = rememberCoroutineScope()
        var password by remember { mutableStateOf("") }
        var hint by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var code by remember { mutableStateOf<String?>(null) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            focus.requestFocus()
            // The check code of the key this password will be sealed to; the TV shows the same code for its own key.
            code = withContext(Dispatchers.IO) {
                runCatching {
                    val store = PairingStore(context)
                    val token = store.accountToken ?: return@runCatching null
                    TelegramPasswordRequests.requestFor(CatalogClient(store.apiBaseUrl, store).pendingTvPasswordRequests(token), requestId)
                        ?.let { PasswordSeal.checkCode(it.tvPublicKey) }
                }.getOrNull()
            }
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
                    error = ui.getString(R.string.ui_no_longer_needs_the_password_9cb267, tvName)
                    sending = false
                    return@launch
                }
                val sent = runCatching { client.sealTvPassword(token, requestId, PasswordSeal.seal(request.tvPublicKey, requestId, typed)) }
                    .getOrDefault(false)
                // Never log the password or the sealed bytes: only whether it went.
                PairLog.i("Telegram password for ${request.tvName}: ${if (sent) "sent" else "not sent"}")
                if (sent) {
                    request.tvDeviceId?.let(RecentTvPasswords::sent)
                    password = ""
                    close()
                } else {
                    error = ui.getString(R.string.ui_couldn_t_send_it_check_your_connection_and_try_again_b8b4ee)
                    sending = false
                }
            }
        }
        Column(
            Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(ui.getString(R.string.ui_telegram_password_65d868), color = androidx.compose.ui.graphics.Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                ui.getString(R.string.ui_is_signing_in_to_your_telegram_type_your_two_step_veri_999fde, tvName),
                color = VlcMuted, fontSize = 15.sp, lineHeight = 21.sp,
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(ui.getString(R.string.ui_password_e7cf3e)) },
                placeholder = hint.takeIf { it.isNotBlank() }?.let { { Text(ui.getString(R.string.ui_hint_3033f3, it)) } },
                singleLine = true,
                enabled = !sending,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Send, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
            code?.let {
                Text(ui.getString(R.string.ui_check_code_9a5af9), color = VlcMuted, fontSize = 13.sp)
                Text(it, color = VlcOrange, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text(ui.getString(R.string.ui_shows_a_code_too_send_your_password_only_if_both_codes_12e5fa, tvName), color = VlcMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            hint.takeIf { it.isNotBlank() }?.let { Text(ui.getString(R.string.ui_hint_3033f3, it), color = VlcMuted, fontSize = 14.sp) }
            error?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFFF8A80), fontSize = 14.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ::send, enabled = password.isNotEmpty() && !sending) { Text(if (sending) ui.getString(R.string.ui_sending_b8ed52) else ui.getString(R.string.ui_send_to_0ddfbe, tvName)) }
                OutlinedButton(onClick = {
                    scope.launch {
                        val store = PairingStore(context)
                        store.accountToken?.let { CatalogClient(store.apiBaseUrl, store).cancelTvPassword(it, requestId) }
                        close()
                    }
                }) { Text(ui.getString(R.string.ui_cancel_19766e)) }
            }
        }
    }
}
