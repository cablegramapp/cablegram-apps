package app.cablegram.phone

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

private const val SUBDL_KEY_PAGE = "https://subdl.com/panel/api"
private const val OPENSUBTITLES_KEY_PAGE = "https://www.opensubtitles.com/consumers"

/**
 * One free account is enough: SubDL (a key only) leads; OpenSubtitles is an optional extra because it also needs the
 * person's login. Inputs are never saveable/restored, prefilled, copied into diagnostics, or shown in screenshots.
 * [requireAccount] is false where the screen is opened from Settings, so Done always leaves it.
 */
@Composable internal fun SubtitleProviderSetup(viewModel: PhoneViewModel, onDone: () -> Unit, doneLabel: String? = null, requireAccount: Boolean = true) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val credentialRevision = viewModel.subtitleCredentialRevision
    val window = LocalContext.current.activity()?.window
    DisposableEffect(window) {
        val alreadySecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val subdl = remember(credentialRevision) { viewModel.subtitleProviderConfigured("subdl") }
    val openSubtitles = remember(credentialRevision) { viewModel.subtitleProviderConfigured("opensubtitles") }

    Text(ui.getString(R.string.ui_subtitle_accounts_9602a0), color = Color.White, style = MaterialTheme.typography.titleLarge)
    Text(ui.getString(R.string.ui_cablegram_finds_subtitles_through_your_own_free_accoun_811b7f), color = VlcMuted)

    SectionCard(ui.getString(R.string.ui_subdl_recommended_7b58c1)) {
        if (subdl) {
            ConnectedRow(ui.getString(R.string.ui_subdl_is_connected_be1fd7))
            ProviderForm(viewModel, "subdl", connected = true)
        } else {
            Text(ui.getString(R.string.ui_quickest_to_set_up_it_only_needs_a_key_1a6632), color = VlcMuted)
            Step(1, ui.getString(R.string.ui_sign_in_or_create_a_free_account_at_subdl_112b2c))
            Step(2, ui.getString(R.string.ui_open_the_api_page_and_copy_your_api_key_3d48c9))
            OpenLink(ui.getString(R.string.ui_open_subdl_api_page_61aa3f), SUBDL_KEY_PAGE)
            Step(3, ui.getString(R.string.ui_paste_the_key_here_don_t_use_your_subdl_password_26ff9e))
            ProviderForm(viewModel, "subdl", connected = false)
        }
    }

    var showOpenSubtitles by remember { mutableStateOf(false) }
    SectionCard(ui.getString(R.string.ui_opensubtitles_optional_4070ca)) {
        if (openSubtitles) {
            ConnectedRow(ui.getString(R.string.ui_opensubtitles_is_connected_8086b6))
            ProviderForm(viewModel, "opensubtitles", connected = true)
        } else {
            Text(ui.getString(R.string.ui_the_biggest_catalogue_often_better_for_older_titles_it_9d2b9e), color = VlcMuted)
            TextButton(onClick = { showOpenSubtitles = !showOpenSubtitles }, contentPadding = PaddingValues(0.dp)) {
                Text(if (showOpenSubtitles) ui.getString(R.string.ui_hide_ac20a5) else ui.getString(R.string.ui_add_opensubtitles_too_a20dce))
                Icon(if (showOpenSubtitles) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showOpenSubtitles) {
                Text(ui.getString(R.string.ui_opensubtitles_needs_two_things_an_api_key_for_searchin_6cf012) +
                    ui.getString(R.string.ui_because_each_download_counts_against_your_own_account__fb8976) +
                    ui.getString(R.string.ui_your_login_stays_on_this_phone_572eb1), color = VlcMuted)
                Step(1, ui.getString(R.string.ui_create_a_free_account_at_opensubtitles_com_c1e6ef))
                Step(2, ui.getString(R.string.ui_open_the_api_consumers_page_create_a_consumer_and_copy_864978))
                OpenLink(ui.getString(R.string.ui_open_opensubtitles_api_page_9f649e), OPENSUBTITLES_KEY_PAGE)
                Step(3, ui.getString(R.string.ui_enter_the_key_with_your_opensubtitles_username_and_pas_5e0f85))
                ProviderForm(viewModel, "opensubtitles", connected = false)
            }
        }
    }

    PrivacyAndTerms()
    val anyConnected = subdl || openSubtitles
    Button(enabled = anyConnected || !requireAccount, onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(doneLabel ?: ui.getString(R.string.ui_find_subtitles_e39ed2)) }
    if (!anyConnected && requireAccount) Text(ui.getString(R.string.ui_connect_at_least_one_account_to_search_9f3093), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
}

@Composable private fun ConnectedRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.CheckCircle, null, tint = VlcOrange)
        Text(text, color = Color.White)
    }
}

@Composable private fun Step(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("$number.", color = VlcOrange)
        Text(text, color = Color.White)
    }
}

@Composable private fun OpenLink(label: String, url: String) {
    val uriHandler = LocalUriHandler.current
    OutlinedButton(onClick = { runCatching { uriHandler.openUri(url) } }) {
        Text(label); Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
    }
}

