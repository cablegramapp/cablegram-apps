package app.cablegram.ui

import app.cablegram.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import app.cablegram.data.ServerProbe
import app.cablegram.data.ownServerUrl
import kotlinx.coroutines.launch

/**
 * "Use your own server" on the pairing screen: the TV checks that the address answers like a Cablegram server, then
 * pairs with it. Typed on the TV because a phone can't safely hand a server address to an unpaired TV.
 */
@Composable
internal fun OwnServerForm(
    check: suspend (String) -> ServerProbe,
    onUse: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val connectFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf(TextFieldValue("http://", TextRange(7))) }
    val address = field.text
    var checking by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    /** Up from Connect or Cancel goes back to the field; nothing focusable sits above them. */
    val upToField = Modifier.onPreviewKeyEvent { event ->
        val up = event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp
        if (up) runCatching { focus.requestFocus() }
        up
    }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onCancel)

    fun connect() {
        if (checking || address.isBlank()) return
        keyboard?.hide()
        // An error then leaves the remote on Connect, with Cancel next to it.
        runCatching { connectFocus.requestFocus() }
        checking = true
        error = null
        scope.launch {
            when (val found = check(address)) {
                is ServerProbe.Found -> onUse(found.url)
                ServerProbe.InvalidAddress -> error = ui.getString(R.string.ui_use_https_or_http_for_an_address_on_your_home_network_d0a725)
                ServerProbe.Unreachable -> error = ui.getString(R.string.ui_could_not_reach_that_server_check_the_address_and_that_b7da05)
                ServerProbe.NotCablegram -> error = ui.getString(R.string.ui_that_address_is_not_a_cablegram_server_0f26c7)
            }
            checking = false
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 56.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BrandMark()
        Text(ui.getString(R.string.ui_use_your_own_server_622a50), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
        Text(ui.getString(R.string.ui_enter_your_cablegram_server_address_for_example_http_1_8ff787), color = Muted, fontSize = 17.sp, lineHeight = 24.sp)
        // Above the field: the TV keyboard covers everything below it.
        if (ownServerUrl(address)?.startsWith("http://") == true) {
            Text(ui.getString(R.string.ui_this_address_is_not_encrypted_use_it_only_on_your_home_3eaa84), color = Warning, fontSize = 14.sp)
        }
        error?.let { Text(it, color = Coral, fontSize = 14.sp) }
        // Field and buttons on one line, so Connect and Cancel stay above the TV keyboard.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = field,
                onValueChange = { if (it.text != field.text) error = null; field = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { connect() }),
                textStyle = TextStyle(color = Paper, fontSize = 20.sp),
                cursorBrush = SolidColor(Cyan),
                modifier = Modifier.weight(1f).focusRequester(focus).onFocusChanged { fieldFocused = it.isFocused }
                    // A text field keeps the D-pad for its caret: down, or right at the end, goes on to Connect.
                    .onPreviewKeyEvent { event ->
                        val leave = event.type == KeyEventType.KeyDown && (event.key == Key.DirectionDown ||
                            (event.key == Key.DirectionRight && field.selection.collapsed && field.selection.end == field.text.length))
                        if (leave) runCatching { connectFocus.requestFocus() }
                        leave
                    }
                    .background(PanelRaised, RoundedCornerShape(10.dp))
                    .border(2.dp, if (fieldFocused) Cyan else Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (address.isEmpty()) Text(ui.getString(R.string.ui_server_address_8be897), color = Slate, fontSize = 16.sp)
                    inner()
                },
            )
            Button(onClick = ::connect, enabled = !checking && address.isNotBlank(), modifier = Modifier.focusRequester(connectFocus).then(upToField), colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                Text(if (checking) ui.getString(R.string.ui_checking_ec963f) else ui.getString(R.string.ui_connect_1a2303))
            }
            Button(onClick = onCancel, modifier = upToField, colors = ButtonDefaults.colors(containerColor = PanelRaised)) { Text(ui.getString(R.string.ui_cancel_19766e)) }
        }
    }
}
