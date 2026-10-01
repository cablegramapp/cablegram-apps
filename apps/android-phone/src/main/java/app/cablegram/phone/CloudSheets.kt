package app.cablegram.phone

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CloudFlow(viewModel: PhoneViewModel) {
    BackHandler(enabled = viewModel.cloudSheet != CloudSheet.None) { viewModel.dismissCloudSheet() }
    when (viewModel.cloudSheet) {
        CloudSheet.None -> Unit
        CloudSheet.Setup -> SaveSetupSheet(viewModel)
        CloudSheet.Confirm -> SaveConfirmSheet(viewModel)
        CloudSheet.Oversize -> SaveOversizeSheet(viewModel)
        CloudSheet.Manage -> ManageCloudSheet(viewModel)
        CloudSheet.ConnectR2 -> ConnectR2Sheet(viewModel)
        CloudSheet.FreeUp -> FreeUpSheet(viewModel)
        CloudSheet.FreeUpConfirm -> FreeUpConfirmSheet(viewModel)
    }
}

@Composable
fun TransferCard(viewModel: PhoneViewModel, modifier: Modifier = Modifier) {
    val item = viewModel.activeSave ?: return
    if (viewModel.transferCardDismissed) return
    val done = item.uploadBytes ?: 0
    val total = item.uploadTotal ?: item.fileSizeBytes ?: 1
    val remaining = (total - done).coerceAtLeast(0)
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE1A1A1A))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Saving to Cloud", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(item.title, color = Color.White)
        LinearProgressIndicator(
            progress = { transferFraction(item) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = VlcOrange,
            trackColor = Color(0xFF333333),
        )
        Text("${formatBytes(done)} / ${formatBytes(total)}", color = VlcMuted, fontSize = 12.sp)
        val rate = formatRate(item.transferBytesPerSec)
        val eta = etaLabel(remaining, item.transferBytesPerSec)
        if (rate.isNotBlank() || eta.isNotBlank()) {
            Text(listOf(rate, eta).filter { it.isNotBlank() }.joinToString(" · "), color = VlcMuted, fontSize = 12.sp)
        }
        TextButton(onClick = viewModel::runSaveInBackground) { Text("Hide — keep saving") }
    }
}

@Composable
private fun SheetScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(VlcBlack.copy(alpha = 0.98f))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onClose, modifier = Modifier.maestro(MaestroIds.CLOUD_CLOSE)) { Text("Close", color = VlcOrange) }
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
private fun SaveSetupSheet(viewModel: PhoneViewModel) {
    SheetScaffold("Save to Cloud", viewModel::dismissCloudSheet) {
        Text("Keep your videos available even when your phone is off.", color = VlcMuted)
        Text("☁️  ${formatBytes(viewModel.cloudAvailable)} available", color = Color.White)
        Text("Choose where your cloud files are stored.", color = VlcMuted)
        Button(onClick = viewModel::acceptCablegramCloud, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.CLOUD_CABLEGRAM)) {
            Text("Cablegram Cloud    5 GB free")
        }
        TextButton(onClick = viewModel::openOwnCloudSetup) {
            Text("Use my own cloud storage")
        }
        Text("R2 · S3 · More", color = VlcMuted, fontSize = 12.sp)
        Button(onClick = viewModel::acceptCablegramCloud, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.CLOUD_CONTINUE)) {
            Text("Continue")
        }
    }
}

@Composable
private fun SaveOversizeSheet(viewModel: PhoneViewModel) {
    val item = viewModel.saveTarget ?: return
    SheetScaffold("Save to Cloud", viewModel::dismissCloudSheet) {
        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted)
        Text("This file is larger than your available storage.", color = Color(0xFFFFB74D))
        Text("Cloud storage", color = Color.White)
        Text("${formatBytes(viewModel.cloudAvailable)} available", color = VlcMuted)
        OutlinedButton(onClick = viewModel::chooseAnotherStorage, modifier = Modifier.fillMaxWidth()) {
            Text("Choose another storage")
        }
    }
}

@Composable
private fun SaveConfirmSheet(viewModel: PhoneViewModel) {
    val item = viewModel.saveTarget ?: return
    SheetScaffold("Save to Cloud", viewModel::dismissCloudSheet) {
        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted)
        Text("Cloud storage", color = Color.White)
        Text("${formatBytes(viewModel.cloudAvailable)} available", color = VlcMuted)
        Text("After saving, this title stays on your phone. Free up space later if you want.", color = VlcMuted)
        Text("●  Keep on phone", color = Color.White)
        Text("○  Remove from phone", color = VlcMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi only", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = viewModel.wifiOnlyTransfers, onCheckedChange = viewModel::setWifiOnly)
        }
        Button(onClick = viewModel::confirmSaveToCloud, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.CLOUD_CONFIRM)) {
            Text("Save to Cloud")
        }
    }
}

