package app.cablegram.phone

import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

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
        Text("Saving to ${viewModel.saveDestinationName}", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
            .background(VlcBlack)
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
        val offered = providerRows(viewModel.storage)
        Text(
            if (offered.isEmpty()) "Your own storage isn't available on this server."
            else offered.joinToString(" · ") { it.name },
            color = VlcMuted, fontSize = 12.sp,
        )
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
    val destination = viewModel.saveDestinationName
    SheetScaffold("Save to $destination", viewModel::dismissCloudSheet) {
        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted)
        Text(destination, color = Color.White)
        Text(destinationSpaceLine(viewModel.storage, viewModel.cloudAvailable), color = VlcMuted)
        Text("After saving, this title stays on your phone. Free up space later if you want.", color = VlcMuted)
        Text("●  Keep on phone", color = Color.White)
        Text("○  Remove from phone", color = VlcMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Wi-Fi only", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = viewModel.wifiOnlyTransfers, onCheckedChange = viewModel::setWifiOnly)
        }
        Button(onClick = viewModel::confirmSaveToCloud, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.CLOUD_CONFIRM)) {
            Text("Save to $destination")
        }
    }
}

@Composable
private fun ManageCloudSheet(viewModel: PhoneViewModel) {
    val context = LocalContext.current
    val status = viewModel.storage
    val rows = providerRows(status).filter { it.state != ProviderState.Connected }
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
        Text("Your own storage", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        val connection = status?.connection?.takeIf { it.status == "active" }
        if (connection != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(providerName(connection.provider, status), color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(connectedSummary(connection, status), color = Color(0xFF79D6B0))
                OutlinedButton(onClick = viewModel::disconnectStorage, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Disconnect ${providerName(connection.provider, status)}")
                }
            }
        } else {
            Text("Keep your videos in storage you own. They go straight there from your phone.", color = VlcMuted)
        }
        if (rows.isEmpty() && connection == null) {
            Text("Not available on this server", color = VlcMuted)
        }
        rows.forEach { row ->
            ProviderRowView(row, enabled = row.state != ProviderState.Locked && !viewModel.busy) {
                when (row.id) {
                    PROVIDER_GOOGLE_DRIVE -> viewModel.connectGoogle { url -> openCustomTab(context, url) }
                    PROVIDER_CLOUDFLARE_R2 -> viewModel.beginConnectR2()
                }
            }
        }
        viewModel.googleConnectError?.let { Text(it, color = Color(0xFFFFB74D)) }
    }
}

/** One provider in the list: its name, what connecting takes, and why it is off when it is. */
@Composable
private fun ProviderRowView(row: ProviderRow, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1A1A1A))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(row.name, color = if (enabled) Color.White else VlcMuted, style = MaterialTheme.typography.titleSmall)
        Text("${row.summary} · ${row.duration}", color = VlcMuted, fontSize = 13.sp)
        row.note?.let {
            Text(it, color = if (row.state == ProviderState.NeedsSignIn) Color(0xFFFFB74D) else VlcMuted, fontSize = 13.sp)
        }
        if (row.state == ProviderState.NeedsSignIn) Text("Sign in again", color = VlcOrange, fontSize = 14.sp)
    }
}

/** Google forbids sign-in inside an embedded WebView; a Custom Tab is the browser, so it is allowed. */
private fun openCustomTab(context: Context, url: String) {
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private const val CLOUDFLARE_SIGN_UP = "https://dash.cloudflare.com/sign-up"
private const val CLOUDFLARE_R2 = "https://dash.cloudflare.com/?to=/:account/r2/overview"

/** One numbered step of the connect walkthrough, optionally with a button that opens the Cloudflare page it is about. */
@Composable
private fun GuideStep(number: Int, title: String, lines: List<String>, openLabel: String? = null, openUrl: String? = null, content: (@Composable () -> Unit)? = null) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(VlcOrange), contentAlignment = Alignment.Center) {
            Text(number.toString(), color = Color.Black, fontSize = 14.sp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
            lines.forEach { Text(it, color = VlcMuted, fontSize = 14.sp) }
            if (openLabel != null && openUrl != null) {
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUrl))) } }) { Text(openLabel) }
            }
            content?.invoke()
        }
    }
}

