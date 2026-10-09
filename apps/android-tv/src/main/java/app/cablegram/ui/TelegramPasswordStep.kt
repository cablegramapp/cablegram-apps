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

/** Both password entry points share the same explicit keyboard choice and signature clip. */
@Composable
internal fun TelegramPasswordStep(
    status: TvTelegramStatus.NeedsPassword,
    onPassword: (String) -> Unit,
    onAskPhone: () -> Unit,
    onCancelPhoneRequest: () -> Unit = {},
    videoUrl: String = TELEGRAM_PASSWORD_VIDEO_URL,
    extraActions: @Composable ColumnScope.() -> Unit = {},
) {
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

    val controls: @Composable ColumnScope.() -> Unit = {
        Text("Telegram sign-in", color = Paper, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        when (mode) {
            PasswordKeyboard.CHOOSE -> {
                Text("Where would you like to type your password?", color = Cyan, fontSize = 20.sp, lineHeight = 25.sp)
                Text("Telegram needs your two-step verification password. Choose a keyboard to finish signing in on this TV.", color = Muted, fontSize = 15.sp, lineHeight = 21.sp)
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
                Button(onClick = { mode = PasswordKeyboard.TV }, modifier = Modifier.focusRequester(focus)) { Text("TV keyboard") }
                Button(onClick = { mode = PasswordKeyboard.PHONE }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Phone keyboard") }
            }
            PasswordKeyboard.TV -> {
                Text("Type with the TV keyboard", color = Cyan, fontSize = 20.sp)
                TvPasswordField(status, onPassword)
                Button(onClick = ::chooseKeyboard, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Choose keyboard") }
            }
            PasswordKeyboard.PHONE -> {
                Text(if (status.busy) "Checking your password…" else "Type on your phone", color = Cyan, fontSize = 20.sp)
                Text("Open the Cablegram notification on your paired phone. Tap “Enter password”, type it with the phone keyboard, and send it. This TV signs in automatically when Telegram accepts it.", color = Paper, fontSize = 15.sp, lineHeight = 21.sp)
                status.hint.takeIf { it.isNotBlank() }?.let { Text("Hint: $it", color = Muted, fontSize = 14.sp) }
                (status.phoneRequestError ?: status.error)?.let { Text(it, color = Coral, fontSize = 14.sp) }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
                Button(onClick = ::chooseKeyboard, modifier = Modifier.focusRequester(focus)) { Text("Choose keyboard") }
                Button(onClick = onAskPhone, enabled = !status.busy, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text("Send phone prompt again") }
            }
        }
        Text("Your password is used only to sign this TV in to Telegram.", color = Slate, fontSize = 12.sp, lineHeight = 17.sp)
        extraActions()
    }
    Row(Modifier.fillMaxSize().background(InkDeep).padding(horizontal = 28.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(.6f).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(PanelRaised)) { SignatureVideo(videoUrl, muted = true) }
        Column(Modifier.weight(.4f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = controls)
    }
}

@Composable
private fun TvPasswordField(status: TvTelegramStatus.NeedsPassword, onPassword: (String) -> Unit) {
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
    BasicTextField(
        value = password, onValueChange = { password = it }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        textStyle = TextStyle(color = Paper, fontSize = 18.sp), cursorBrush = SolidColor(Cyan),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).background(PanelRaised, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            if (password.isEmpty()) Text(status.hint.takeIf { it.isNotBlank() }?.let { "Hint: $it" } ?: "Telegram password", color = Slate, fontSize = 16.sp)
            inner()
        },
    )
    status.error?.let { Text(it, color = Coral, fontSize = 14.sp) }
    Button(onClick = ::submit, enabled = !status.busy && password.isNotEmpty(), colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(if (status.busy) "Checking…" else "Sign in") }
}
