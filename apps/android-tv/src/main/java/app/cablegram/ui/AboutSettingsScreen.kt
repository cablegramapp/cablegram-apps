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
import app.cablegram.diagnostics.DiagnosticsPreferences
import app.cablegram.diagnostics.TvDiagnostics
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
    serverUrl: String? = null,
    onReturnToCablegram: (() -> Unit)? = null,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
            Button(onClick = ::backToSettings, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_back_to_settings_ea37ec)) }
            if (telegramStatus is app.cablegram.TvTelegramStatus.NeedsPassword && telegramViaPhone) {
                Button(onClick = { onTelegramViaPhone(false) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_use_this_tv_for_telegram_playback_051438)) }
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
            Text(ui.getString(R.string.ui_about_cablegram_8eeae9), color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.app_tagline), color = Cyan, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.legal_disclaimer),
                color = Muted,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            )
            if (isDemoMode) {
                Text(
                    ui.getString(R.string.ui_demo_mode_royalty_free_sample_streams_9e36b2),
                    color = Cyan,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (serverUrl != null) {
                Text(ui.getString(R.string.ui_server_1_s_dfe9cc, serverUrl), color = Slate, fontSize = 14.sp)
                if (onReturnToCablegram != null) {
                    Button(onClick = onReturnToCablegram, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_return_to_cablegram_e2e1d7)) }
                }
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
                colors = ButtonDefaults.colors(containerColor = PanelRaised),
            ) {
                Text(ui.getString(R.string.ui_open_privacy_policy_c90fcb))
            }
            Text(PRIVACY_POLICY_URL, color = Slate, fontSize = 12.sp)
            if (TvDiagnostics.isAvailable()) {
                var crashReporting by remember { mutableStateOf(DiagnosticsPreferences.isCrashReportingEnabled(context)) }
                var reportStatus by remember { mutableStateOf<String?>(null) }
                var sendingReport by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val next = !crashReporting
                            crashReporting = next
                            DiagnosticsPreferences.setCrashReportingEnabled(context, next)
                        },
                        colors = ButtonDefaults.colors(containerColor = PanelRaised),
                    ) {
                        Text(if (crashReporting) stringResource(R.string.diagnostics_crash_reports_on) else stringResource(R.string.diagnostics_crash_reports_off))
                    }
                    Button(
                        onClick = {
                            sendingReport = true
                            TvDiagnostics.sendProblemReport(
                                context = context,
                                description = "User initiated report from TV",
                                tvLanIp = tvLanIp,
                                telegramStatus = telegramStatus.javaClass.simpleName,
                                serverUrl = serverUrl,
                            ) { success ->
                                sendingReport = false
                                reportStatus = if (success) {
                                    ui.getString(R.string.diagnostics_report_sent)
                                } else {
                                    ui.getString(R.string.diagnostics_report_failed)
                                }
                            }
                        },
                        enabled = !sendingReport,
                        colors = ButtonDefaults.colors(containerColor = PanelRaised),
                    ) {
                        Text(if (sendingReport) stringResource(R.string.diagnostics_sending) else stringResource(R.string.diagnostics_report_problem_button))
                    }
                }
                reportStatus?.let {
                    Text(it, color = Cyan, fontSize = 12.sp)
                }
            }
            var showLegal by remember { mutableStateOf(false) }
            Button(
                onClick = { showLegal = !showLegal },
                colors = ButtonDefaults.colors(containerColor = PanelRaised),
            ) {
                Text(if (showLegal) ui.getString(R.string.ui_hide_licence_and_notices_35e525) else ui.getString(R.string.ui_licence_and_notices_ded184))
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
            Text(ui.getString(R.string.ui_connection_639a40), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            SettingValue(ui.getString(R.string.ui_this_tv_3e2400), tvLanIp ?: ui.getString(R.string.ui_waiting_for_wi_fi_72c813), monospace = true)
            SettingValue(ui.getString(R.string.ui_media_source_de4006), ui.getString(R.string.ui_your_paired_phone_7e4f9a))
            SettingValue(ui.getString(R.string.ui_playback_path_5cecee), ui.getString(R.string.ui_wi_fi_first_relay_when_away_4ab75e))
            Text(
                ui.getString(R.string.ui_same_wi_fi_gives_the_fastest_start_and_best_quality_el_50f729),
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (status == app.cablegram.TvTelegramStatus.Off) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status) {
        while (status is app.cablegram.TvTelegramStatus.WaitingForPhone) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Text("Telegram", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Text(ui.getString(R.string.ui_cablegram_is_an_unofficial_app_not_made_or_endorsed_by_5de578), color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
    when (status) {
        app.cablegram.TvTelegramStatus.Connecting -> Text(ui.getString(R.string.ui_connecting_to_telegram_06a2ff), color = Muted, fontSize = 14.sp)
        is app.cablegram.TvTelegramStatus.NotSignedIn -> {
            Text(
                ui.getString(R.string.ui_your_household_s_telegram_is_connected_but_this_tv_isn_90d748),
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Button(onClick = { onSignIn(status.twoStep) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_connect_this_tv_8474b0)) }
        }
        app.cablegram.TvTelegramStatus.ThroughPhone ->
            Text(ui.getString(R.string.ui_telegram_videos_play_through_your_phone_on_this_tv_not_e75ff6), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
        is app.cablegram.TvTelegramStatus.Connected ->
            SettingValue(ui.getString(R.string.ui_signed_in_as_abc50e), status.name)
        is app.cablegram.TvTelegramStatus.Problem -> Text(status.message, color = Coral, fontSize = 14.sp, lineHeight = 20.sp)
        app.cablegram.TvTelegramStatus.CanConnect -> {
            Text(
                ui.getString(R.string.ui_telegram_isn_t_connected_for_this_household_yet_you_ca_634c79),
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Button(onClick = onConnect, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_connect_telegram_on_this_tv_461bf0)) }
        }
        is app.cablegram.TvTelegramStatus.WaitingForPhone -> {
            if (!status.standalone) {
                Text(ui.getString(R.string.ui_waiting_for_your_phone_to_let_this_tv_use_your_telegra_6296ca), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            if (status.standalone || now - status.since > 20_000) {
                Text(
                    ui.getString(R.string.ui_scan_this_code_in_telegram_on_your_phone_settings_devi_1df732),
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                val qr = remember(status.link) { createQrBitmap(status.link, 360).asImageBitmap() }
                Image(
                    bitmap = qr,
                    contentDescription = ui.getString(R.string.ui_telegram_login_qr_code_b413de),
                    modifier = Modifier.size(180.dp),
                )
            }
            if (status.standalone) {
                Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_cancel_19766e)) }
            }
        }
        is app.cablegram.TvTelegramStatus.NeedsPassword -> {
            Text(ui.getString(R.string.ui_telegram_needs_your_two_step_verification_password_to__2f2be0), color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
            Button(onClick = onOpenPassword, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_enter_telegram_password_7844e5)) }
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
        Button(onClick = { connectChosen = false }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_back_76900f)) }
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
        Text(ui.getString(R.string.ui_connecting_telegram_ed3dc8), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        when (status) {
            is app.cablegram.TvTelegramStatus.Problem -> Text(status.message, color = Coral, fontSize = 18.sp, lineHeight = 25.sp)
            is app.cablegram.TvTelegramStatus.WaitingForPhone ->
                Text(
                    if (status.standalone) ui.getString(R.string.ui_finish_signing_in_from_settings_telegram_on_this_tv_d69b81)
                    else ui.getString(R.string.ui_your_phone_is_letting_this_tv_in_to_telegram_if_it_ask_a75585),
                    color = Muted,
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                )
            is app.cablegram.TvTelegramStatus.NeedsPassword -> Text(ui.getString(R.string.ui_checking_your_password_06c20d), color = Muted, fontSize = 18.sp)
            else -> Text(ui.getString(R.string.ui_signing_in_8b9d3d), color = Muted, fontSize = 18.sp)
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        Button(onClick = { connectChosen = false }, modifier = Modifier.focusRequester(focus)) { Text(ui.getString(R.string.ui_back_76900f)) }
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    TelegramSlide(videoUrl) {
        Text(ui.getString(R.string.ui_watch_telegram_on_this_tv_b79cf0), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text(ui.getString(R.string.ui_connect_your_telegram_to_this_tv_once_and_your_library_f3e103), color = Cyan, fontSize = 20.sp, lineHeight = 26.sp)
        listOf(
            ui.getString(R.string.ui_starts_faster_and_seeks_smoothly_the_video_comes_strai_bd5209),
            ui.getString(R.string.ui_your_phone_can_sleep_or_leave_home_it_uses_no_battery__dec30b),
            ui.getString(R.string.ui_this_tv_gets_its_own_session_end_it_any_time_in_telegr_ad0e0a),
            ui.getString(R.string.ui_cablegram_reads_only_your_cablegram_library_channel_ne_359f98),
        ).forEach { Text("•  $it", color = Paper, fontSize = 16.sp, lineHeight = 22.sp) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onConnect, modifier = Modifier.focusRequester(focus)) { Text(ui.getString(R.string.ui_connect_this_tv_8474b0)) }
            Button(onClick = onBack, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_back_to_library_008e42)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onPlayThroughPhone(false) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_play_through_my_phone_this_time_389f1f)) }
            Button(onClick = { onPlayThroughPhone(true) }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_always_use_my_phone_ce2492)) }
        }
    }
}
