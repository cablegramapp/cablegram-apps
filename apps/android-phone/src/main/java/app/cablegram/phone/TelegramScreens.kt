package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cablegram.telegram.LibraryBotRecommendations
import app.cablegram.telegram.CablegramChannel
import app.cablegram.telegram.CommunityChannels
import app.cablegram.telegram.TelegramSession
import app.cablegram.telegram.TelegramState

private const val PRIVACY_URL = "https://cablegram.app/privacy.html#telegram"

/**
 * The code that talks to Telegram, in the public repository, at the tag this build is released from
 * (`v` + versionName). Update the tag in the URL together with `versionName` in build.gradle.kts.
 */
private const val TELEGRAM_CODE_URL =
    "https://github.com/seep-labs/cablegram-apps/tree/v0.2.2/apps/android-phone/src/main/java/app/cablegram/telegram"

/** A direct route to the household's linked channel, before the other import sources. */
@Composable
internal fun TelegramLibraryCard(viewModel: PhoneViewModel) {
    if (!PhoneTelegram.configured) return
    val context = LocalContext.current
    val ui = context.resources
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val link = viewModel.telegramLink
    val linked = link?.linked == true
    val chatId = link?.chatId
    // Linked for the household, but this phone has no Telegram session of its own: not connected here.
    val notHere = linked && (viewModel.telegramHealth == TelegramHealth.NotHere || !PhoneTelegram.wasLinked(context))
    androidx.compose.runtime.LaunchedEffect(linked, chatId) { if (linked) viewModel.checkTelegramHealth() }
    SectionCard(ui.getString(R.string.telegram_library_title)) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = VlcOrange, modifier = Modifier.size(32.dp))
        Text(TelegramSession.LIBRARY_TITLE, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(ui.getString(R.string.telegram_library_help), color = VlcMuted)
        when {
            viewModel.telegramHealth == TelegramHealth.ChannelLost && linked && !notHere -> {
                Text(ui.getString(R.string.ui_you_re_no_longer_in_the_channel_you_left_it_or_it_was__46af5b, TelegramSession.LIBRARY_TITLE), color = VlcOrange)
                Button(onClick = viewModel::recreateTelegramChannel, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(ui.getString(R.string.ui_create_a_new_cb63aa, TelegramSession.LIBRARY_TITLE))
                }
            }
            notHere -> {
                // The household is linked, but not from this phone: say so instead of "Connected" and an Open button.
                Text(ui.getString(R.string.ui_this_phone_isn_t_signed_in_to_telegram_yet_so_it_can_t_a21b4f), color = VlcOrange)
                Button(onClick = viewModel::openTelegramSheet, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text(ui.getString(R.string.ui_sign_in_on_this_phone_1bc154))
                }
            }
            linked && chatId != null -> {
                Text(ui.getString(R.string.ui_connected_as_767c2d, link?.displayName ?: ui.getString(R.string.ui_your_account_17a7e5)), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                Button(onClick = { openInTelegram(context, chatId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_LIBRARY_OPEN)) {
                    Text(ui.getString(R.string.telegram_library_open))
                }
                OutlinedButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(PhoneTelegram.channelWebLink(chatId)))
                    android.widget.Toast.makeText(context, ui.getString(R.string.telegram_library_copied), android.widget.Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.TELEGRAM_LIBRARY_COPY)) {
                    Text(ui.getString(R.string.telegram_library_copy))
                }
                Text(ui.getString(R.string.telegram_library_members_link), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            }
            else -> Button(onClick = viewModel::openTelegramSheet, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text(ui.getString(R.string.ui_connect_telegram_00c799))
            }
        }
    }
}

/**
 * Telegram in Import → Connected storage (spec 004 US1): connect once on the phone, then videos
 * shared into the private "Cablegram library" channel appear in Cablegram.
 */
