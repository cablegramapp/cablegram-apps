package app.cablegram.ui

import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import app.cablegram.R
import app.cablegram.data.PRIVACY_POLICY_URL

@Composable
internal fun AboutSettingsPanel(
    isDemoMode: Boolean,
    tvLanIp: String?,
    telegramStatus: app.cablegram.TvTelegramStatus = app.cablegram.TvTelegramStatus.Off,
    onTelegramPassword: (String) -> Unit = {},
    onTelegramConnect: () -> Unit = {},
    onTelegramCancel: () -> Unit = {},
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(top = 18.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1.1f)
                .background(PanelSoft, RoundedCornerShape(18.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("About Cablegram", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.app_tagline), color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.legal_disclaimer),
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            if (isDemoMode) {
                Text(
                    "Demo mode · royalty-free sample streams",
                    color = Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                colors = ButtonDefaults.colors(containerColor = PanelRaised),
            ) {
                Text("Open privacy policy")
            }
            Text(PRIVACY_POLICY_URL, color = Slate, fontSize = 12.sp)
            var showLegal by remember { mutableStateOf(false) }
            Button(
                onClick = { showLegal = !showLegal },
                colors = ButtonDefaults.colors(containerColor = PanelRaised),
            ) {
                Text(if (showLegal) "Hide licence and notices" else "Licence and notices")
            }
            if (showLegal) {
                val notice = remember { LegalText.notice(context) }
                Text(
                    notice,
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(.9f)
                .background(PanelSoft, RoundedCornerShape(18.dp))
                // The Telegram section can add a password field or a QR code; scroll rather than clip.
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Connection", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SettingValue("This TV", tvLanIp ?: "Waiting for Wi-Fi", monospace = true)
            SettingValue("Media source", "Your paired phone")
            SettingValue("Playback path", "Wi‑Fi first, relay when away")
            Text(
                "Same Wi‑Fi gives the fastest start and best quality. Elsewhere, Cablegram streams through its relay.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            TelegramSettingsSection(telegramStatus, onTelegramPassword, onTelegramConnect, onTelegramCancel)
        }
    }
}

@Composable
private fun SettingValue(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label.uppercase(), color = Slate, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        Text(
            value,
            color = Paper,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/** Spec 004 US2: the TV's Telegram status; a QR code if the household phone hasn't approved it within 20 s. */
@Composable
private fun TelegramSettingsSection(
    status: app.cablegram.TvTelegramStatus,
    onPassword: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
) {
    if (status == app.cablegram.TvTelegramStatus.Off) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status is app.cablegram.TvTelegramStatus.WaitingForPhone) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Text("Telegram", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text("Cablegram is an unofficial app, not made or endorsed by Telegram. It uses your own Telegram account.", color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
    when (status) {
        app.cablegram.TvTelegramStatus.Connecting -> Text("Connecting to Telegram…", color = Muted, fontSize = 14.sp)
        app.cablegram.TvTelegramStatus.ThroughPhone ->
            Text("Telegram videos play through your phone on this TV. Nothing from Telegram is stored here.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        is app.cablegram.TvTelegramStatus.Connected ->
            SettingValue("Signed in as", status.name)
        is app.cablegram.TvTelegramStatus.Problem -> Text(status.message, color = Cyan, fontSize = 14.sp, lineHeight = 20.sp)
        app.cablegram.TvTelegramStatus.CanConnect -> {
            Text(
                "Telegram isn't connected for this household yet. You can connect it from this TV: scan a code with the Telegram app on your phone.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Button(onClick = onConnect, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Connect Telegram on this TV") }
        }
        is app.cablegram.TvTelegramStatus.WaitingForPhone -> {
            if (!status.standalone) {
                Text("Waiting for your phone to let this TV use your Telegram…", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (status.standalone || now - status.since > 20_000) {
                Text(
                    "Scan this code in Telegram on your phone: Settings → Devices → Link Desktop Device.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                val qr = remember(status.link) { createQrBitmap(status.link, 360).asImageBitmap() }
                Image(
                    bitmap = qr,
                    contentDescription = "Telegram login QR code",
                    modifier = Modifier.size(180.dp),
                )
            }
            if (status.standalone) {
                Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Cancel") }
            }
        }
        is app.cablegram.TvTelegramStatus.NeedsPassword -> TelegramPasswordStep(status, onPassword)
        app.cablegram.TvTelegramStatus.Off -> Unit
    }
}

/** Two-step verification on the TV: typed with the remote or the Google TV app; sent to Telegram only. */
@Composable
private fun TelegramPasswordStep(status: app.cablegram.TvTelegramStatus.NeedsPassword, onPassword: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    Text(
        "Your phone approved this TV. Your Telegram account also has two-step verification, so enter its password here once.",
        color = Muted,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
    BasicTextField(
        value = password,
        onValueChange = { password = it },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onPassword(password) }),
        textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
        cursorBrush = SolidColor(Cyan),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fieldFocus)
            .background(PanelRaised, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (password.isEmpty()) Text(status.hint.takeIf { it.isNotBlank() }?.let { "Hint: $it" } ?: "Telegram password", color = Slate, fontSize = 16.sp)
            inner()
        },
    )
    status.error?.let { Text(it, color = Cyan, fontSize = 14.sp) }
    Button(
        onClick = { if (password.isNotEmpty()) onPassword(password) },
        enabled = !status.busy && password.isNotEmpty(),
        colors = ButtonDefaults.colors(containerColor = PanelRaised),
    ) { Text(if (status.busy) "Checking…" else "Sign in") }
    Text(
        "Tip: the Google TV app on your phone can type for you. Your password goes to Telegram only; Cablegram never stores it.",
        color = Slate,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}

