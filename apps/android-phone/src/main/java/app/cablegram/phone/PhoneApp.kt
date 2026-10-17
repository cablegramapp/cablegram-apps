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
import androidx.compose.ui.platform.LocalUriHandler
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
        Text(ui.getString(R.string.ui_name_this_household_a2930a), color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(ui.getString(R.string.ui_this_name_is_saved_on_the_server_every_paired_tv_and_p_0a385b), color = VlcMuted)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(80) },
            modifier = Modifier.fillMaxWidth().maestro(MaestroIds.HOUSEHOLD_NAME),
            singleLine = true,
            label = { Text(ui.getString(R.string.ui_household_name_e4d50a)) },
        )
        Button(
            onClick = { viewModel.saveHouseholdName(name) },
            enabled = name.trim().isNotEmpty() && !viewModel.busy,
            modifier = Modifier.fillMaxWidth().maestro(MaestroIds.HOUSEHOLD_CONTINUE),
        ) { Text(if (viewModel.busy) ui.getString(R.string.ui_saving_23e392) else ui.getString(R.string.ui_continue_31fbef)) }
        viewModel.status?.let { Text(it, color = VlcMuted) }
    }
}

@Composable
private fun PairingForm(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var pin by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    BackHandler { viewModel.cancelAddTv() }
    Column(
        Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        TextButton(onClick = viewModel::cancelAddTv, modifier = Modifier.maestro(MaestroIds.PAIRING_NOT_NOW)) { Text(ui.getString(R.string.ui_not_now_a0e63d)) }
        ScreenHeading(if (viewModel.paired) ui.getString(R.string.ui_one_more_big_screen_3514a4) else ui.getString(R.string.ui_meet_your_tv_4d8c97),
            ui.getString(R.string.pairing_setup_steps))
        TextButton(onClick = { uriHandler.openUri("https://cablegram.app/#downloads") }) {
            Text(ui.getString(R.string.pairing_get_tv_app))
        }
        Surface(color = VlcPanel, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Default.Tv, null, Modifier.size(40.dp), tint = VlcOrange)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter { char -> char in '0'..'9' }.take(6) },
                    modifier = Modifier.fillMaxWidth().maestro(MaestroIds.PAIRING_ENTER_PIN),
                    singleLine = true,
                    label = { Text(ui.getString(R.string.ui_tv_code_8c33e0)) },
                    placeholder = { Text("000000") },
                    textStyle = MaterialTheme.typography.headlineMedium.copy(letterSpacing = 8.sp, textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (pin.length == 6 && !viewModel.busy) viewModel.applyPairUri(pin) }),
                )
                Button(onClick = { viewModel.applyPairUri(pin) }, enabled = pin.length == 6 && !viewModel.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(if (viewModel.busy) ui.getString(R.string.ui_connecting_72021e) else ui.getString(R.string.ui_connect_tv_c9f94c))
                }
                TextButton(onClick = { pin = clipboard.getText()?.text.orEmpty().filter { it in '0'..'9' }.take(6) },
                    enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_paste_code_682909)) }
            }
        }
        OutlinedButton(onClick = { scanning = !scanning }, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (scanning) ui.getString(R.string.ui_close_camera_bebcb8) else ui.getString(R.string.ui_scan_qr_code_instead_375856))
        }
        if (scanning) PairingScanner(Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp)), onQr = viewModel::applyPairUri)
        viewModel.status?.let { StatusNote(it) }
        Text(ui.getString(R.string.ui_keep_your_phone_and_tv_on_the_same_wi_fi_while_watchin_7a397d),
            color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
    }
}