@Composable
internal fun TelegramStorageEntry(viewModel: PhoneViewModel, onAccountHelp: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (!PhoneTelegram.configured) return
    val link = viewModel.telegramLink
    val linked = link?.linked == true
    val health = viewModel.telegramHealth
    val context = LocalContext.current
    val notHere = linked && (health == TelegramHealth.NotHere || !PhoneTelegram.wasLinked(context))
    var confirmDisconnect by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).maestro(MaestroIds.TELEGRAM_ENTRY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = VlcOrange)
        Column(Modifier.weight(1f)) {
            Text(if (linked) ui.getString(R.string.ui_telegram_4bdd3a, TelegramSession.LIBRARY_TITLE) else "Telegram", color = Color.White)
            Text(
                when {
                    !linked -> ui.getString(R.string.ui_use_your_own_telegram_account_as_cloud_storage_share_v_0d5978)
                    notHere -> ui.getString(R.string.ui_this_phone_isn_t_signed_in_to_telegram_yet_so_it_can_t_a21b4f)
                    health == TelegramHealth.ChannelLost ->
                        ui.getString(R.string.ui_you_re_no_longer_in_the_channel_you_left_it_or_it_was__46af5b, TelegramSession.LIBRARY_TITLE)
                    health == TelegramHealth.SignedOut ->
                        ui.getString(R.string.ui_telegram_signed_cablegram_out_on_this_phone_for_exampl_c6a476)
                    else -> ui.getString(R.string.ui_connected_as_videos_you_share_into_this_private_channe_54b272, link?.displayName ?: ui.getString(R.string.ui_your_account_17a7e5))
                },
                color = if (notHere || health == TelegramHealth.ChannelLost || health == TelegramHealth.SignedOut) VlcOrange else VlcMuted,
                fontSize = 12.sp,
            )
        }
    }
    when {
        !linked -> Button(
            onClick = viewModel::openTelegramSheet,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_CONNECT),
        ) { Text(ui.getString(R.string.ui_connect_telegram_00c799)) }
        notHere -> Button(
            onClick = viewModel::openTelegramSheet,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(ui.getString(R.string.ui_sign_in_on_this_phone_1bc154)) }
        health == TelegramHealth.ChannelLost -> Button(
            onClick = viewModel::recreateTelegramChannel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_RECREATE),
        ) { Text(ui.getString(R.string.ui_create_a_new_cb63aa, TelegramSession.LIBRARY_TITLE)) }
        health == TelegramHealth.SignedOut -> Button(
            onClick = viewModel::signInToTelegramAgain,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text(ui.getString(R.string.ui_sign_in_to_telegram_again_ce9582)) }
        else -> link?.chatId?.let { chatId ->
            OutlinedButton(
                onClick = { openInTelegram(context, chatId) },
                modifier = Modifier.fillMaxWidth().maestro(MaestroIds.TELEGRAM_OPEN),
            ) { Text(ui.getString(R.string.ui_open_in_telegram_700ad9, TelegramSession.LIBRARY_TITLE)) }
        }
    }
    if (linked && health == TelegramHealth.Ok && PhoneTelegram.wasLinked(context)) {
        OutlinedButton(
            onClick = viewModel::checkTelegramBotHub,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_CHECK_BOT_HUB),
        ) { Text(ui.getString(R.string.ui_check_bot_hub_d194d2)) }
        OutlinedButton(
            onClick = viewModel::organizeTelegramFolder,
            enabled = !viewModel.telegramFolderBusy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_ORGANIZE_FOLDER),
        ) { Text(if (viewModel.telegramFolderBusy) ui.getString(R.string.ui_organizing_564f6b) else ui.getString(R.string.ui_organize_telegram_folder_84e271)) }
        viewModel.telegramFolderMessage?.let { Text(it, color = VlcMuted, modifier = Modifier.padding(horizontal = 8.dp)) }
        var autoApprove by remember { mutableStateOf(PhoneTelegram.autoApproveTvs(context)) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(ui.getString(R.string.ui_approve_my_tvs_automatically_23e7c5), color = Color.White)
                Text(
                    ui.getString(R.string.ui_off_a_notification_asks_before_each_tv_signs_in_to_tel_6f98b3),
                    color = VlcMuted,
                    fontSize = 12.sp,
                )
            }
            androidx.compose.material3.Switch(
                checked = autoApprove,
                onCheckedChange = { autoApprove = it; PhoneTelegram.setAutoApproveTvs(context, it) },
            )
        }
    }
    if (linked && !notHere && health == TelegramHealth.Ok) {
        OutlinedButton(onClick = viewModel::openHiddenVideos, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_hidden_videos_e92f75)) }
    }
    if (viewModel.showHiddenVideos) HiddenVideosDialog(viewModel)
    TextButton(onClick = onAccountHelp, modifier = Modifier.maestro(MaestroIds.TELEGRAM_ACCOUNT_HELP)) {
        Text(ui.getString(R.string.telegram_separate_learn), color = VlcOrange)
    }
    if (linked) {
        OutlinedButton(
            onClick = { confirmDisconnect = true },
            modifier = Modifier.fillMaxWidth().maestro(MaestroIds.TELEGRAM_DISCONNECT),
        ) { Text(ui.getString(R.string.ui_disconnect_telegram_3896d7), color = Color(0xFFFF8A80)) }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(ui.getString(R.string.ui_disconnect_telegram_eb9ab8)) },
            text = {
                Text(
                    ui.getString(R.string.ui_cablegram_signs_out_of_telegram_on_this_phone_and_on_y_73f0dc) +
                        ui.getString(R.string.ui_channel_they_disappear_from_cablegram_until_you_connec_2ef282, TelegramSession.LIBRARY_TITLE),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; viewModel.disconnectTelegram() }) { Text(ui.getString(R.string.ui_disconnect_acfc5b)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
            modifier = Modifier.maestroRoot(),
        )
    }
}

