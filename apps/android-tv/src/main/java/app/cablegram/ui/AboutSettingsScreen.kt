package app.cablegram.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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
import app.cablegram.isSigningIn
import app.cablegram.data.PRIVACY_POLICY_URL

@Composable
internal fun AboutSettingsPanel(
    isDemoMode: Boolean,
    tvLanIp: String?,
    telegramStatus: app.cablegram.TvTelegramStatus = app.cablegram.TvTelegramStatus.Off,
    onTelegramPassword: (String) -> Unit = {},
    onTelegramAskPhone: () -> Unit = {},
    onTelegramCancelPasswordRequest: () -> Unit = {},
    telegramViaPhone: Boolean = false,
    onTelegramViaPhone: (Boolean) -> Unit = {},
    onTelegramConnect: () -> Unit = {},
    onTelegramCancel: () -> Unit = {},
    onTelegramSignInWithoutPassword: () -> Unit = {},
) {
    // The password step opens over Settings, but never locks the viewer out of the rest of it (sign-out, pairing).
    var passwordStepOpen by remember { mutableStateOf(true) }
    // A linked household's TV that isn't signed in: the keyboard slide opens only when the viewer chooses to connect.
    var connectStepOpen by remember { mutableStateOf(false) }
    val signingIn = telegramStatus.isSigningIn()
    LaunchedEffect(signingIn) { if (!signingIn) connectStepOpen = false }
    // One step for the whole sign-in, kept up from the keyboard choice until Telegram answers: leaving it while
    // "Connecting" brought it back on the keyboard choice for a moment (CAB-73).
    if ((telegramStatus is app.cablegram.TvTelegramStatus.NeedsPassword && passwordStepOpen) || (connectStepOpen && signingIn)) {
        fun backToSettings() { connectStepOpen = false; passwordStepOpen = false }
        androidx.activity.compose.BackHandler(enabled = connectStepOpen) { backToSettings() }
        val notSignedIn = telegramStatus as? app.cablegram.TvTelegramStatus.NotSignedIn
        TelegramPasswordStep(
            telegramStatus, onTelegramPassword, onTelegramAskPhone, onTelegramCancelPasswordRequest,
            onNoPassword = if (notSignedIn != null && notSignedIn.twoStep == null) onTelegramSignInWithoutPassword else null,
        ) {
            Button(onClick = ::backToSettings, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Back to Settings") }
            if (telegramStatus is app.cablegram.TvTelegramStatus.NeedsPassword && telegramViaPhone) {
                Button(onClick = { onTelegramViaPhone(false) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Use this TV for Telegram playback") }
            }
        }
        return
    }
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
                // Scrolls to a focused button: "Licence and notices" sat half below the screen on a 1080p TV.
                .verticalScroll(rememberScrollState())
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
            // A remote scrolls by moving focus. With Telegram connected this section has no button, so it is focusable
            // itself (outlined when focused): otherwise "Signed in as" could never be scrolled into view.
            val telegramInteraction = remember { MutableInteractionSource() }
            val telegramFocused by telegramInteraction.collectIsFocusedAsState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(2.dp, if (telegramFocused) Cyan else Color.Transparent, RoundedCornerShape(12.dp))
                    .focusable(interactionSource = telegramInteraction)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                TelegramSettingsSection(telegramStatus, telegramViaPhone, onTelegramViaPhone, onTelegramConnect, onTelegramCancel,
                    onOpenPassword = { passwordStepOpen = true; connectStepOpen = true },
                    onSignIn = { twoStep -> if (twoStep == false) onTelegramSignInWithoutPassword() else connectStepOpen = true })
            }
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
    viaPhone: Boolean,
    onViaPhone: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onOpenPassword: () -> Unit = {},
    onSignIn: (twoStep: Boolean?) -> Unit = {},
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
        is app.cablegram.TvTelegramStatus.NotSignedIn -> {
            Text(
                "Your household's Telegram is connected, but this TV isn't signed in. Sign it in to stream Telegram videos straight from Telegram, without your phone.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Button(onClick = { onSignIn(status.twoStep) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Connect this TV") }
        }
        app.cablegram.TvTelegramStatus.ThroughPhone ->
            Text("Telegram videos play through your phone on this TV. Nothing from Telegram is stored here.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        is app.cablegram.TvTelegramStatus.Connected ->
            SettingValue("Signed in as", status.name)
        is app.cablegram.TvTelegramStatus.Problem -> Text(status.message, color = Coral, fontSize = 14.sp, lineHeight = 20.sp)
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
        is app.cablegram.TvTelegramStatus.NeedsPassword -> {
            Text("Telegram needs your two-step verification password to finish signing in on this TV.", color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Button(onClick = onOpenPassword, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Enter Telegram password") }
        }
        app.cablegram.TvTelegramStatus.Off -> Unit
    }
}

/**
 * Opened from a Telegram title while this TV isn't signed in to Telegram yet. Slide 1 asks whether to connect this TV
 * at all and why it is worth it; only after "Connect" does slide 2 ask for the keyboard (or show the sign-in progress).
 * Playback starts once Telegram accepts the sign-in.
 */
@Composable
internal fun TelegramPasswordScreen(
    status: app.cablegram.TvTelegramStatus,
    onPassword: (String) -> Unit,
    onAskPhone: () -> Unit,
    onSignedIn: () -> Unit,
    onPlayThroughPhone: (always: Boolean) -> Unit,
    onBack: () -> Unit,
    onCancelPasswordRequest: () -> Unit = {},
    onSignInWithoutPassword: () -> Unit = {},
    passwordVideoUrl: String = app.cablegram.data.TELEGRAM_PASSWORD_VIDEO_URL,
) {
    var connectChosen by rememberSaveable { mutableStateOf(false) }
    // Back from slide 2 returns to the question; from slide 1 it leaves.
    androidx.activity.compose.BackHandler { if (connectChosen) connectChosen = false else onBack() }
    LaunchedEffect(status is app.cablegram.TvTelegramStatus.Connected) {
        if (status is app.cablegram.TvTelegramStatus.Connected) onSignedIn()
    }
    if (!connectChosen) {
        TelegramConnectQuestion(passwordVideoUrl, onConnect = { connectChosen = true }, onPlayThroughPhone = onPlayThroughPhone, onBack = onBack)
        return
    }
    val previous: @Composable ColumnScope.() -> Unit = {
        Button(onClick = { connectChosen = false }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Back") }
    }
    val notSignedIn = status as? app.cablegram.TvTelegramStatus.NotSignedIn
    // An account without two-step verification signs in right away: approving on the phone finishes it.
    LaunchedEffect(notSignedIn?.twoStep) { if (notSignedIn?.twoStep == false) onSignInWithoutPassword() }
    // Kept up through the whole sign-in (its own "signing in" while busy), so the chosen keyboard survives the
    // in-between states and the keyboard choice never flashes back (CAB-73).
    val askPassword = (notSignedIn != null && notSignedIn.twoStep != false) ||
        status is app.cablegram.TvTelegramStatus.NeedsPassword ||
        status == app.cablegram.TvTelegramStatus.Connecting || status is app.cablegram.TvTelegramStatus.WaitingForPhone
    if (askPassword) {
        TelegramPasswordStep(
            status, onPassword, onAskPhone, onCancelPasswordRequest, videoUrl = passwordVideoUrl,
            onNoPassword = if (notSignedIn?.twoStep == null && notSignedIn != null) onSignInWithoutPassword else null,
            extraActions = previous,
        )
        return
    }
    TelegramSlide(passwordVideoUrl) {
        Text("Connecting Telegram", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        when (status) {
            is app.cablegram.TvTelegramStatus.Problem -> Text(status.message, color = Coral, fontSize = 18.sp, lineHeight = 25.sp)
            is app.cablegram.TvTelegramStatus.WaitingForPhone ->
                Text(
                    if (status.standalone) "Finish signing in from Settings → Telegram on this TV."
                    else "Your phone is letting this TV in to Telegram. If it asks, approve it in the Cablegram notification. The video starts as soon as it's done.",
                    color = Muted,
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                )
            is app.cablegram.TvTelegramStatus.NeedsPassword -> Text("Checking your password…", color = Muted, fontSize = 18.sp)
            else -> Text("Signing in…", color = Muted, fontSize = 18.sp)
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        Button(onClick = { connectChosen = false }, modifier = Modifier.focusRequester(focus)) { Text("Back") }
    }
}

/** Slide 1: whether to connect Telegram on this TV at all, with what it brings; the phone stays an option. */
@Composable
private fun TelegramConnectQuestion(
    videoUrl: String,
    onConnect: () -> Unit,
    onPlayThroughPhone: (always: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    TelegramSlide(videoUrl) {
        Text("Watch Telegram on this TV?", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Connect your Telegram to this TV once, and your library plays straight from Telegram.", color = Cyan, fontSize = 20.sp, lineHeight = 26.sp)
        listOf(
            "Starts faster and seeks smoothly: the video comes straight from Telegram.",
            "Your phone can sleep or leave home; it uses no battery or data.",
            "This TV gets its own session. End it any time in Telegram → Settings → Devices.",
            "Cablegram reads only your \"Cablegram library\" channel, never your chats.",
        ).forEach { Text("•  $it", color = Paper, fontSize = 16.sp, lineHeight = 22.sp) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onConnect, modifier = Modifier.focusRequester(focus)) { Text("Connect this TV") }
            Button(onClick = onBack, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Back to library") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onPlayThroughPhone(false) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Play through my phone this time") }
            Button(onClick = { onPlayThroughPhone(true) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Always use my phone") }
        }
    }
}
