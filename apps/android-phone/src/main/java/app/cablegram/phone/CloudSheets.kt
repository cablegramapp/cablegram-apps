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
import androidx.compose.material3.AlertDialog
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
        Text(ui.getString(R.string.ui_saving_to_a516ef, viewModel.saveDestinationName), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(item.title, color = Color.White)
        LinearProgressIndicator(
            progress = { transferFraction(item) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = VlcOrange,
            trackColor = Color(0xFF333333),
        )
        Text(ui.getString(R.string.ui_message_f1c1f8, formatBytes(done), formatBytes(total)), color = VlcMuted, fontSize = 12.sp)
        val rate = formatRate(item.transferBytesPerSec)
        val eta = etaLabel(remaining, item.transferBytesPerSec, resources = ui)
        if (rate.isNotBlank() || eta.isNotBlank()) {
            Text(listOf(rate, eta).filter { it.isNotBlank() }.joinToString(" · "), color = VlcMuted, fontSize = 12.sp)
        }
        TextButton(onClick = viewModel::runSaveInBackground) { Text(ui.getString(R.string.ui_hide_keep_saving_e0c66f)) }
    }
}

@Composable
private fun SheetScaffold(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    Column(
        Modifier
            .fillMaxSize()
            .background(VlcBlack)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onClose, modifier = Modifier.maestro(MaestroIds.CLOUD_CLOSE)) { Text(ui.getString(R.string.ui_close_7d9eb7), color = VlcOrange) }
        Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
private fun SaveSetupSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    // Saves go only to the household's own storage (CAB-38): this sheet leads to connecting it.
    SheetScaffold(ui.getString(R.string.ui_save_to_cloud_0fd280), viewModel::dismissCloudSheet) {
        Text(ui.getString(R.string.ui_keep_your_videos_available_even_when_your_phone_is_off_b72efb), color = VlcMuted)
        val offered = providerRows(viewModel.storage, resources = ui)
        Text(
            if (offered.isEmpty()) ui.getString(R.string.ui_your_own_storage_isn_t_available_on_this_server_d34244)
            else offered.joinToString(" · ") { it.name },
            color = VlcMuted, fontSize = 12.sp,
        )
        if (offered.isNotEmpty()) {
            Button(onClick = viewModel::openOwnCloudSetup, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.CLOUD_CONTINUE)) {
                Text(ui.getString(R.string.ui_connect_your_storage_04561b))
            }
        }
    }
}

@Composable
private fun SaveOversizeSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val item = viewModel.saveTarget ?: return
    SheetScaffold(ui.getString(R.string.ui_save_to_cloud_0fd280), viewModel::dismissCloudSheet) {
        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted)
        Text(ui.getString(R.string.ui_this_file_is_larger_than_your_available_storage_e6fe5f), color = Color(0xFFFFB74D))
        Text(ui.getString(R.string.ui_cloud_storage_9644ab), color = Color.White)
        Text(ui.getString(R.string.ui_available_9ffcbc, formatBytes(viewModel.cloudAvailable)), color = VlcMuted)
        OutlinedButton(onClick = viewModel::chooseAnotherStorage, modifier = Modifier.fillMaxWidth()) {
            Text(ui.getString(R.string.ui_choose_another_storage_10ef3b))
        }
    }
}

@Composable
private fun SaveConfirmSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val item = viewModel.saveTarget ?: return
    val destination = viewModel.saveDestinationName
    SheetScaffold(ui.getString(R.string.ui_save_to_48c64f, destination), viewModel::dismissCloudSheet) {
        Text(item.title, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted)
        Text(destination, color = Color.White)
        Text(destinationSpaceLine(viewModel.storage, viewModel.cloudAvailable, resources = ui), color = VlcMuted)
        Text(ui.getString(R.string.ui_after_saving_this_title_stays_on_your_phone_free_up_sp_d0fcae), color = VlcMuted)
        Text(ui.getString(R.string.ui_keep_on_phone_cff4f5), color = Color.White)
        Text(ui.getString(R.string.ui_remove_from_phone_b2bf2b), color = VlcMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.getString(R.string.ui_wi_fi_only_ca99bb), color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = viewModel.wifiOnlyTransfers, onCheckedChange = viewModel::setWifiOnly)
        }
        Button(onClick = viewModel::confirmSaveToCloud, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.CLOUD_CONFIRM)) {
            Text(ui.getString(R.string.ui_save_to_48c64f, destination))
        }
    }
}

