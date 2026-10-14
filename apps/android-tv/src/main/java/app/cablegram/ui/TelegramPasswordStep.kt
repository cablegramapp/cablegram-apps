package app.cablegram.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
private data class PasswordStepState(val hint: String, val busy: Boolean, val error: String?, val phoneRequestError: String?, val phoneCode: String? = null) {
    companion object {
        fun of(status: TvTelegramStatus) = when (status) {
            is TvTelegramStatus.NeedsPassword -> PasswordStepState(status.hint, status.busy, status.error, status.phoneRequestError, status.phoneCode)
            is TvTelegramStatus.NotSignedIn -> PasswordStepState("", false, null, status.phoneRequestError, status.phoneCode)
            // Signing in with the password already given: the phone's approval and Telegram's check are under way.
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
    BackHandler(enabled = mode != PasswordKeyboard.CHOOSE) { chooseKeyboard() }

    TelegramSlide(videoUrl) {
        Text("Telegram sign-in", color = Paper, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        when (mode) {
            PasswordKeyboard.CHOOSE -> {
                Text("Where would you like to type your password?", color = Cyan, fontSize = 26.sp, lineHeight = 32.sp)
                Text("Telegram needs your two-step verification password once to sign in on this TV. Signing in starts only after you've typed it.", color = Muted, fontSize = 18.sp, lineHeight = 25.sp)
                step.error?.let { Text(it, color = Coral, fontSize = 16.sp) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
                Button(onClick = { mode = PasswordKeyboard.PHONE }, modifier = Modifier.focusRequester(focus)) { Text("Phone keyboard (recommended)") }
                Button(onClick = { mode = PasswordKeyboard.TV }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("TV keyboard") }
                onNoPassword?.let { Button(onClick = it, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("My Telegram has no password") } }
            }
            PasswordKeyboard.TV -> {
                Text("Type with the TV keyboard", color = Cyan, fontSize = 26.sp)
                Text("Back on the remote closes the keyboard. Cancel picks another one.", color = Muted, fontSize = 16.sp)
                TvPasswordField(step, onPassword, onCancel = ::chooseKeyboard)
            }
            PasswordKeyboard.PHONE -> {
                Text(if (step.busy) "Checking your password…" else "Type on your phone", color = Cyan, fontSize = 26.sp)
                Text("Cablegram on your paired phone opens a password screen. If it isn't open, tap the Cablegram notification. Type the password there; this TV signs in as soon as Telegram accepts it.", color = Paper, fontSize = 18.sp, lineHeight = 25.sp)
                step.phoneCode?.let { code ->
                    Text("Your phone shows the code", color = Muted, fontSize = 16.sp)
                    Text(code, color = Cyan, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                    Text("Send your password only if the phone shows this same code.", color = Muted, fontSize = 16.sp, lineHeight = 22.sp)
                }
                step.hint.takeIf { it.isNotBlank() }?.let { Text("Hint: $it", color = Muted, fontSize = 16.sp) }
                (step.phoneRequestError ?: step.error)?.let { Text(it, color = Coral, fontSize = 16.sp) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
                Button(onClick = ::chooseKeyboard, modifier = Modifier.focusRequester(focus)) { Text("Choose keyboard") }
                Button(onClick = onAskPhone, enabled = !step.busy, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Ask my phone again") }
            }
        }
        Text("Your password goes only to Telegram, encrypted for this TV. Cablegram never sees it.", color = Slate, fontSize = 14.sp, lineHeight = 19.sp)
        extraActions()
    }
}

@Composable
private fun TvPasswordField(status: PasswordStepState, onPassword: (String) -> Unit, onCancel: () -> Unit) {
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
            if (password.isEmpty()) Text(status.hint.takeIf { it.isNotBlank() }?.let { "Hint: $it" } ?: "Telegram password", color = Slate, fontSize = 16.sp)
            inner()
        },
    )
        Button(onClick = ::submit, enabled = !status.busy && password.isNotEmpty(), colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(if (status.busy) "Checking…" else "Sign in") }
        Button(onClick = onCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Cancel") }
    }
    status.error?.let { Text(it, color = Coral, fontSize = 14.sp) }
}