/** Phone number → code → (password) → library set up. One request at a time (the session enforces it). */
@Composable
internal fun TelegramConnectSheet(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    BackHandler(onBack = viewModel::closeTelegramSheet)
    val started = viewModel.telegramStarted || PhoneTelegram.existing() != null
    var accountHelpOpen by rememberSaveable { mutableStateOf(false) }
    if (accountHelpOpen) {
        TelegramAccountHelp(onBack = { accountHelpOpen = false })
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(VlcBlack.copy(alpha = 0.98f))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .maestro(MaestroIds.TELEGRAM_SHEET),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = viewModel::closeTelegramSheet) { Text(ui.getString(R.string.ui_close_7d9eb7), color = VlcOrange) }
        // A household that is already linked still needs the intro on a phone that has no session of its own.
        val context = LocalContext.current
        if (!started && !PhoneTelegram.wasLinked(context)) BeforeYouConnect(viewModel, onAccountHelp = { accountHelpOpen = true }) else SignInSteps(viewModel)
    }
}

/** FR-021: what Cablegram gets, what it uses, where credentials go, how to revoke — before any input. */
@Composable
private fun BeforeYouConnect(viewModel: PhoneViewModel, onAccountHelp: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val context = LocalContext.current
    Text(ui.getString(R.string.ui_before_you_connect_telegram_435c06), color = Color.White, style = MaterialTheme.typography.headlineSmall)
    TrustPoint(
        ui.getString(R.string.ui_what_cablegram_can_do_86c731),
        ui.getString(R.string.ui_like_any_telegram_app_telegram_desktop_for_example_cab_22d711),
    )
    TrustPoint(
        ui.getString(R.string.ui_what_it_does_c74ea9),
        ui.getString(R.string.ui_for_your_videos_it_reads_and_manages_one_private_chann_a5212a, TelegramSession.LIBRARY_TITLE),
    )
    TrustPoint(
        ui.getString(R.string.ui_where_your_details_go_c282bf),
        ui.getString(R.string.ui_your_phone_number_login_code_and_password_go_straight__bc3e04),
    )
    TrustPoint(
        ui.getString(R.string.ui_you_stay_in_control_faa171),
        ui.getString(R.string.ui_you_ll_see_cablegram_in_telegram_settings_devices_and__e6a486),
    )
    TrustPoint(
        ui.getString(R.string.ui_you_can_check_this_ba4981),
        ui.getString(R.string.ui_the_cablegram_apps_are_open_source_anyone_can_check_wh_3a0623),
    )
    TextButton(onClick = {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TELEGRAM_CODE_URL)))
        }
    }) { Text(ui.getString(R.string.ui_read_the_code_f049d5), color = VlcOrange) }
    Text(
        ui.getString(R.string.ui_cablegram_is_an_unofficial_app_it_isn_t_made_or_endors_7e0562),
        color = VlcMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    TrustPoint(ui.getString(R.string.telegram_separate_title), ui.getString(R.string.telegram_separate_intro))
    TextButton(onClick = onAccountHelp, modifier = Modifier.maestro(MaestroIds.TELEGRAM_ACCOUNT_HELP)) {
        Text(ui.getString(R.string.telegram_separate_learn), color = VlcOrange)
    }
    Button(
        onClick = viewModel::connectTelegram,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_CONTINUE),
    ) { Text(ui.getString(R.string.ui_continue_31fbef)) }
    // FR-023: only when a Home TV is paired; it must already be signed in to the household's Telegram.
    if (viewModel.homeTvForTelegram() != null && viewModel.telegramLink?.linked == true) {
        OutlinedButton(onClick = viewModel::connectTelegramViaTv, modifier = Modifier.fillMaxWidth()) {
            Text(ui.getString(R.string.ui_use_the_qr_code_on_your_tv_instead_7c5651))
        }
        Text(
            ui.getString(R.string.ui_your_tv_is_already_signed_in_to_your_telegram_it_appro_6e35a3),
            color = VlcMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedButton(onClick = viewModel::closeTelegramSheet, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_not_now_a0e63d)) }
    TextButton(onClick = {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL)))
        }
    }) { Text(ui.getString(R.string.ui_how_this_works_bdbd77), color = VlcOrange) }
}