@Composable
private fun ManageCloudSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val context = LocalContext.current
    val status = viewModel.storage
    val rows = providerRows(status, resources = ui).filter { it.state != ProviderState.Connected }
    SheetScaffold(ui.getString(R.string.ui_cloud_storage_2ec3eb), viewModel::dismissCloudSheet) {
        Text(ui.getString(R.string.ui_your_own_storage_6765b7), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        val connection = status?.connection?.takeIf { it.status == "active" }
        if (connection != null) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF1A1A1A)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(providerName(connection.provider, status, resources = ui), color = Color.White, style = MaterialTheme.typography.titleSmall)
                Text(connectedSummary(connection, status, resources = ui), color = Color(0xFF79D6B0))
                OutlinedButton(onClick = viewModel::disconnectStorage, enabled = !viewModel.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(ui.getString(R.string.ui_disconnect_7d073a, providerName(connection.provider, status, resources = ui)))
                }
            }
        } else {
            Text(ui.getString(R.string.ui_keep_your_videos_in_storage_you_own_they_go_straight_t_697721), color = VlcMuted)
        }
        if (rows.isEmpty() && connection == null) {
            Text(ui.getString(R.string.ui_not_available_on_this_server_210147), color = VlcMuted)
        }
        rows.forEach { row ->
            ProviderRowView(row, enabled = row.state != ProviderState.Locked && !viewModel.busy) {
                when (row.id) {
                    PROVIDER_GOOGLE_DRIVE -> viewModel.googleSignInNoteOpen = true
                    PROVIDER_CLOUDFLARE_R2 -> viewModel.beginConnectR2()
                }
            }
        }
        viewModel.googleConnectError?.let { Text(it, color = Color(0xFFFFB74D)) }
    }
    if (viewModel.googleSignInNoteOpen) {
        AlertDialog(
            onDismissRequest = { viewModel.googleSignInNoteOpen = false },
            title = { Text(ui.getString(R.string.ui_one_box_to_tick_0f7e37)) },
            text = { Text(googleSignInPreparation( resources = ui)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.googleSignInNoteOpen = false
                    viewModel.connectGoogle { url -> openCustomTab(context, url) }
                }) { Text(ui.getString(R.string.ui_open_google_f8b2a8)) }
            },
            dismissButton = { TextButton(onClick = { viewModel.googleSignInNoteOpen = false }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
        )
    }
}