/** Entry for one provider; once connected it collapses to Replace / Remove. */
@Composable private fun ProviderForm(viewModel: PhoneViewModel, provider: String, connected: Boolean) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val clipboard = LocalClipboardManager.current
    var editing by remember(provider, connected) { mutableStateOf(!connected) }
    var key by remember(provider) { mutableStateOf("") }
    var username by remember(provider) { mutableStateOf("") }
    var password by remember(provider) { mutableStateOf("") }
    var passwordVisible by remember(provider) { mutableStateOf(false) }
    var note by remember(provider) { mutableStateOf<String?>(null) }
    var refused by remember(provider) { mutableStateOf(false) }
    var checking by remember(provider) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val needsLogin = provider == "opensubtitles"
    val name = if (needsLogin) "OpenSubtitles" else "SubDL"

    if (editing) {
        OutlinedTextField(key, { key = it.trim() }, label = { Text(ui.getString(R.string.ui_api_key_7757de, name)) },
            visualTransformation = PasswordVisualTransformation(), singleLine = true,
            trailingIcon = {
                IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { key = it } }) { Icon(Icons.Default.ContentPaste, ui.getString(R.string.ui_paste_key_b95819)) }
            },
            modifier = Modifier.fillMaxWidth().maestro("subtitle_${provider}_key"))
        if (needsLogin) {
            OutlinedTextField(username, { username = it }, label = { Text(ui.getString(R.string.ui_opensubtitles_username_f5e557)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().maestro("subtitle_opensubtitles_username"))
            OutlinedTextField(password, { password = it }, label = { Text(ui.getString(R.string.ui_opensubtitles_password_1ad679)) }, singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (passwordVisible) ui.getString(R.string.ui_hide_password_a60a56) else ui.getString(R.string.ui_show_password_6aeaa6))
                    }
                },
                modifier = Modifier.fillMaxWidth().maestro("subtitle_opensubtitles_password"))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !checking && key.isNotBlank() && (!needsLogin || username.isNotBlank() && password.isNotBlank()), onClick = {
                val typed = PersonalSubtitleCredentials(key.trim(), username.trim(), password)
                checking = true; note = null; refused = false
                scope.launch {
                    // Ask the provider first: a refused key is never saved, and what was typed stays to be corrected.
                    val check = checkSubtitleCredentials(provider, typed)
                    checking = false
                    if (check is CredentialCheck.Refused) { refused = true; note = check.message; return@launch }
                    note = try {
                        viewModel.saveSubtitleProvider(provider, typed)
                        key = ""; username = ""; password = ""; editing = false
                        (check as? CredentialCheck.Unchecked)?.message ?: ui.getString(R.string.ui_accepted_it_you_re_ready_to_search_e0f65f, name)
                    } catch (_: Exception) { ui.getString(R.string.ui_couldn_t_save_it_securely_try_again_f85662) }
                }
            }, modifier = Modifier.maestro("subtitle_${provider}_save")) {
                if (checking) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(ui.getString(R.string.ui_checking_ec963f)) }
                else Text(if (connected) ui.getString(R.string.ui_save_new_details_7ddcbe) else ui.getString(R.string.ui_connect_5fd5e3, name))
            }
            if (connected) TextButton(onClick = { editing = false; key = ""; username = ""; password = "" }) { Text(ui.getString(R.string.ui_cancel_19766e)) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { editing = true; note = null }) { Text(if (needsLogin) ui.getString(R.string.ui_change_details_b71167) else ui.getString(R.string.ui_replace_key_548dca)) }
            TextButton(onClick = { viewModel.removeSubtitleProvider(provider); note = ui.getString(R.string.ui_removed_subtitles_you_already_chose_stay_ae4157, name) }) { Text(ui.getString(R.string.ui_remove_c3812f)) }
        }
    }
    note?.let { Text(it, color = if (refused) MaterialTheme.colorScheme.error else VlcMuted, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.maestro("subtitle_${provider}_note")) }
}

@Composable private fun PrivacyAndTerms() {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var open by remember { mutableStateOf(false) }
    Text(ui.getString(R.string.ui_searches_go_straight_from_this_phone_to_the_site_you_c_3c8a91) +
        ui.getString(R.string.ui_only_the_subtitle_you_pick_is_saved_for_your_tvs_ab5d20), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { open = !open }, contentPadding = PaddingValues(0.dp)) {
        Text(if (open) ui.getString(R.string.ui_hide_privacy_and_terms_942d73) else ui.getString(R.string.ui_privacy_and_terms_9b68c4))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
    }
    if (open) {
        Text(ui.getString(R.string.ui_the_subtitle_sites_see_your_ip_address_the_title_file__cc820e), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Text(ui.getString(R.string.ui_only_the_subtitle_you_choose_its_language_provider_ref_abd48f), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Text(ui.getString(R.string.ui_personal_keys_do_not_grant_copyright_permission_use_tr_003d6d), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
    }
}