/**
 * Cloudflare R2: the owner makes a bucket and an Object Read & Write token once, and enters its keys here. Each step
 * says what to tap in Cloudflare, with a button that opens the page, and the form checks every field as it is typed.
 */
@Composable
private fun ConnectR2Sheet(viewModel: PhoneViewModel) {
    var accountId by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val problems = listOf(r2AccountIdProblem(accountId), r2BucketProblem(bucket), r2KeyIdProblem(keyId), r2SecretProblem(secret))
    val complete = listOf(accountId, bucket, keyId, secret).all { it.isNotBlank() } && problems.all { it == null }
    SheetScaffold("Connect your own storage", viewModel::dismissCloudSheet) {
        Text("Keep your videos in your own Cloudflare R2 storage. They go straight there from your phone, and your TV plays them even when the phone is off. About 5 minutes, once.", color = VlcMuted)
        GuideStep(
            1, "Create a free Cloudflare account",
            listOf("Already have one? Skip to step 2."),
            "Open Cloudflare sign-up", CLOUDFLARE_SIGN_UP,
        )
        GuideStep(
            2, "Turn on R2 and make a bucket",
            listOf(
                "Open R2. Cloudflare asks you to subscribe to R2 once; it is free to start, and it may ask for a payment card.",
                "Then tap Create bucket. Name it with lowercase letters, digits and dashes, for example my-cablegram-videos. Leave the other settings as they are.",
            ),
            "Open R2", CLOUDFLARE_R2,
        )
        GuideStep(
            3, "Create an access token",
            listOf(
                "On the R2 page, find Account Details and tap Manage next to API Tokens. Then tap Create Account API token.",
                "Permission: Object Read & Write. Apply it to a specific bucket and choose the one from step 2. Then tap Create.",
                "Cloudflare now shows an Access Key ID, a Secret Access Key and an Endpoint. The secret is shown only once, so keep that page open.",
            ),
            "Open R2", CLOUDFLARE_R2,
        )
        GuideStep(4, "Enter the details", listOf("Copy each value from the page Cloudflare showed you. Spaces around them are ignored.")) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    accountId, { accountId = it }, label = { Text("Endpoint or Account ID") }, singleLine = true,
                    isError = problems[0] != null, supportingText = { Text(problems[0] ?: "Paste the Endpoint address Cloudflare showed, or just the 32-character account ID in it.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    bucket, { bucket = it }, label = { Text("Bucket name") }, singleLine = true,
                    isError = problems[1] != null, supportingText = { Text(problems[1] ?: "The name you gave the bucket.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    keyId, { keyId = it }, label = { Text("Access Key ID") }, singleLine = true,
                    isError = problems[2] != null, supportingText = { Text(problems[2] ?: "Shown when you created the token.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    secret, { secret = it }, label = { Text("Secret Access Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = problems[3] != null, supportingText = { Text(problems[3] ?: "Shown once, under the Access Key ID.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        viewModel.r2ConnectError?.let { Text(it, color = Color(0xFFFFB74D)) }
        Button(
            onClick = {
                viewModel.connectR2Storage(accountId, bucket, keyId, secret)
                // The secret does not stay in the form once it has been sent.
                secret = ""
            },
            enabled = !viewModel.busy && complete,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (viewModel.busy) "Checking…" else "Connect") }
        Text("What it costs: R2 includes 10 GB free; after that Cloudflare charges about $0.015 per GB a month, and downloads are free.", color = VlcMuted, fontSize = 12.sp)
        Text("Cablegram keeps the keys encrypted and uses them only for your library. To remove access later, delete the token in Cloudflare.", color = VlcMuted, fontSize = 12.sp)
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
        Text("Safely stored in ${viewModel.saveDestinationName}", color = Color(0xFF79D6B0))
        Text("Remove local copy?", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text("Your copy in ${viewModel.saveDestinationName} will remain available for TV playback.", color = VlcMuted)
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