@Composable
private fun TrustPoint(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
        Text(body, color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SignInSteps(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val context = LocalContext.current
    val session = remember { PhoneTelegram.session(context) }
    val state by session.state.collectAsState()
    val busy by session.busy.collectAsState()
    val inputError by session.lastError.collectAsState()
    var input by rememberSaveable(state::class) { mutableStateOf("") }
    Text(if (viewModel.telegramBotHubOnly) ui.getString(R.string.ui_bot_hub_6dcb6e) else ui.getString(R.string.ui_connect_telegram_00c799), color = Color.White, style = MaterialTheme.typography.headlineSmall)

    val linked = viewModel.telegramLink?.linked == true && viewModel.telegramHealth == TelegramHealth.Ok && PhoneTelegram.wasLinked(context)
    when {
        linked -> {
            val channels = viewModel.telegramCommunityChannels
            val communityState = channels?.state?.collectAsState()?.value
            if (!viewModel.telegramBotHubOnly && channels != null && communityState?.completed == false) {
                CommunityChannelsOptions(channels, viewModel::saveTelegramCommunityChannels)
            } else {
                val bots = viewModel.telegramLibraryBots
                val botState = bots?.state?.collectAsState()?.value
                if (bots != null && botState?.completed == false) {
                    androidx.compose.runtime.LaunchedEffect(bots) { viewModel.loadTelegramLibraryBots() }
                    LibraryBotOptions(bots, viewModel::loadTelegramLibraryBots, viewModel::addRecommendedTelegramBot)
                } else if (viewModel.telegramBotHubOnly) {
                    Text(ui.getString(R.string.ui_bot_hub_review_complete_check_again_any_time_for_curre_700291), color = VlcMuted)
                    Button(onClick = viewModel::closeTelegramSheet, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_close_bot_hub_855e5a)) }
                } else Done(viewModel)
            }
        }
        viewModel.telegramMessage != null -> {
            Text(viewModel.telegramMessage!!, color = VlcOrange)
            if (state is TelegramState.Ready) {
                OutlinedButton(onClick = viewModel::connectTelegram) { Text(ui.getString(R.string.ui_try_again_d8b839)) }
            }
        }
        viewModel.telegramFinishing || state is TelegramState.Ready -> Working(ui.getString(R.string.ui_setting_up_your_channel_3eeef5, TelegramSession.LIBRARY_TITLE))
        else -> when (val s = state) {
            TelegramState.Starting -> Working(ui.getString(R.string.ui_starting_telegram_44fd03))
            TelegramState.NeedsPhoneNumber -> {
                Text(ui.getString(R.string.ui_enter_the_phone_number_of_your_telegram_account_it_goe_284479), color = VlcMuted)
                Step(
                    label = ui.getString(R.string.ui_phone_number_306f1b),
                    hint = ui.getString(R.string.ui_with_country_code_e_g_31_6_1234_5678_f10939),
                    value = input,
                    onValue = { input = it.take(20) },
                    keyboard = KeyboardType.Phone,
                    busy = busy,
                    error = inputError,
                    action = ui.getString(R.string.ui_send_code_66a5b4),
                    enabled = input.count(Char::isDigit) >= 7,
                ) { session.submitPhoneNumber(input) }
            }
            TelegramState.NeedsCode -> {
                Text(ui.getString(R.string.ui_telegram_sent_a_login_code_to_your_telegram_app_or_by__0317af), color = VlcMuted)
                Text(
                    ui.getString(R.string.ui_telegram_s_message_says_not_to_give_this_code_to_anyon_7277cb) +
                        ui.getString(R.string.ui_cablegram_uses_it_to_sign_in_with_telegram_directly_li_f3fc01),
                    color = VlcMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Step(ui.getString(R.string.ui_login_code_95e717), "5 digits", input, { input = it.filter(Char::isDigit).take(8) }, KeyboardType.NumberPassword, busy, inputError, ui.getString(R.string.ui_continue_31fbef), input.length >= 5) {
                    session.submitCode(input)
                }
            }
            is TelegramState.NeedsPassword -> {
                Text(ui.getString(R.string.ui_your_account_has_two_step_verification_enter_your_tele_9e706f), color = VlcMuted)
                Text(
                    ui.getString(R.string.ui_cablegram_passes_your_password_to_telegram_to_sign_in__0bb35c),
                    color = VlcMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Step(
                    label = ui.getString(R.string.ui_telegram_password_65d868),
                    hint = s.hint.takeIf { it.isNotBlank() }?.let { ui.getString(R.string.ui_hint_3033f3, it) } ?: "",
                    value = input,
                    onValue = { input = it },
                    keyboard = KeyboardType.Password,
                    busy = busy,
                    error = inputError,
                    action = ui.getString(R.string.ui_sign_in_bfd402),
                    enabled = input.isNotEmpty(),
                    secret = true,
                ) { session.submitPassword(input) }
            }
            is TelegramState.Failed -> {
                Text(s.message, color = VlcOrange)
                OutlinedButton(onClick = { session.retryStartup() }) { Text(ui.getString(R.string.ui_try_again_d8b839)) }
            }
            is TelegramState.WaitingForApproval ->
                if (viewModel.telegramViaTv) Working(ui.getString(R.string.ui_waiting_for_your_tv_to_approve_keep_cablegram_open_on__785c22))
                else Working(ui.getString(R.string.ui_starting_telegram_44fd03))
            TelegramState.SignedOut -> Working(ui.getString(R.string.ui_starting_telegram_44fd03))
            is TelegramState.Ready -> Unit
        }
    }
}

/** Optional memberships are explicit: even the default selections do nothing until Save. */
@Composable
internal fun CommunityChannelsOptions(channels: CommunityChannels, onSave: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val state by channels.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.maestro("telegram_channels")) {
        Text(ui.getString(R.string.ui_join_cablegram_channels_d35d5f), color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(
            ui.getString(R.string.ui_these_channels_are_optional_uncheck_any_you_don_t_want_b5e843),
            color = VlcMuted,
        )
        CablegramChannel.entries.forEach { channel ->
            val joined = channel in state.joined
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .toggleable(
                        value = channel in state.selected,
                        enabled = !state.saving && !joined,
                        role = Role.Checkbox,
                        onValueChange = { channels.select(channel, it) },
                    ).maestro("telegram_channel_${channel.username}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = channel in state.selected, onCheckedChange = null, enabled = !state.saving && !joined)
                Column(Modifier.weight(1f)) {
                    Text(channel.title, color = Color.White)
                    Text(channel.url, color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                    if (channel == CablegramChannel.BotHubs) {
                        Text(ui.getString(R.string.ui_discover_useful_telegram_bots_that_make_managing_organ_090372), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                        Text(ui.getString(R.string.ui_these_are_independent_third_party_bots_and_are_not_dev_296876), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (joined) Text(ui.getString(R.string.ui_joined_69318b), color = VlcOrange)
                    if (channel in state.failed) Text(ui.getString(R.string.ui_couldn_t_join_try_save_again_or_uncheck_to_continue_02e0d9), color = VlcOrange)
                }
            }
        }
        Text(ui.getString(R.string.ui_joined_cablegram_channels_your_library_support_and_add_30d764), color = VlcMuted)
        Button(
            onClick = onSave,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro("telegram_channels_save"),
        ) { Text(if (state.saving) ui.getString(R.string.ui_joining_6bbb89) else ui.getString(R.string.ui_save_1509f5)) }
    }
}

/** Each Add is separately gated; Skip never requires accepting the disclaimer. */
@Composable
internal fun LibraryBotOptions(bots: LibraryBotRecommendations, onRetry: () -> Unit, onAdd: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val state by bots.state.collectAsState()
    val context = LocalContext.current
    val bot = state.current
    Column(Modifier.maestro("telegram_library_bots"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(ui.getString(R.string.ui_optional_bots_for_your_library_0e5b67), color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Text(ui.getString(R.string.ui_discover_useful_telegram_bots_that_make_managing_organ_090372), color = VlcMuted)
        Text(ui.getString(R.string.ui_these_are_independent_third_party_bots_and_are_not_dev_296876), color = VlcMuted)
        Text(ui.getString(R.string.ui_recommendations_come_from_posts_in_cablegrambothubs_yo_812f39), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        if (state.added > 0) Text(ui.getString(R.string.ui_added_to_your_library_392ec3, (state.added).toString()), color = VlcOrange)
        if (!state.loaded) {
            if (state.busy) Working(ui.getString(R.string.ui_finding_bots_in_cablegram_bot_hubs_6632c3))
            state.error?.let { Text(it, color = VlcOrange) }
            if (!state.busy) {
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_try_again_d8b839)) }
                TextButton(onClick = bots::finish, modifier = Modifier.fillMaxWidth().maestro("telegram_bots_skip_all")) { Text(ui.getString(R.string.ui_skip_bots_9e32d8)) }
            }
        } else if (bot == null) {
            Text(ui.getString(R.string.ui_no_bots_are_available_to_review_you_can_check_cablegra_cffc27), color = VlcMuted)
            Button(onClick = bots::finish, modifier = Modifier.fillMaxWidth().maestro("telegram_bots_done")) { Text(ui.getString(R.string.ui_continue_31fbef)) }
        } else {
            Text(ui.getString(R.string.ui_bot_of_c8ea8f, (state.index + 1).toString(), (state.bots.size).toString()), color = VlcMuted)
            Text(bot.displayName, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text("@${bot.username}", color = VlcOrange)
            OutlinedButton(onClick = {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(bot.url))) }
            }) { Text(ui.getString(R.string.ui_review_bot_in_telegram_eec873)) }
            Text(ui.getString(R.string.ui_access_to_your_library_channel_4ecfde), color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(ui.getString(R.string.ui_the_bot_will_be_added_to_your_cablegram_library_channe_1d4a14), color = VlcMuted)
            Text(ui.getString(R.string.ui_this_recommendation_is_shared_for_informational_purpos_02f61b), color = Color.White, modifier = Modifier.maestro("telegram_bot_disclaimer"))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(
                    value = state.disclaimerAccepted,
                    enabled = !state.busy,
                    role = Role.Checkbox,
                    onValueChange = bots::acceptDisclaimer,
                ).maestro("telegram_bot_accept_disclaimer"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = state.disclaimerAccepted, onCheckedChange = null, enabled = !state.busy)
                Text(ui.getString(R.string.ui_i_have_reviewed_this_bot_s_permissions_and_agree_to_th_07cab6, bot.username), color = Color.White)
            }
            state.error?.let { Text(it, color = VlcOrange) }
            Button(
                onClick = onAdd,
                enabled = state.disclaimerAccepted && !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro("telegram_bot_add"),
            ) { Text(if (state.busy) ui.getString(R.string.ui_adding_c6de6f) else ui.getString(R.string.ui_add_to_library_channel_f72709)) }
            OutlinedButton(onClick = bots::skipCurrent, enabled = !state.busy, modifier = Modifier.fillMaxWidth().maestro("telegram_bot_skip")) { Text(ui.getString(R.string.ui_skip_this_bot_b287ce)) }
            TextButton(onClick = bots::finish, enabled = !state.busy, modifier = Modifier.fillMaxWidth().maestro("telegram_bots_skip_all")) { Text(ui.getString(R.string.ui_skip_remaining_bots_89f0a7)) }
        }
    }
}

@Composable
private fun Step(
    label: String,
    hint: String,
    value: String,
    onValue: (String) -> Unit,
    keyboard: KeyboardType,
    busy: Boolean,
    error: String?,
    action: String,
    enabled: Boolean,
    secret: Boolean = false,
    onSubmit: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        modifier = Modifier.fillMaxWidth().maestro(MaestroIds.TELEGRAM_INPUT),
        singleLine = true,
        label = { Text(label) },
        supportingText = { Text(error ?: hint, color = if (error != null) VlcOrange else VlcMuted) },
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
    )
    Button(
        onClick = onSubmit,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_SUBMIT),
    ) { Text(if (busy) ui.getString(R.string.ui_checking_ec963f) else action) }
}

@Composable
private fun Working(message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(22.dp), color = VlcOrange, strokeWidth = 2.dp)
        Text(message, color = VlcMuted)
    }
}

