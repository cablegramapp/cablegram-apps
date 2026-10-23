package app.cablegram.ui

import androidx.activity.compose.LocalActivity

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import app.cablegram.CablegramViewModel
import app.cablegram.MainActivity
import app.cablegram.R
import app.cablegram.ScreenState
import app.cablegram.data.DeviceSession
import app.cablegram.data.Profile
import app.cablegram.data.validationError
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun CablegramApp(viewModel: CablegramViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    viewModel.openingSignature?.let { opening ->
        androidx.compose.runtime.key(opening.id) {
            SignatureOpeningScreen(opening.url, onFinished = { viewModel.finishOpeningSignature(opening.id) })
        }
        return
    }
    val activity = LocalActivity.current as? MainActivity
    LaunchedEffect(viewModel.pendingNavCommand) {
        val command = viewModel.pendingNavCommand ?: return@LaunchedEffect
        val keyCode = when (command.command) {
            "select" -> KeyEvent.KEYCODE_DPAD_CENTER
            "move" -> when (command.payload["direction"]?.jsonPrimitive?.content) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP
                "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT
                "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                else -> null
            }
            else -> null
        }
        val invalid = command.validationError()
        // Review fix: while the TV asks whether a phone may use the owner's Telegram, only the physical
        // remote can answer. A phone must not be able to press OK on its own request.
        val telegramPromptOpen = viewModel.telegram.phoneLoginPrompt.value != null
        val reason = if (invalid != null) invalid else if (telegramPromptOpen) "blocked_by_telegram_prompt"
        else if (keyCode == null || activity == null) "not_applicable"
        else runCatching {
            val down = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            val up = activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            if (down || up) null else "not_handled"
        }.getOrElse { "execution_failed" }
        viewModel.consumeNavCommand(command.id, reason)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Ink, InkDeep),
                ),
            ),
    ) {
        AnimatedContent(
            targetState = viewModel.screen,
            contentKey = { screen ->
                when (screen) {
                    ScreenState.Loading -> "loading"
                    is ScreenState.Pairing -> "pairing"
                    is ScreenState.ProfilePicker -> "profiles"
                    is ScreenState.SessionActions -> "session"
                    ScreenState.Library -> "library"
                    is ScreenState.Resolving -> "resolving:${screen.video.id}"
                    is ScreenState.Player -> "player:${screen.video.id}"
                    is ScreenState.Error -> "error:${screen.title}"
                    is ScreenState.TelegramPassword -> "telegram-password"
                }
            },
            transitionSpec = {
                val involvesPlayback = initialState is ScreenState.Resolving
                    || targetState is ScreenState.Resolving
                    || initialState is ScreenState.Player
                    || targetState is ScreenState.Player
                if (involvesPlayback) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = "screen",
        ) { screen ->
            when (screen) {
                ScreenState.Loading -> LoadingScreen()
                is ScreenState.Pairing -> PairingScreen(
                    screen.session,
                    screen.nameRequired,
                    viewModel::retry,
                    viewModel::cancelProfilePairing,
                    canCancel = viewModel.hasProfiles,
                    tvLanIp = viewModel.tvLanIp,
                    ownServerUrl = viewModel.serverUrl.takeIf { !viewModel.usesDefaultServer },
                    checkOwnServer = viewModel::checkOwnServer,
                    onUseOwnServer = viewModel::setServerUrl,
                    onReturnToCablegram = viewModel::returnToCablegram,
                )
                is ScreenState.ProfilePicker -> {
                    var digits by remember(screen.pinPromptFor?.id, screen.pinError) { mutableStateOf("") }
                    // Back returns to the library after "switch profile"; at app start it asks before exiting.
                    val pickerContext = androidx.compose.ui.platform.LocalContext.current
                    var exitArmedAt by remember { mutableStateOf(0L) }
                    BackHandler(enabled = screen.pinPromptFor == null && screen.awaitingPhoneFor == null) {
                        if (viewModel.returnToLibraryFromPicker()) return@BackHandler
                        val now = System.currentTimeMillis()
                        if (now - exitArmedAt < 2_500L) {
                            viewModel.leaveCastPicker()
                            (pickerContext as? android.app.Activity)?.finish()
                        } else {
                            exitArmedAt = now
                            android.widget.Toast.makeText(pickerContext, ui.getString(R.string.ui_press_back_again_to_exit_e12dfa), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    // R-4: hardware/remote number keys also feed the keypad.
                    PinKeyInterceptor(
                        enabled = screen.pinPromptFor != null,
                        onDigit = { c -> if (digits.length < 8) { digits += c; viewModel.onPinDigit() } },
                        onDelete = { digits = digits.dropLast(1) },
                        onSubmit = { viewModel.submitProfilePin(digits); digits = "" },
                        onCancel = viewModel::cancelProfilePin,
                    )
                    ProfilePickerScreen(
                        state = screen,
                        castBanner = viewModel.castPickerBanner,
                        onSelect = viewModel::chooseProfile,
                        onStartPairing = viewModel::startProfilePairing,
                        onSignOut = viewModel::signOut,
                        sharedTv = viewModel.sharedTv,
                        onPinDigit = { c -> if (digits.length < 8) { digits += c; viewModel.onPinDigit() } },
                        onPinDelete = { digits = digits.dropLast(1) },
                        onPinSubmit = {
                            viewModel.submitProfilePin(digits)
                            digits = ""
                        },
                        onPinCancel = {
                            digits = ""
                            viewModel.cancelProfilePin()
                        },
                        pinBuffer = digits,
                        onApprovalCancel = viewModel::cancelProfileApproval,
                    )
                    Unit
                }
                is ScreenState.SessionActions -> SessionActionsScreen(
                    screen.message, viewModel::showProfilePicker, viewModel::signOutProfile, viewModel::closeSessionActions,
                    sharedTv = viewModel.sharedTv, onUnpair = viewModel::unpairFromHere,
                )
                is ScreenState.Library -> { LibraryScreen(
                    videos = viewModel.libraryVideos,
                    isRefreshing = viewModel.isLibraryRefreshing,
                    onRefresh = viewModel::refreshLibrary,
                    onPlay = viewModel::play,
                    onToggleMyList = viewModel::toggleMyList,
                    selectedType = viewModel.librarySection,
                    onTypeSelected = viewModel::selectLibrarySection,
                    profileName = viewModel.activeProfileName,
                    profileAvatarUrl = viewModel.activeProfileAvatarUrl,
                    onSwitchProfile = viewModel::showSessionActions,
                    restoredFocusId = viewModel.libraryFocusItemId,
                    restoredShowKey = viewModel.libraryShowKey,
                    onExplorerFocus = viewModel::rememberLibraryExplorer,
                    isDemoMode = viewModel.isDemoMode,
                    adHeadline = viewModel.libraryAds?.takeIf { it.enabled }?.headline,
                    adBody = viewModel.libraryAds?.takeIf { it.enabled }?.body,
                    tvLanIp = viewModel.tvLanIp,
                    telegramStatus = viewModel.telegram.status.collectAsState().value,
                    onTelegramPassword = viewModel.telegram::submitPassword,
                    onTelegramAskPhone = viewModel.telegram::askPhoneForPassword,
                    onTelegramCancelPasswordRequest = viewModel.telegram::cancelPhonePasswordRequest,
                    telegramViaPhone = viewModel.telegram.viaPhone.collectAsState().value,
                    onTelegramViaPhone = viewModel.telegram::setViaPhone,
                    onTelegramConnect = viewModel.telegram::connectStandalone,
                    onTelegramSignInWithoutPassword = viewModel.telegram::signInWithoutPassword,
                    onTelegramCancel = viewModel.telegram::cancelConnect,
                    serverUrl = viewModel.serverUrl,
                    onReturnToCablegram = viewModel::returnToCablegram.takeIf { !viewModel.usesDefaultServer },
                )
                ConnectedWelcomeDialog(viewModel.connectedWelcome, viewModel::dismissConnectedWelcome) }
                is ScreenState.Resolving -> PrepareStatusScreen(
                    screen = screen,
                    onCancel = viewModel::cancelResolving,
                    onClipStarted = { viewModel.signatureClipStarted(screen.signatureAttemptId) },
                    onStartNow = { viewModel.startReadyPlaybackNow(screen.signatureAttemptId) },
                )
                is ScreenState.Player -> PlayerScreen(
                    videoId = screen.video.id,
                    title = screen.video.displayTitle(),
                    playback = screen.playback,
                    isLive = screen.video.mediaType == "live",
                    durationSeconds = screen.video.durationSeconds,
                    onState = { position, duration, isPlaying, volume, muted, engine -> viewModel.reportPlaybackState(screen.video.id, position, duration, isPlaying, volume, muted, engine) },
                    remoteCommand = viewModel.pendingPlayerCommand,
                    remoteTitleCommandId = viewModel.pendingRemoteTitleId,
                    onRemoteCommandConsumed = viewModel::consumePlayerCommand,
                    onRemotePlaybackResult = { reason -> viewModel.remotePlaybackResult(screen.video.id, reason) },
                    onPlayerEvent = viewModel::castPlayerEvent,
                    onRenewPlayback = { viewModel.renewPlayback(screen.video.id) },
                    onBack = viewModel::closePlayer,
                )
                is ScreenState.TelegramPassword -> TelegramPasswordScreen(
                    status = viewModel.telegram.status.collectAsState().value,
                    onPassword = viewModel.telegram::submitPassword,
                    onAskPhone = viewModel.telegram::askPhoneForPassword,
                    onCancelPasswordRequest = viewModel.telegram::cancelPhonePasswordRequest,
                    onSignInWithoutPassword = viewModel.telegram::signInWithoutPassword,
                    onSignedIn = { viewModel.play(screen.video) },
                    onPlayThroughPhone = { always -> viewModel.playThroughPhone(screen.video, always) },
                    onBack = viewModel::closePlayer,
                )
                is ScreenState.Error -> ErrorScreen(
                    title = screen.title,
                    message = screen.message,
                    onRetry = viewModel::retry,
                    onRemove = screen.removableVideo?.let { video -> { viewModel.removeUnavailableVideo(video) } },
                    onConvert = screen.convertibleVideo?.let { video -> { viewModel.requestTranscode(video) } },
                    // On a shared TV only the open account can be removed here; never every account (CAB-65).
                    onSignOut = if (screen.canSignOut && (!viewModel.sharedTv || viewModel.hasActiveAccount)) viewModel::unpairFromHere else null,
                    onBack = if (screen.canGoBack) viewModel::closePlayer else null,
                )
            }
        }
    }
    TelegramPhoneLoginPrompt(viewModel)
}

@Composable
private fun ProfilePickerScreen(
    state: ScreenState.ProfilePicker,
    castBanner: String?,
    onSelect: (Profile) -> Unit,
    onStartPairing: () -> Unit,
    onSignOut: () -> Unit,
    sharedTv: Boolean,
    onPinDigit: (Char) -> Unit,
    onPinSubmit: () -> Unit,
    onPinDelete: () -> Unit,
    onPinCancel: () -> Unit,
    pinBuffer: String,
    onApprovalCancel: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    state.awaitingPhoneFor?.let { profile ->
        PhoneApprovalPanel(profile, sharedTv, onApprovalCancel)
        return
    }
    // R-4 / FR-008: PIN entry overlay for a protected profile; a 4-8 digit code
    // on the D-pad/removable keypad unlocks the profile for this session.
    val pinProfile = state.pinPromptFor
    if (pinProfile != null) {
        var focusedDigit by remember { mutableStateOf("1") }
        LaunchedEffect(pinProfile.id) { focusedDigit = "1" }
        BackHandler(onBack = onPinCancel)
        // Two columns so the whole keypad (including 0 and OK) fits a 1080p
        // TV: remotes such as Chromecast's have no number keys, so the
        // on-screen keypad is the only way in.
        Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(PanelSoft)
                    .border(1.dp, Outline, RoundedCornerShape(20.dp))
                    .padding(horizontal = 40.dp, vertical = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.width(300.dp)) {
                    BrandMark()
                    Spacer(Modifier.height(24.dp))
                    Text(ui.getString(R.string.ui_enter_pin_f246c7), color = Muted, fontSize = 16.sp)
                    Text(pinProfile.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(maxOf(4, pinBuffer.length)) { index ->
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(if (index < pinBuffer.length) Acid else Outline),
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        when {
                            state.pending -> ui.getString(R.string.ui_checking_ec963f)
                            state.pinError != null -> state.pinError
                            else -> ui.getString(R.string.ui_choose_the_digits_then_ok_30a667)
                        },
                        color = if (state.pinError != null && !state.pending) Warning else Muted,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = onPinCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                        Text(ui.getString(R.string.ui_cancel_19766e))
                    }
                }
                LtrControls {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { digit ->
                                    PinKey(
                                        label = digit,
                                        focused = focusedDigit == digit,
                                        requestFocus = digit == "1",
                                        onFocus = { focusedDigit = digit },
                                        onClick = { onPinDigit(digit.first()) },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PinKey(label = "⌫", focused = focusedDigit == "del", onFocus = { focusedDigit = "del" }, onClick = onPinDelete)
                            PinKey(label = "0", focused = focusedDigit == "0", onFocus = { focusedDigit = "0" }, onClick = { onPinDigit('0') })
                            PinKey(label = ui.getString(R.string.ui_ok_565339), focused = focusedDigit == "ok", onFocus = { focusedDigit = "ok" }, onClick = onPinSubmit)
                        }
                    }
                }
            }
        }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(PanelRaised.copy(alpha = 0.55f), Color.Transparent), radius = 1400f)),
    ) {
        Box(Modifier.padding(start = 56.dp, top = 40.dp)) { BrandMark() }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(ui.getString(R.string.ui_who_s_watching_d0ec3b), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            castBanner?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color.White, fontSize = 18.sp, maxLines = 2,
                    modifier = Modifier.background(PanelSoft, RoundedCornerShape(10.dp)).padding(12.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(
                state.message ?: ui.getString(R.string.ui_choose_your_profile_a946b9),
                color = if (state.message != null && !state.pending) Warning else Muted,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(40.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                state.profiles.forEachIndexed { index, profile ->
                    ProfileChoice(profile, !state.pending, requestFocus = index == 0, onSelect)
                }
                NewProfileChoice(enabled = !state.pending, requestFocus = state.profiles.isEmpty(), onClick = onStartPairing)
            }
            Spacer(Modifier.height(36.dp))
            if (sharedTv) {
                // CAB-65: several accounts use this TV. Unpairing everyone from here would let anyone remove the others.
                Text(
                    ui.getString(R.string.ui_several_accounts_use_this_tv_to_remove_one_open_one_of_f713b3),
                    color = Muted,
                    fontSize = 15.sp,
                )
            } else {
                val unpair = remember { UnpairConfirmState() }
                UnpairConfirmDialog(unpair, onSignOut)
                Button(onClick = unpair::ask, colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Muted)) {
                    Text(ui.getString(R.string.ui_unpair_this_tv_c7796b))
                }
            }
        }
    }
}

/** Profile lock: the TV waits until a phone in the household allows this profile. */
@Composable
private fun PhoneApprovalPanel(profile: Profile, sharedTv: Boolean, onCancel: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    BackHandler(onBack = onCancel)
    val cancelFocus = remember { FocusRequester() }
    LaunchedEffect(profile.id) { runCatching { cancelFocus.requestFocus() } }
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(PanelSoft)
                .border(1.dp, Outline, RoundedCornerShape(20.dp))
                .padding(horizontal = 40.dp, vertical = 32.dp),
        ) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text(ui.getString(R.string.ui_allow_on_your_phone_f78aa2), color = Muted, fontSize = 16.sp)
            Text(profile.name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))
            Text(
                if (sharedTv) {
                    ui.getString(R.string.ui_other_accounts_use_this_tv_too_so_this_account_s_phone_9a6f28)
                } else {
                    ui.getString(R.string.ui_this_account_is_locked_open_cablegram_on_a_phone_signe_af553c)
                },
                color = Muted,
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(26.dp))
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.colors(containerColor = PanelRaised),
                modifier = Modifier.focusRequester(cancelFocus),
            ) {
                Text(ui.getString(R.string.ui_cancel_19766e))
            }
        }
    }
}