@Composable
private fun ManageCloudSheet(viewModel: PhoneViewModel) {
    SheetScaffold("Cloud Storage", viewModel::dismissCloudSheet) {
        Text("Cablegram Cloud", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(
            if (viewModel.cablegramCloudReady) "5 GB free · ${formatBytes(viewModel.cloudAvailable)} available"
            else "5 GB free",
            color = VlcMuted,
        )
        if (!viewModel.cablegramCloudReady) {
            Button(onClick = viewModel::acceptCablegramCloud, modifier = Modifier.fillMaxWidth()) {
                Text("Use Cablegram Cloud")
            }
        }
        Text("Other storage", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text("Connect another provider for your own bucket. Normal saves never ask for keys.", color = VlcMuted)
        if (viewModel.cloudConnected) {
            Text(viewModel.storage?.connection?.displayLabel ?: "Your storage is connected", color = Color(0xFF79D6B0))
            OutlinedButton(onClick = viewModel::disconnectStorage, modifier = Modifier.fillMaxWidth()) {
                Text("Disconnect own storage")
            }
        } else {
            OutlinedButton(onClick = viewModel::beginConnectR2, modifier = Modifier.fillMaxWidth()) { Text("Connect your own storage") }
        }
    }
}

/** Cloudflare R2: the owner makes a bucket and an Object Read & Write token once, and enters its keys here. */
@Composable
private fun ConnectR2Sheet(viewModel: PhoneViewModel) {
    var accountId by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    SheetScaffold("Connect Cloudflare R2", viewModel::dismissCloudSheet) {
        Text("Your videos go straight to a bucket you own. Set it up once in Cloudflare:", color = VlcMuted)
        Text("1. Turn on R2 (the free tier is enough) and create a bucket.", color = Color.White)
        Text("2. R2 → Manage R2 API Tokens → Create API token. Permission: Object Read & Write, for that bucket only.", color = Color.White)
        Text("3. Copy the account ID, the Access Key ID and the Secret Access Key here.", color = Color.White)
        Text("Cablegram keeps the keys encrypted and uses them only for your library. To remove access later, delete the token in Cloudflare.", color = VlcMuted, fontSize = 12.sp)
        OutlinedTextField(accountId, { accountId = it }, label = { Text("Account ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(bucket, { bucket = it }, label = { Text("Bucket name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(keyId, { keyId = it }, label = { Text("Access Key ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            secret, { secret = it }, label = { Text("Secret Access Key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        viewModel.r2ConnectError?.let { Text(it, color = Color(0xFFFFB74D)) }
        Button(
            onClick = {
                viewModel.connectR2Storage(accountId, bucket, keyId, secret)
                // The secret does not stay in the form once it has been sent.
                secret = ""
            },
            enabled = !viewModel.busy && accountId.isNotBlank() && bucket.isNotBlank() && keyId.isNotBlank() && secret.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (viewModel.busy) "Checking…" else "Connect") }
    }
}

@Composable
private fun FreeUpSheet(viewModel: PhoneViewModel) {
    val candidates = freeUpCandidates(viewModel.items)
    val bytes = freeUpBytes(viewModel.items)
    SheetScaffold("Free up space", viewModel::dismissCloudSheet) {
        if (candidates.isEmpty()) {
            Text("Save titles to the cloud first. Then you can remove the phone copies.", color = VlcMuted)
            return@SheetScaffold
        }
        Text("Free up ${formatBytes(bytes)}", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text("These videos are already safely stored in the cloud.", color = VlcMuted)
        candidates.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.askFreeUp(item) }
                    .padding(vertical = 8.dp),
            ) {
                Text(item.title, color = Color.White)
                Text("☁️  ${item.fileSizeBytes?.let(::formatBytes).orEmpty()}", color = VlcMuted)
            }
        }
        Button(onClick = { candidates.firstOrNull()?.let(viewModel::askFreeUp) }, modifier = Modifier.fillMaxWidth()) {
            Text("Review & free space")
        }
    }
}

@Composable
private fun FreeUpConfirmSheet(viewModel: PhoneViewModel) {
    val item = viewModel.freeUpTarget ?: return
    SheetScaffold(item.title, viewModel::dismissCloudSheet) {
        Text("Safely stored in cloud", color = Color(0xFF79D6B0))
        Text("Remove local copy?", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Your cloud copy will remain available for TV playback.", color = VlcMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.keepOnPhone(item) }, modifier = Modifier.weight(1f)) {
                Text("Keep on phone")
            }
            Button(onClick = { viewModel.confirmFreeUp(item) }, modifier = Modifier.weight(1f)) {
                Text("Remove")
            }
        }
    }
}