@Composable
private fun Done(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val context = LocalContext.current
    Text(ui.getString(R.string.ui_connected_as_767c2d, viewModel.telegramLink?.displayName ?: ui.getString(R.string.ui_your_account_17a7e5)), color = Color.White, style = MaterialTheme.typography.titleMedium)
    when (viewModel.telegramChannelCreated) {
        true -> Text(ui.getString(R.string.ui_cablegram_created_the_private_channel_in_your_telegram_12ed98, TelegramSession.LIBRARY_TITLE), color = VlcMuted)
        false -> Text(ui.getString(R.string.ui_cablegram_is_using_the_channel_that_already_exists_in__8a8ad9, TelegramSession.LIBRARY_TITLE), color = VlcMuted)
        null -> Unit
    }
    viewModel.telegramLink?.chatId?.let { chatId ->
        Button(onClick = { openInTelegram(context, chatId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(ui.getString(R.string.ui_open_it_in_telegram_b012d4))
        }
    }
    Text(
        ui.getString(R.string.ui_telegram_will_show_a_new_login_alert_possibly_someone__6a8b3b) +
            ui.getString(R.string.ui_on_tap_yes_it_s_me_7d75cd, PhoneTelegram.deviceModel),
        color = VlcMuted,
    )
    Text(
        ui.getString(R.string.ui_in_telegram_share_or_forward_videos_into_the_private_c_ab9acc, TelegramSession.LIBRARY_TITLE) +
            ui.getString(R.string.ui_they_appear_in_cablegram_and_play_on_your_tv_straight__d414d1),
        color = VlcMuted,
    )
    OutlinedButton(onClick = viewModel::closeTelegramSheet, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_done_11a676)) }
}