/**
 * R-4: captures hardware remote number keys (and Back) during PIN entry so
 * physical remotes can type the code without focusing the on-screen keypad.
 */
@Composable
private fun PinKeyInterceptor(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    if (!enabled) return
    val view = LocalView.current
    DisposableEffect(view) {
        val handler: (KeyEvent) -> Boolean = { event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when {
                    event.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        val digit = ('0' + (event.keyCode - KeyEvent.KEYCODE_0))
                        onDigit(digit); true
                    }
                    event.keyCode == KeyEvent.KEYCODE_DEL -> { onDelete(); true }
                    // Select/Enter press the focused keypad key (digits or OK);
                    // treating them as "submit" here would fight the keypad.
                    event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> { onSubmit(); true }
                    event.keyCode == KeyEvent.KEYCODE_BACK -> { onCancel(); true }
                    else -> false
                }
            } else false
        }
        view.setOnKeyListener { _, _, event -> handler(event) }
        onDispose { view.setOnKeyListener(null) }
    }
}

@Composable
private fun PinKey(
    label: String,
    focused: Boolean,
    requestFocus: Boolean = false,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (requestFocus) runCatching { focusRequester.requestFocus() }
    }
    Box(
        modifier = Modifier
            .size(width = 88.dp, height = 52.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.hasFocus) onFocus() }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Acid else PanelRaised)
            .border(2.dp, if (focused) Paper else Color.Transparent, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Ink else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionActionsScreen(
    message: String?,
    onSwitchProfile: () -> Unit,
    onSignOutProfile: () -> Unit,
    onBack: () -> Unit,
    sharedTv: Boolean,
    onUnpair: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().padding(56.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(560.dp).clip(RoundedCornerShape(18.dp)).background(PanelSoft).border(1.dp, PanelRaised, RoundedCornerShape(18.dp)).padding(42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BrandMark()
            Text(ui.getString(R.string.ui_tv_session_dca893), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(ui.getString(R.string.ui_choose_an_action_769239), color = Muted, fontSize = 17.sp)
            Button(onClick = onSwitchProfile, modifier = Modifier.fillMaxWidth()) {
                Text(ui.getString(R.string.ui_switch_profile_c1e0da))
            }
            Button(onClick = onSignOutProfile, modifier = Modifier.fillMaxWidth()) {
                Text(ui.getString(R.string.ui_sign_out_48f0d3))
            }
            // CAB-65: on a shared TV this removes only the open account; with one account it unpairs the TV.
            val unpair = remember { UnpairConfirmState() }
            if (sharedTv) {
                UnpairConfirmDialog(
                    unpair, onUnpair,
                    title = ui.getString(R.string.ui_remove_this_account_from_this_tv_7dea07),
                    text = ui.getString(R.string.ui_this_account_and_its_profiles_leave_this_tv_the_other__3f0d5a),
                    confirmLabel = ui.getString(R.string.ui_remove_c3812f),
                )
            } else {
                UnpairConfirmDialog(unpair, onUnpair)
            }
            Button(onClick = unpair::ask, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.colors(containerColor = Panel)) {
                Text(if (sharedTv) ui.getString(R.string.ui_remove_this_account_from_this_tv_b0c050) else ui.getString(R.string.ui_unpair_this_tv_c7796b))
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.colors(containerColor = Panel)) {
                Text(ui.getString(R.string.ui_back_76900f))
            }
            message?.let { Text(it, color = Warning, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun NewProfileChoice(enabled: Boolean, requestFocus: Boolean, onClick: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    ProfileTile(
        label = ui.getString(R.string.ui_add_profile_f964be),
        enabled = enabled,
        requestFocus = requestFocus,
        onClick = onClick,
        background = Color.Transparent,
        dashed = true,
    ) { focused ->
        Text("+", color = if (focused) Ink else Paper.copy(alpha = 0.8f), fontSize = 52.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun ProfileChoice(profile: Profile, enabled: Boolean, requestFocus: Boolean, onSelect: (Profile) -> Unit) {
    val avatarUrl = profile.resolvedAvatarUrl()
    var imageFailed by remember(avatarUrl) { mutableStateOf(false) }
    ProfileTile(
        label = profile.name,
        enabled = enabled,
        requestFocus = requestFocus,
        onClick = { onSelect(profile) },
        background = placeholderGradient(profile.name).first(),
        locked = profile.pinSet,
    ) {
        if (avatarUrl != null && !imageFailed) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = profile.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
            )
        } else {
            Text(profile.name.take(1).uppercase(), color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A large, D-pad friendly profile avatar with a clear focus ring and label. */
@Composable
private fun ProfileTile(
    label: String,
    enabled: Boolean,
    requestFocus: Boolean,
    onClick: () -> Unit,
    background: Color,
    dashed: Boolean = false,
    locked: Boolean = false,
    content: @Composable (focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus, enabled) {
        if (requestFocus && enabled) runCatching { focusRequester.requestFocus() }
    }
    val scale by androidx.compose.animation.core.animateFloatAsState(if (focused) 1.1f else 1f, label = "profile-scale")
    Column(
        modifier = Modifier
            .width(136.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(116.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (enabled) 1f else 0.5f
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(if (dashed && focused) Paper else background)
                    .border(
                        width = if (focused) 4.dp else if (dashed) 2.dp else 0.dp,
                        color = if (focused) Paper else if (dashed) Outline else Color.Transparent,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) { content(focused) }
            if (locked) {
                Text(
                    "PIN",
                    color = Ink,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Acid)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            label,
            color = if (focused) Paper else Muted,
            fontSize = 18.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingScreen(label: String? = null) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text(label ?: ui.getString(R.string.ui_opening_your_library_2d9a4d), color = Muted, fontSize = 18.sp)
        }
    }
}

@Composable
private fun PairingScreen(
    session: DeviceSession,
    nameRequired: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    canCancel: Boolean = false,
    tvLanIp: String? = null,
    /** The self-hosted server this TV pairs with; null for Cablegram's own. */
    ownServerUrl: String? = null,
    checkOwnServer: suspend (String) -> app.cablegram.data.ServerProbe = { app.cablegram.data.ServerProbe.Unreachable },
    onUseOwnServer: (String) -> Unit = {},
    onReturnToCablegram: () -> Unit = {},
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var enteringServer by remember { mutableStateOf(false) }
    if (enteringServer) {
        OwnServerForm(check = checkOwnServer, onUse = { enteringServer = false; onUseOwnServer(it) }, onCancel = { enteringServer = false })
        return
    }
    // With profiles to return to, Back goes back to them. Without, Back leaves the app as usual: catching it here kept
    // the app open and stopped the TV from noticing that a phone had paired it.
    BackHandler(enabled = canCancel, onBack = onBack)
    if (nameRequired) {
        Box(Modifier.fillMaxSize().padding(72.dp), contentAlignment = Alignment.Center) {
            Text(
                ui.getString(R.string.ui_pair_this_tv_from_the_cablegram_app_on_your_phone_894b76),
                color = Aqua,
                fontSize = 28.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        return
    }

    val pairLink = session.phoneQrUrl ?: session.qrUrl
    val qrBitmap = remember(pairLink) { createQrBitmap(pairLink, 520) }
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = 56.dp, vertical = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BrandMark()
            Spacer(Modifier.height(28.dp))
            Text(ui.getString(R.string.ui_pair_this_tv_a16f6b), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Text(ui.getString(R.string.ui_scan_the_code_with_your_phone_s_camera_or_type_this_pi_f54954), color = Muted, fontSize = 17.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(26.dp))
            LtrControls {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    session.pin.forEach { digit ->
                        Box(
                            Modifier
                                .size(width = 58.dp, height = 74.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PanelRaised)
                                .border(1.dp, Outline, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(digit.toString(), color = Acid, fontSize = 40.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val newPinFocus = remember { FocusRequester() }
                var newPinFocused by remember { mutableStateOf(false) }
                LaunchedEffect(session.sessionId) {
                    // tv-material Buttons draw focus from interactions collected in
                    // their own effect; focusing in the same frame (this effect runs
                    // first) leaves a focused button without its highlight. Wait a
                    // frame, and retry because slow TVs may still be transitioning.
                    repeat(20) {
                        kotlinx.coroutines.delay(100)
                        if (newPinFocused) return@LaunchedEffect
                        runCatching { newPinFocus.requestFocus() }
                    }
                }
                Button(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(newPinFocus).onFocusChanged { newPinFocused = it.hasFocus },
                    colors = ButtonDefaults.colors(containerColor = PanelRaised),
                ) {
                    Text(ui.getString(R.string.ui_new_pin_6d8849))
                }
                if (ownServerUrl == null) {
                    Button(onClick = { enteringServer = true }, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                        Text(ui.getString(R.string.ui_use_your_own_server_622a50))
                    }
                } else {
                    Button(onClick = onReturnToCablegram, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                        Text(ui.getString(R.string.ui_return_to_cablegram_e2e1d7))
                    }
                }
                if (canCancel) {
                    Button(onClick = onBack, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                        Text(ui.getString(R.string.ui_cancel_19766e))
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                ui.getString(R.string.ui_keep_the_phone_and_tv_on_the_same_wi_fi_your_videos_st_898a8b),
                color = Slate,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                ui.getString(R.string.ui_this_tv_036568, tvLanIp ?: ui.getString(R.string.ui_no_wi_fi_address_yet_614231)),
                color = Muted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (ownServerUrl != null) {
                Spacer(Modifier.height(6.dp))
                Text(ui.getString(R.string.ui_server_1_s_dfe9cc, ownServerUrl), color = Muted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(PanelSoft)
                .border(1.dp, Outline, RoundedCornerShape(24.dp))
                .padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = ui.getString(R.string.ui_phone_pairing_qr_code_6fc5a0))
            }
            Spacer(Modifier.height(14.dp))
            Text(ui.getString(R.string.ui_scan_with_your_phone_s_camera_91a2f3), color = Muted, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(ui.getString(R.string.ui_or_open_app_cablegram_app_and_enter_the_pin_8e31cb), color = Slate, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun ErrorScreen(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onRemove: (() -> Unit)?,
    onConvert: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
    errorVideoUrl: String? = null,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (onBack != null) BackHandler(onBack = onBack)
    UnhappyScenarioFrame(eventKey = "$title:$message", videoUrl = errorVideoUrl) {
            Text("CABLEGRAM", color = Coral, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Paper, lineHeight = 31.sp)
            Text(message, color = Muted, fontSize = 16.sp, lineHeight = 23.sp)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    // Let the Button start observing focus before focusing it.
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { retryFocus.requestFocus() }
                }
                // After a deleted video, "Try again" reloads the library; "Back to library" leaves at once.
                if (onBack != null) {
                    Button(onClick = onBack, modifier = Modifier.focusRequester(retryFocus)) { Text(ui.getString(R.string.ui_back_to_library_008e42)) }
                    Button(onClick = onRetry, colors = ButtonDefaults.colors(containerColor = Panel)) { Text(ui.getString(R.string.ui_try_again_d8b839)) }
                } else {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text(ui.getString(R.string.ui_try_again_d8b839)) }
                }
                if (onConvert != null) Button(onClick = onConvert) { Text(ui.getString(R.string.ui_convert_anyway_2c1857)) }
                if (onRemove != null) {
                    Button(onClick = onRemove, colors = ButtonDefaults.colors(containerColor = Coral)) { Text(ui.getString(R.string.ui_remove_c3812f)) }
                }
                if (onSignOut != null) {
                    val unpair = remember { UnpairConfirmState() }
                    UnpairConfirmDialog(unpair, onSignOut)
                    Button(onClick = unpair::ask, colors = ButtonDefaults.colors(containerColor = Panel)) {
                        Text(ui.getString(R.string.ui_unpair_tv_fbd044))
                    }
                }
            }
    }
}

@Composable
internal fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.cablegram_brand_mark),
            contentDescription = "Cablegram",
            modifier = Modifier.height(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text("Cablegram", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, letterSpacing = 0.sp)
    }
}

internal fun createQrBitmap(content: String, size: Int): Bitmap {
    val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (row in 0 until size) {
        for (column in 0 until size) {
            pixels[row * size + column] = if (matrix[column, row]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/**
 * Review fix: "Sign in <phone> to your Telegram?" A phone that asked to be signed in through this TV only
 * gets the session if the owner allows it here, with the TV's own remote (remote commands are blocked).
 */
@Composable
private fun TelegramPhoneLoginPrompt(viewModel: CablegramViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val prompt = viewModel.telegram.phoneLoginPrompt.collectAsState().value ?: return
    val denyFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(prompt.requestId) { runCatching { denyFocus.requestFocus() } }
    androidx.activity.compose.BackHandler { viewModel.telegram.answerPhoneLogin(false) }
    Box(
        Modifier.fillMaxSize().background(Color(0xF20B0D10)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(760.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("TELEGRAM", color = Color(0xFFF3B86A), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
            Text(ui.getString(R.string.ui_sign_in_to_your_telegram_a2b2cf, prompt.phoneName), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                ui.getString(R.string.ui_a_phone_in_your_household_asked_this_tv_to_sign_it_in__568faf) +
                    ui.getString(R.string.ui_chose_use_the_qr_code_on_your_tv_on_your_own_phone_all_ce22eb),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 17.sp,
                lineHeight = 24.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.tv.material3.Button(
                    onClick = { viewModel.telegram.answerPhoneLogin(false) },
                    modifier = Modifier.focusRequester(denyFocus),
                ) { Text(ui.getString(R.string.ui_don_t_allow_9803bd)) }
                androidx.tv.material3.Button(onClick = { viewModel.telegram.answerPhoneLogin(true) }) { Text(ui.getString(R.string.ui_allow_e213c1)) }
            }
        }
    }
}
