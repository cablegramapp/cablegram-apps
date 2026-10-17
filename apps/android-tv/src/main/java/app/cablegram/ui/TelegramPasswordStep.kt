package app.cablegram.ui

import app.cablegram.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import app.cablegram.TvTelegramStatus
import app.cablegram.data.TELEGRAM_PASSWORD_VIDEO_URL

private enum class PasswordKeyboard { CHOOSE, TV, PHONE }

/** What the password step shows, before signing in ([TvTelegramStatus.NotSignedIn]) or when Telegram asks. */
private data class PasswordStepState(
    val hint: String, val busy: Boolean, val error: String?, val phoneRequestError: String?, val phoneCode: String? = null,
    /** When the phone prompt reached the server; null while it is being sent. */
    val phoneSentAt: Long? = null,
    /** Signing in is waiting for the household phone to let this TV in. */
    val approving: Boolean = false,
) {
    companion object {
        fun of(status: TvTelegramStatus) = when (status) {
            is TvTelegramStatus.NeedsPassword -> PasswordStepState(status.hint, status.busy, status.error, status.phoneRequestError, status.phoneCode, status.phoneSentAt)
            is TvTelegramStatus.NotSignedIn -> PasswordStepState("", false, null, status.phoneRequestError, status.phoneCode, status.phoneSentAt)
            // Signing in with the password already given: the phone's approval and Telegram's check are under way.
            is TvTelegramStatus.WaitingForPhone -> PasswordStepState("", true, null, null, approving = true)
            else -> PasswordStepState("", true, null, null)
        }
    }
}

/**
 * One slide of the Telegram sign-in: the signature clip kept small on the left so the question reads first, and the
 * controls on the right. Shared by the "connect this TV?" slide and the keyboard slide.
 */
@Composable
internal fun TelegramSlide(videoUrl: String, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxSize().background(InkDeep).padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(.36f).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(PanelRaised)) { SignatureVideo(videoUrl, muted = true) }
        Column(Modifier.weight(.64f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

/**
 * The keyboard slide: both password entry points share the same explicit keyboard choice and signature clip. The
 * phone keyboard comes first: typing a password with a remote is slow, and the phone opens a proper password screen.
 */
@Composable
internal fun TelegramPasswordStep(
    status: TvTelegramStatus,
    onPassword: (String) -> Unit,
    onAskPhone: () -> Unit,
    onCancelPhoneRequest: () -> Unit = {},
    videoUrl: String = TELEGRAM_PASSWORD_VIDEO_URL,
    /** Shown before signing in while Cablegram doesn't know whether the account has a password. */
    onNoPassword: (() -> Unit)? = null,
    extraActions: @Composable ColumnScope.() -> Unit = {},
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val step = PasswordStepState.of(status)
    var mode by remember { mutableStateOf(PasswordKeyboard.CHOOSE) }
    val keyboard = LocalSoftwareKeyboardController.current
    val cancelPhone by rememberUpdatedState(onCancelPhoneRequest)
    val askPhone by rememberUpdatedState(onAskPhone)
    val usingPhone = mode == PasswordKeyboard.PHONE
    DisposableEffect(mode) {
        if (usingPhone) askPhone()
        onDispose { if (usingPhone) cancelPhone() }
    }
    fun chooseKeyboard() { keyboard?.hide(); mode = PasswordKeyboard.CHOOSE }
    // While signing in there is no keyboard to go back to: Back is the caller's.
    BackHandler(enabled = mode != PasswordKeyboard.CHOOSE && !step.busy) { chooseKeyboard() }

    TelegramSlide(videoUrl) {
        Text(ui.getString(R.string.ui_telegram_sign_in_f886b3), color = Paper, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        // The password is given: one steady "signing in" until Telegram answers, never the keyboard choice in between
        // (CAB-73). The chosen keyboard is kept, so a rejected password comes back to it with Telegram's message.
        if (step.busy) {
            SigningIn(step)
        } else when (mode) {
            PasswordKeyboard.CHOOSE -> {
                Text(ui.getString(R.string.ui_where_would_you_like_to_type_your_password_cbc0dc), color = Cyan, fontSize = 26.sp, lineHeight = 32.sp)
                Text(ui.getString(R.string.ui_telegram_needs_your_two_step_verification_password_onc_fe96c0), color = Muted, fontSize = 18.sp, lineHeight = 25.sp)
                step.error?.let { Text(it, color = Coral, fontSize = 16.sp) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
                Button(onClick = { mode = PasswordKeyboard.PHONE }, modifier = Modifier.focusRequester(focus)) { Text(ui.getString(R.string.ui_phone_keyboard_recommended_9db367)) }
                Button(onClick = { mode = PasswordKeyboard.TV }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_tv_keyboard_0fb61f)) }
                onNoPassword?.let { Button(onClick = it, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_my_telegram_has_no_password_2e003f)) } }
            }
            PasswordKeyboard.TV -> {
                Text(ui.getString(R.string.ui_type_with_the_tv_keyboard_b12dad), color = Cyan, fontSize = 26.sp)
                Text(ui.getString(R.string.ui_back_on_the_remote_closes_the_keyboard_cancel_picks_an_c14dae), color = Muted, fontSize = 16.sp)
                TvPasswordField(step, onPassword, onCancel = ::chooseKeyboard)
            }
            PasswordKeyboard.PHONE -> PhoneKeyboard(step, onAskPhone, onTvKeyboard = { mode = PasswordKeyboard.TV })
        }
        Text(ui.getString(R.string.ui_your_password_goes_only_to_telegram_encrypted_for_this_c23ea4), color = Slate, fontSize = 14.sp, lineHeight = 19.sp)
        extraActions()
    }
}

