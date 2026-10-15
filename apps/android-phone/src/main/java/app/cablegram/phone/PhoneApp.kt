package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PhoneApp(viewModel: PhoneViewModel) {
    when {
        viewModel.needsAccount -> SignInScreen(viewModel)
        viewModel.deletingAccount -> DeleteAccountScreen(viewModel)
        viewModel.termsRequired -> TermsScreen(viewModel)
        viewModel.verifyingEmail -> VerifyEmailForm(viewModel)
        viewModel.addingTv -> PairingForm(viewModel)
        viewModel.needsName -> NameForm(viewModel)
        viewModel.welcomeTourOpen -> WelcomeTour(viewModel)
        else -> LibraryShell(viewModel)
    }
    // A TV scanned before signing in waits here until there is an account to pair it to (CAB-62).
    if (viewModel.pendingPair != null && !viewModel.needsAccount && !viewModel.termsRequired) PairTrustDialog(viewModel)
    if (viewModel.pendingPrivacyItem != null) {
        PrivacyPrompt(viewModel)
    }
    if (viewModel.artworkEditor.draft != null) ArtworkEditorScreen(viewModel.artworkEditor)
}

@Composable
private fun NameForm(viewModel: PhoneViewModel) {
    var name by remember { mutableStateOf(viewModel.householdName) }
    Column(
        Modifier
            .fillMaxSize()
            .background(VlcBlack)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Name this household", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text("This name is saved on the server. Every paired TV and phone will see the same library.", color = VlcMuted)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            modifier = Modifier.fillMaxWidth().maestro(MaestroIds.HOUSEHOLD_NAME),
            singleLine = true,
            label = { Text("Household name") },
        )
        Button(
            onClick = { viewModel.saveHouseholdName(name) },
            enabled = name.trim().isNotEmpty() && !viewModel.busy,
            modifier = Modifier.fillMaxWidth().maestro(MaestroIds.HOUSEHOLD_CONTINUE),
        ) { Text(if (viewModel.busy) "Saving…" else "Continue") }
        viewModel.status?.let { Text(it, color = VlcMuted) }
    }
}

@Composable
private fun PairingForm(viewModel: PhoneViewModel) {
    val clipboard = LocalClipboardManager.current
    var pin by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    BackHandler { viewModel.cancelAddTv() }
    Column(
        Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = viewModel::cancelAddTv, modifier = Modifier.maestro(MaestroIds.PAIRING_NOT_NOW)) { Text("Not now") }
        ScreenHeading(if (viewModel.paired) "One more big screen." else "Meet your TV.",
            "Open Cablegram on your TV, then enter the six-digit code shown there.")
        Surface(color = VlcPanel, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.Tv, null, Modifier.size(40.dp), tint = VlcOrange)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { char -> char in '0'..'9' }.take(6) },
                    modifier = Modifier.fillMaxWidth().maestro(MaestroIds.PAIRING_ENTER_PIN),
                    singleLine = true,
                    label = { Text("TV code") },
                    placeholder = { Text("000000") },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pin.length == 6 && !viewModel.busy) viewModel.applyPairUri(pin) }),
                )
                Button(onClick = { viewModel.applyPairUri(pin) }, enabled = pin.length == 6 && !viewModel.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (viewModel.busy) "Connecting…" else "Connect TV")
                }
                TextButton(onClick = { pin = clipboard.getText()?.text.orEmpty().filter { it in '0'..'9' }.take(6) },
                    enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text("Paste code") }
            }
        }
        OutlinedButton(onClick = { scanning = !scanning }, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (scanning) "Close camera" else "Scan QR code instead")
        }
        if (scanning) PairingScanner(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp)), onQr = viewModel::applyPairUri)
        viewModel.status?.let { StatusNote(it) }
        Text("Keep your phone and TV on the same Wi-Fi while watching. Your video files stay on your phone.",
            color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Enter the 6-digit code we emailed you" after sign-up or from Settings. */
@Composable
private fun VerifyEmailForm(viewModel: PhoneViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            BrandMark()
            ScreenHeading("Confirm your email", "Enter the 6-digit code we sent you. A confirmed email keeps your account recoverable and turns on free relay streaming.")
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                modifier = Modifier.fillMaxWidth().maestro("email_code"),
                singleLine = true,
                label = { Text("Code") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (code.length == 6) viewModel.submitEmailCode(code) }),
            )
            Button(
                onClick = { viewModel.submitEmailCode(code) },
                enabled = code.length == 6 && !viewModel.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro("email_code_submit"),
            ) { Text(if (viewModel.busy) "Checking…" else "Confirm") }
            TextButton(onClick = viewModel::sendEmailCode, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text("Send a new code") }
            TextButton(onClick = viewModel::skipEmailVerification, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth().maestro("email_code_later")) { Text("Later") }
            viewModel.status?.let { StatusNote(it) }
        }
    }
}