/** One provider in the list: its name, what connecting takes, and why it is off when it is. */
@Composable
private fun ProviderRowView(row: ProviderRow, enabled: Boolean, onClick: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
        if (row.state == ProviderState.NeedsSignIn) Text(ui.getString(R.string.ui_sign_in_again_51fbe1), color = VlcOrange, fontSize = 14.sp)
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var accountId by remember { mutableStateOf("") }
    var bucket by remember { mutableStateOf("") }
    var keyId by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    val problems = listOf(r2AccountIdProblem(accountId), r2BucketProblem(bucket), r2KeyIdProblem(keyId), r2SecretProblem(secret))
    val complete = listOf(accountId, bucket, keyId, secret).all { it.isNotBlank() } && problems.all { it == null }
    SheetScaffold(ui.getString(R.string.ui_connect_your_own_storage_10a917), viewModel::dismissCloudSheet) {
        Text(ui.getString(R.string.ui_keep_your_videos_in_your_own_cloudflare_r2_storage_the_16689a), color = VlcMuted)
        GuideStep(
            1, ui.getString(R.string.ui_create_a_free_cloudflare_account_9d8fd1),
            listOf(ui.getString(R.string.ui_already_have_one_skip_to_step_2_55c7ad)),
            ui.getString(R.string.ui_open_cloudflare_sign_up_53c22b), CLOUDFLARE_SIGN_UP,
        )
        GuideStep(
            2, ui.getString(R.string.ui_turn_on_r2_and_make_a_bucket_2479a0),
            listOf(
                ui.getString(R.string.ui_open_r2_cloudflare_asks_you_to_subscribe_to_r2_once_it_10d8f5),
                ui.getString(R.string.ui_then_tap_create_bucket_name_it_with_lowercase_letters__10114b),
            ),
            ui.getString(R.string.ui_open_r2_41969b), CLOUDFLARE_R2,
        )
        GuideStep(
            3, ui.getString(R.string.ui_create_an_access_token_ed7ea7),
            listOf(
                ui.getString(R.string.ui_on_the_r2_page_find_account_details_and_tap_manage_nex_31acb8),
                ui.getString(R.string.ui_permission_object_read_write_apply_it_to_a_specific_bu_ac52d4),
                ui.getString(R.string.ui_cloudflare_now_shows_an_access_key_id_a_secret_access__5639c8),
            ),
            ui.getString(R.string.ui_open_r2_41969b), CLOUDFLARE_R2,
        )
        GuideStep(4, ui.getString(R.string.ui_enter_the_details_0aa04d), listOf(ui.getString(R.string.ui_copy_each_value_from_the_page_cloudflare_showed_you_sp_bb099b))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    accountId, { accountId = it }, label = { Text(ui.getString(R.string.ui_endpoint_or_account_id_dce8d2)) }, singleLine = true,
                    isError = problems[0] != null, supportingText = { Text(problems[0] ?: ui.getString(R.string.ui_paste_the_endpoint_address_cloudflare_showed_or_just_t_6ee22f)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    bucket, { bucket = it }, label = { Text(ui.getString(R.string.ui_bucket_name_800f2e)) }, singleLine = true,
                    isError = problems[1] != null, supportingText = { Text(problems[1] ?: ui.getString(R.string.ui_the_name_you_gave_the_bucket_2ae9c0)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    keyId, { keyId = it }, label = { Text(ui.getString(R.string.ui_access_key_id_8761c0)) }, singleLine = true,
                    isError = problems[2] != null, supportingText = { Text(problems[2] ?: ui.getString(R.string.ui_shown_when_you_created_the_token_e2211a)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    secret, { secret = it }, label = { Text(ui.getString(R.string.ui_secret_access_key_d2aeac)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = problems[3] != null, supportingText = { Text(problems[3] ?: ui.getString(R.string.ui_shown_once_under_the_access_key_id_27f4b9)) },
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
        ) { Text(if (viewModel.busy) ui.getString(R.string.ui_checking_ec963f) else ui.getString(R.string.ui_connect_1a2303)) }
        Text(ui.getString(R.string.ui_what_it_costs_r2_includes_10_gb_free_after_that_cloudf_499770), color = VlcMuted, fontSize = 12.sp)
        Text(ui.getString(R.string.ui_cablegram_keeps_the_keys_encrypted_and_uses_them_only__5e4393), color = VlcMuted, fontSize = 12.sp)
    }
}

@Composable
private fun FreeUpSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val candidates = freeUpCandidates(viewModel.items)
    val bytes = freeUpBytes(viewModel.items)
    SheetScaffold(ui.getString(R.string.ui_free_up_space_5912ec), viewModel::dismissCloudSheet) {
        if (candidates.isEmpty()) {
            Text(ui.getString(R.string.ui_save_titles_to_the_cloud_first_then_you_can_remove_the_a6c5d3), color = VlcMuted)
            return@SheetScaffold
        }
        Text(ui.getString(R.string.ui_free_up_8fe74c, formatBytes(bytes)), color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(ui.getString(R.string.ui_these_videos_are_already_safely_stored_in_the_cloud_8fce14), color = VlcMuted)
        candidates.forEach { item ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.askFreeUp(item) }
                    .padding(vertical = 8.dp),
            ) {
                Text(item.title, color = Color.White)
                Text(ui.getString(R.string.ui_message_20f3b4, item.fileSizeBytes?.let(::formatBytes).orEmpty()), color = VlcMuted)
            }
        }
        Button(onClick = { candidates.firstOrNull()?.let(viewModel::askFreeUp) }, modifier = Modifier.fillMaxWidth()) {
            Text(ui.getString(R.string.ui_review_free_space_0a228f))
        }
    }
}

@Composable
private fun FreeUpConfirmSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val item = viewModel.freeUpTarget ?: return
    SheetScaffold(item.title, viewModel::dismissCloudSheet) {
        Text(ui.getString(R.string.ui_safely_stored_in_f9eee7, viewModel.saveDestinationName), color = Color(0xFF79D6B0))
        Text(ui.getString(R.string.ui_remove_local_copy_67b13a), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(ui.getString(R.string.ui_your_copy_in_will_remain_available_for_tv_playback_bb5796, viewModel.saveDestinationName), color = VlcMuted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.keepOnPhone(item) }, modifier = Modifier.weight(1f)) {
                Text(ui.getString(R.string.ui_keep_on_phone_bded0c))
            }
            Button(onClick = { viewModel.confirmFreeUp(item) }, modifier = Modifier.weight(1f)) {
                Text(ui.getString(R.string.ui_remove_c3812f))
            }
        }
    }
}