/**
 * Waiting for the password from the phone. Says plainly whether the prompt is still being sent or already on its way,
 * since the phone takes a few seconds to show it. Focus rests on that message, not on a button: a second press of OK
 * while waiting must not cancel the prompt or send another (CAB-73). "Ask my phone again" appears only once a new
 * prompt would actually be sent.
 */
@Composable
private fun PhoneKeyboard(step: PasswordStepState, onAskPhone: () -> Unit, onTvKeyboard: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(step.phoneSentAt) {
        while (step.phoneSentAt != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val sent = step.phoneSentAt != null
    val failed = step.phoneRequestError != null
    val canAskAgain = failed || (sent && now - step.phoneSentAt!! >= app.cablegram.PHONE_REASK_MS)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(Modifier.focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (sent || failed) ui.getString(R.string.ui_type_on_your_phone_a4ef92) else ui.getString(R.string.ui_sending_to_your_phone_4c2c7f), color = Cyan, fontSize = 26.sp)
        Text(
            when {
                failed -> ui.getString(R.string.ui_the_password_screen_didn_t_reach_your_phone_f88f9e)
                sent -> ui.getString(R.string.ui_it_s_on_its_way_cablegram_on_your_phone_opens_a_passwo_f216b7)
                else -> ui.getString(R.string.ui_cablegram_on_your_phone_will_open_a_password_screen_8fe1ea)
            },
            color = Paper, fontSize = 18.sp, lineHeight = 25.sp,
        )
        step.phoneCode?.let { code ->
            Text(ui.getString(R.string.ui_your_phone_shows_the_code_de7f56), color = Muted, fontSize = 16.sp)
            Text(code, color = Cyan, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text(ui.getString(R.string.ui_send_your_password_only_if_the_phone_shows_this_same_c_4e8437), color = Muted, fontSize = 16.sp, lineHeight = 22.sp)
        }
        step.hint.takeIf { it.isNotBlank() }?.let { Text(ui.getString(R.string.ui_hint_3033f3, it), color = Muted, fontSize = 16.sp) }
        (step.phoneRequestError ?: step.error)?.let { Text(it, color = Coral, fontSize = 16.sp) }
    }
    if (canAskAgain) {
        if (!failed) Text(ui.getString(R.string.ui_nothing_on_your_phone_yet_6a0939), color = Muted, fontSize = 16.sp)
        Button(onClick = onAskPhone, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_ask_my_phone_again_c2ad29)) }
    }
    Button(onClick = onTvKeyboard, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_use_the_tv_keyboard_instead_c6c66e)) }
}

@Composable
private fun SigningIn(step: PasswordStepState) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val focus = remember { FocusRequester() }
    // Holds focus while the keyboard's buttons are gone, so the remote doesn't lose its place.
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }
    Column(Modifier.focusRequester(focus).focusable(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (step.approving) ui.getString(R.string.ui_waiting_for_your_phone_3c5ba6) else ui.getString(R.string.ui_signing_in_8b9d3d), color = Cyan, fontSize = 26.sp)
        Text(
            if (step.approving) ui.getString(R.string.ui_your_phone_is_letting_this_tv_in_to_telegram_if_it_ask_e165e6)
            else ui.getString(R.string.ui_telegram_is_checking_your_password_this_tv_continues_a_8aca24),
            color = Paper, fontSize = 18.sp, lineHeight = 25.sp,
        )
    }
}

@Composable
private fun TvPasswordField(status: PasswordStepState, onPassword: (String) -> Unit, onCancel: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun submit() {
        if (password.isNotEmpty() && !status.busy) {
            val typed = password
            password = ""
            keyboard?.hide()
            onPassword(typed)
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }
    // Field and buttons on one line, so Sign in and Cancel stay above the TV keyboard.
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
    BasicTextField(
        value = password, onValueChange = { password = it }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        textStyle = TextStyle(color = Paper, fontSize = 20.sp), cursorBrush = SolidColor(Cyan),
        modifier = Modifier.weight(1f).focusRequester(focus).background(PanelRaised, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (password.isEmpty()) Text(status.hint.takeIf { it.isNotBlank() }?.let { ui.getString(R.string.ui_hint_3033f3, it) } ?: ui.getString(R.string.ui_telegram_password_65d868), color = Slate, fontSize = 16.sp)
            inner()
        },
    )
        Button(onClick = ::submit, enabled = !status.busy && password.isNotEmpty(), colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(if (status.busy) ui.getString(R.string.ui_checking_ec963f) else ui.getString(R.string.ui_sign_in_bfd402)) }
        Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_cancel_19766e)) }
    }
    status.error?.let { Text(it, color = Coral, fontSize = 14.sp) }
}