/** Opens the library channel in the Telegram app, or says Telegram isn't installed. */
private fun openInTelegram(context: android.content.Context, chatId: Long) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PhoneTelegram.channelLink(chatId)))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, context.getString(R.string.telegram_library_install_telegram), android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Import → Telegram → Hidden videos: titles removed from the library, and deletions still waiting for Telegram. */
@Composable
private fun HiddenVideosDialog(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val titles = viewModel.hiddenTelegramTitles
    AlertDialog(
        onDismissRequest = { viewModel.showHiddenVideos = false },
        title = { Text(ui.getString(R.string.ui_hidden_videos_e92f75)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    titles == null -> Text(ui.getString(R.string.ui_loading_ba3bbb), color = VlcMuted)
                    titles.isEmpty() -> Text(ui.getString(R.string.ui_nothing_is_hidden_videos_you_remove_from_the_library_a_1d6be6), color = VlcMuted)
                    else -> titles.forEach { title ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(title.title, maxLines = 2)
                                if (title.state == "deleting") {
                                    Text(
                                        title.lastError?.let { ui.getString(R.string.ui_deleting_from_telegram_retrying_b48c27, it) } ?: ui.getString(R.string.ui_deleting_from_telegram_230981),
                                        color = VlcMuted, fontSize = 12.sp,
                                    )
                                }
                            }
                            if (title.state == "hidden") TextButton(onClick = { viewModel.restoreHiddenVideo(title) }) { Text(ui.getString(R.string.ui_restore_a76e13)) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.showHiddenVideos = false }) { Text(ui.getString(R.string.ui_close_7d9eb7)) } },
    )
}
