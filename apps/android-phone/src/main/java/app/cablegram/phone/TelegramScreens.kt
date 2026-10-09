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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cablegram.telegram.TelegramSession
import app.cablegram.telegram.TelegramState

private const val PRIVACY_URL = "https://cablegram.app/privacy.html#telegram"

/**
 * The code that talks to Telegram, in the public repository, at the tag this build is released from
 * (`v` + versionName). Update the tag in the URL together with `versionName` in build.gradle.kts.
 */
private const val TELEGRAM_CODE_URL =
    "https://github.com/cablegramapp/cablegram-apps/tree/v0.2.2/apps/android-phone/src/main/java/app/cablegram/telegram"

/**
 * Telegram in Import → Connected storage (spec 004 US1): connect once on the phone, then videos
 * shared into the private "Cablegram library" channel appear in Cablegram.
 */
@Composable
internal fun TelegramStorageEntry(viewModel: PhoneViewModel) {
    if (!PhoneTelegram.configured) return
    val link = viewModel.telegramLink
    val linked = link?.linked == true
    val health = viewModel.telegramHealth
    val context = LocalContext.current
    var confirmDisconnect by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(linked) { if (linked) viewModel.checkTelegramHealth() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).maestro(MaestroIds.TELEGRAM_ENTRY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = VlcOrange)
        Column(Modifier.weight(1f)) {
            Text(if (linked) "Telegram · ${TelegramSession.LIBRARY_TITLE}" else "Telegram", color = Color.White)
            Text(
                when {
                    !linked -> "Use your own Telegram account as cloud storage. Share videos into a private channel and watch them on your TV."
                    health == TelegramHealth.ChannelLost ->
                        "You're no longer in the \"${TelegramSession.LIBRARY_TITLE}\" channel (you left it or it was deleted), so Cablegram can't see its videos."
                    health == TelegramHealth.SignedOut ->
                        "Telegram signed Cablegram out on this phone (for example from Telegram → Devices). Sign in again, or disconnect."
                    else -> "Connected as ${link?.displayName ?: "your account"}. Videos you share into this private channel appear on your TV."
                },
                color = if (health == TelegramHealth.ChannelLost || health == TelegramHealth.SignedOut) VlcOrange else VlcMuted,
                fontSize = 12.sp,
            )
        }
    }
    when {
        !linked -> Button(
            onClick = viewModel::openTelegramSheet,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_CONNECT),
        ) { Text("Connect Telegram") }
        health == TelegramHealth.ChannelLost -> Button(
            onClick = viewModel::recreateTelegramChannel,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_RECREATE),
        ) { Text("Create a new \"${TelegramSession.LIBRARY_TITLE}\"") }
        health == TelegramHealth.SignedOut -> Button(
            onClick = viewModel::signInToTelegramAgain,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) { Text("Sign in to Telegram again") }
        else -> link?.chatId?.let { chatId ->
            OutlinedButton(
                onClick = { openInTelegram(context, chatId) },
                modifier = Modifier.fillMaxWidth().maestro(MaestroIds.TELEGRAM_OPEN),
            ) { Text("Open \"${TelegramSession.LIBRARY_TITLE}\" in Telegram") }
        }
    }
    if (linked && health == TelegramHealth.Ok && PhoneTelegram.wasLinked(context)) {
        var autoApprove by remember { mutableStateOf(PhoneTelegram.autoApproveTvs(context)) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Approve my TVs automatically", color = Color.White)
                Text(
                    "Off: a notification asks before each TV signs in to Telegram. On: paired TVs sign in without asking.",
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
    if (linked && !PhoneTelegram.wasLinked(context)) {
        // The household is linked (from another phone, or before a reinstall) but this phone has no session,
        // so it can't approve TVs, save videos or end TV sessions until it signs in too.
        Text(
            "This phone isn't signed in to Telegram yet, so it can't approve your TVs or save videos to Telegram.",
            color = VlcOrange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp),
        )
        Button(onClick = viewModel::openTelegramSheet, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Sign in on this phone") }
    }
    if (linked && health == TelegramHealth.Ok) {
        OutlinedButton(onClick = viewModel::openHiddenVideos, modifier = Modifier.fillMaxWidth()) { Text("Hidden videos") }
    }
    if (viewModel.showHiddenVideos) HiddenVideosDialog(viewModel)
    if (linked) {
        OutlinedButton(
            onClick = { confirmDisconnect = true },
            modifier = Modifier.fillMaxWidth().maestro(MaestroIds.TELEGRAM_DISCONNECT),
        ) { Text("Disconnect Telegram", color = Color(0xFFFF8A80)) }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Telegram?") },
            text = {
                Text(
                    "Cablegram signs out of Telegram on this phone and on your TVs. Videos stay in your " +
                        "\"${TelegramSession.LIBRARY_TITLE}\" channel; they disappear from Cablegram until you connect again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDisconnect = false; viewModel.disconnectTelegram() }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
            modifier = Modifier.maestroRoot(),
        )
    }
}

/** Phone number → code → (password) → library set up. One request at a time (the session enforces it). */
@Composable
internal fun TelegramConnectSheet(viewModel: PhoneViewModel) {
    BackHandler(onBack = viewModel::closeTelegramSheet)
    val started = viewModel.telegramStarted || PhoneTelegram.existing() != null
    Column(
        Modifier
            .fillMaxSize()
            .background(VlcBlack.copy(alpha = 0.98f))
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
            .maestro(MaestroIds.TELEGRAM_SHEET),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = viewModel::closeTelegramSheet) { Text("Close", color = VlcOrange) }
        // A household that is already linked still needs the intro on a phone that has no session of its own.
        val context = LocalContext.current
        if (!started && !PhoneTelegram.wasLinked(context)) BeforeYouConnect(viewModel) else SignInSteps(viewModel)
    }
}

/** FR-021: what Cablegram gets, what it uses, where credentials go, how to revoke — before any input. */
@Composable
private fun BeforeYouConnect(viewModel: PhoneViewModel) {
    val context = LocalContext.current
    Text("Before you connect Telegram", color = Color.White, style = MaterialTheme.typography.headlineSmall)
    TrustPoint(
        "What Cablegram can do",
        "Like any Telegram app (Telegram Desktop, for example), Cablegram signs in as a device on your account.",
    )
    TrustPoint(
        "What it does",
        "It only reads and manages one private channel, \"${TelegramSession.LIBRARY_TITLE}\". It never reads your chats or contacts. The only thing it ever sends is a video you choose to save with \"Save to Telegram\", into that channel.",
    )
    TrustPoint(
        "Where your details go",
        "Your phone number, login code and password go straight to Telegram. They are never sent to or stored on Cablegram's servers.",
    )
    TrustPoint(
        "You stay in control",
        "You'll see Cablegram in Telegram → Settings → Devices and can end it there at any time. Disconnect in Cablegram signs out everywhere.",
    )
    TrustPoint(
        "You can check this",
        "The Cablegram apps are open source. Anyone can check what they do with your Telegram account. Cablegram's servers are not part of that.",
    )
    TextButton(onClick = {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(TELEGRAM_CODE_URL)))
        }
    }) { Text("Read the code", color = VlcOrange) }
    Text(
        "Cablegram is an unofficial app. It isn't made or endorsed by Telegram; it uses your own Telegram account through Telegram's public API.",
        color = VlcMuted,
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        onClick = viewModel::connectTelegram,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.TELEGRAM_CONTINUE),
    ) { Text("Continue") }
    // FR-023: only when a Home TV is paired; it must already be signed in to the household's Telegram.
    if (viewModel.homeTvForTelegram() != null && viewModel.telegramLink?.linked == true) {
        OutlinedButton(onClick = viewModel::connectTelegramViaTv, modifier = Modifier.fillMaxWidth()) {
            Text("Use the QR code on your TV instead")
        }
        Text(
            "Your TV is already signed in to your Telegram. It approves this phone, so you don't need your phone number or a code.",
            color = VlcMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    OutlinedButton(onClick = viewModel::closeTelegramSheet, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
    TextButton(onClick = {
        runCatching {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PRIVACY_URL)))
        }
    }) { Text("How this works", color = VlcOrange) }
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
    val context = LocalContext.current
    val session = remember { PhoneTelegram.session(context) }
    val state by session.state.collectAsState()
    val busy by session.busy.collectAsState()
    val inputError by session.lastError.collectAsState()
    var input by rememberSaveable(state::class) { mutableStateOf("") }
    Text("Connect Telegram", color = Color.White, style = MaterialTheme.typography.headlineSmall)

    val linked = viewModel.telegramLink?.linked == true && viewModel.telegramHealth == TelegramHealth.Ok && PhoneTelegram.wasLinked(context)
    when {
        linked -> Done(viewModel)
        viewModel.telegramMessage != null -> Text(viewModel.telegramMessage!!, color = VlcOrange)
        viewModel.telegramFinishing || state is TelegramState.Ready -> Working("Setting up your \"${TelegramSession.LIBRARY_TITLE}\" channel…")
        else -> when (val s = state) {
            TelegramState.Starting -> Working("Starting Telegram…")
            TelegramState.NeedsPhoneNumber -> {
                Text("Enter the phone number of your Telegram account. It goes to Telegram only.", color = VlcMuted)
                Step(
                    label = "Phone number",
                    hint = "With country code, e.g. +31 6 1234 5678",
                    value = input,
                    onValue = { input = it.take(20) },
                    keyboard = KeyboardType.Phone,
                    busy = busy,
                    error = inputError,
                    action = "Send code",
                    enabled = input.count(Char::isDigit) >= 7,
                ) { session.submitPhoneNumber(input) }
            }
            TelegramState.NeedsCode -> {
                Text("Telegram sent a login code to your Telegram app (or by SMS). Enter it here.", color = VlcMuted)
                Text(
                    "Telegram's message says not to give this code to anyone. You're not giving it to a person: " +
                        "Cablegram uses it to sign in with Telegram directly, like Telegram Desktop.",
                    color = VlcMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Step("Login code", "5 digits", input, { input = it.filter(Char::isDigit).take(8) }, KeyboardType.NumberPassword, busy, inputError, "Continue", input.length >= 5) {
                    session.submitCode(input)
                }
            }
            is TelegramState.NeedsPassword -> {
                Text("Your account has two-step verification. Enter your Telegram password.", color = VlcMuted)
                Text(
                    "Cablegram passes your password to Telegram to sign in, and never stores it or sends it anywhere else.",
                    color = VlcMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Step(
                    label = "Telegram password",
                    hint = s.hint.takeIf { it.isNotBlank() }?.let { "Hint: $it" } ?: "",
                    value = input,
                    onValue = { input = it },
                    keyboard = KeyboardType.Password,
                    busy = busy,
                    error = inputError,
                    action = "Sign in",
                    enabled = input.isNotEmpty(),
                    secret = true,
                ) { session.submitPassword(input) }
            }
            is TelegramState.Failed -> {
                Text(s.message, color = VlcOrange)
                OutlinedButton(onClick = { session.retryStartup() }) { Text("Try again") }
            }
            is TelegramState.WaitingForApproval ->
                if (viewModel.telegramViaTv) Working("Waiting for your TV to approve… Keep Cablegram open on the TV.")
                else Working("Starting Telegram…")
            TelegramState.SignedOut -> Working("Starting Telegram…")
            is TelegramState.Ready -> Unit
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
    ) { Text(if (busy) "Checking…" else action) }
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
    val context = LocalContext.current
    Text("Connected as ${viewModel.telegramLink?.displayName ?: "your account"}.", color = Color.White, style = MaterialTheme.typography.titleMedium)
    when (viewModel.telegramChannelCreated) {
        true -> Text("Cablegram created the private channel \"${TelegramSession.LIBRARY_TITLE}\" in your Telegram, with the Cablegram logo.", color = VlcMuted)
        false -> Text("Cablegram is using the \"${TelegramSession.LIBRARY_TITLE}\" channel that already exists in your Telegram.", color = VlcMuted)
        null -> Unit
    }
    viewModel.telegramLink?.chatId?.let { chatId ->
        Button(onClick = { openInTelegram(context, chatId) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text("Open it in Telegram")
        }
    }
    Text(
        "Telegram will show a new-login alert, possibly \"Someone just got access to your messages\". That's Cablegram " +
            "on \"${PhoneTelegram.deviceModel}\": tap \"Yes, it's me\".",
        color = VlcMuted,
    )
    Text(
        "In Telegram, share or forward videos into the private channel \"${TelegramSession.LIBRARY_TITLE}\". " +
            "They appear in Cablegram and play on your TV straight from Telegram.",
        color = VlcMuted,
    )
    OutlinedButton(onClick = viewModel::closeTelegramSheet, modifier = Modifier.fillMaxWidth()) { Text("Done") }
}

/** Opens the library channel in the Telegram app, or says Telegram isn't installed. */
private fun openInTelegram(context: android.content.Context, chatId: Long) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(PhoneTelegram.channelLink(chatId)))
    try {
        context.startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "Install Telegram to open the channel.", android.widget.Toast.LENGTH_LONG).show()
    }
}

/** Import → Telegram → Hidden videos: titles removed from the library, and deletions still waiting for Telegram. */
@Composable
private fun HiddenVideosDialog(viewModel: PhoneViewModel) {
    val titles = viewModel.hiddenTelegramTitles
    AlertDialog(
        onDismissRequest = { viewModel.showHiddenVideos = false },
        title = { Text("Hidden videos") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    titles == null -> Text("Loading…", color = VlcMuted)
                    titles.isEmpty() -> Text("Nothing is hidden. Videos you remove from the library appear here, and you can restore them.", color = VlcMuted)
                    else -> titles.forEach { title ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(title.title, maxLines = 2)
                                if (title.state == "deleting") {
                                    Text(
                                        title.lastError?.let { "Deleting from Telegram… retrying ($it)" } ?: "Deleting from Telegram…",
                                        color = VlcMuted, fontSize = 12.sp,
                                    )
                                }
                            }
                            if (title.state == "hidden") TextButton(onClick = { viewModel.restoreHiddenVideo(title) }) { Text("Restore") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.showHiddenVideos = false }) { Text("Close") } },
    )
}
