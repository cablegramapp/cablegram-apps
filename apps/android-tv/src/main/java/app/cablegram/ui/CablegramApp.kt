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
                )
                is ScreenState.ProfilePicker -> {
                    var digits by remember(screen.pinPromptFor?.id, screen.pinError) { mutableStateOf("") }
                    // Back returns to the library after "switch profile"; at app start it asks before exiting.
                    val pickerContext = androidx.compose.ui.platform.LocalContext.current
                    var exitArmedAt by remember { mutableStateOf(0L) }
                    BackHandler(enabled = screen.pinPromptFor == null) {
                        if (viewModel.returnToLibraryFromPicker()) return@BackHandler
                        val now = System.currentTimeMillis()
                        if (now - exitArmedAt < 2_500L) {
                            (pickerContext as? android.app.Activity)?.finish()
                        } else {
                            exitArmedAt = now
                            android.widget.Toast.makeText(pickerContext, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
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
                        onSelect = viewModel::chooseProfile,
                        onStartPairing = viewModel::startProfilePairing,
                        onSignOut = viewModel::signOut,
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
                    )
                    Unit
                }
                is ScreenState.SessionActions -> SessionActionsScreen(screen.message, viewModel::showProfilePicker, viewModel::signOutProfile, viewModel::closeSessionActions)
                is ScreenState.Library -> LibraryScreen(
                    videos = viewModel.libraryVideos,
                    isRefreshing = viewModel.isLibraryRefreshing,
                    onRefresh = viewModel::refreshLibrary,
                    onPlay = viewModel::play,
                    onToggleMyList = viewModel::toggleMyList,
                    selectedType = viewModel.librarySection,
                    onTypeSelected = viewModel::selectLibrarySection,
                    onPlayLive = viewModel::playLiveChannel,
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
                    telegramViaPhone = viewModel.telegram.viaPhone.collectAsState().value,
                    onTelegramViaPhone = viewModel.telegram::setViaPhone,
                    onTelegramConnect = viewModel.telegram::connectStandalone,
                    onTelegramCancel = viewModel.telegram::cancelConnect,
                )
                is ScreenState.Resolving -> PrepareStatusScreen(
                    screen = screen,
                    onCancel = viewModel::cancelResolving,
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
                    onRenewPlayback = { viewModel.renewPlayback(screen.video.id) },
                    onBack = viewModel::closePlayer,
                )
                is ScreenState.TelegramPassword -> TelegramPasswordScreen(
                    status = viewModel.telegram.status.collectAsState().value,
                    onPassword = viewModel.telegram::submitPassword,
                    onAskPhone = viewModel.telegram::askPhoneForPassword,
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
                    onSignOut = if (screen.canSignOut) viewModel::signOut else null,
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
    onSelect: (Profile) -> Unit,
    onStartPairing: () -> Unit,
    onSignOut: () -> Unit,
    onPinDigit: (Char) -> Unit,
    onPinSubmit: () -> Unit,
    onPinDelete: () -> Unit,
    onPinCancel: () -> Unit,
    pinBuffer: String,
) {
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
                    Text("Enter PIN", color = Muted, fontSize = 16.sp)
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
                            state.pending -> "Checking…"
                            state.pinError != null -> state.pinError
                            else -> "Choose the digits, then OK."
                        },
                        color = if (state.pinError != null && !state.pending) Warning else Muted,
                        fontSize = 15.sp,
                    )
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = onPinCancel, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                        Text("Cancel")
                    }
                }
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
                        PinKey(label = "OK", focused = focusedDigit == "ok", onFocus = { focusedDigit = "ok" }, onClick = onPinSubmit)
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
            Text("Who's watching?", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(10.dp))
            Text(
                state.message ?: "Choose your profile",
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
            Button(onClick = onSignOut, colors = ButtonDefaults.colors(containerColor = Color.Transparent, contentColor = Muted)) {
                Text("Unpair this TV")
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
) {
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().padding(56.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.width(560.dp).clip(RoundedCornerShape(18.dp)).background(PanelSoft).border(1.dp, PanelRaised, RoundedCornerShape(18.dp)).padding(42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            BrandMark()
            Text("TV session", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Choose an action", color = Muted, fontSize = 17.sp)
            Button(onClick = onSwitchProfile, modifier = Modifier.fillMaxWidth()) {
                Text("Switch profile")
            }
            Button(onClick = onSignOutProfile, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.colors(containerColor = Panel)) {
                Text("Back")
            }
            message?.let { Text(it, color = Warning, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun NewProfileChoice(enabled: Boolean, requestFocus: Boolean, onClick: () -> Unit) {
    ProfileTile(
        label = "Add profile",
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
private fun LoadingScreen(label: String = "Opening your library...") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark()
            Spacer(Modifier.height(24.dp))
            Text(label, color = Muted, fontSize = 18.sp)
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
) {
    BackHandler(onBack = onBack)
    if (nameRequired) {
        Box(Modifier.fillMaxSize().padding(72.dp), contentAlignment = Alignment.Center) {
            Text(
                "Pair this TV from the Cablegram app on your phone.",
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
            Text("Pair this TV", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Text("In the Cablegram phone app, choose Pair TV and scan the code, or type this PIN.", color = Muted, fontSize = 17.sp, lineHeight = 24.sp)
            Spacer(Modifier.height(26.dp))
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
                    Text("New PIN")
                }
                if (canCancel) {
                    Button(onClick = onBack, colors = ButtonDefaults.colors(containerColor = PanelRaised)) {
                        Text("Cancel")
                    }
                }
            }
            Spacer(Modifier.height(26.dp))
            Text(
                "Keep the phone and TV on the same Wi‑Fi. Your videos stay on your phone.",
                color = Slate,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "This TV: ${tvLanIp ?: "no Wi‑Fi address yet"}",
                color = Muted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
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
                Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Phone pairing QR code")
            }
            Spacer(Modifier.height(14.dp))
            Text("Scan with the Cablegram phone app", color = Muted, fontSize = 15.sp)
        }
    }
}

@Composable
private fun ErrorScreen(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onRemove: (() -> Unit)?,
    onConvert: (() -> Unit)?,
    onSignOut: (() -> Unit)?,
    onBack: (() -> Unit)? = null,
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().padding(72.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(PanelSoft)
                .border(1.dp, Outline, RoundedCornerShape(22.dp))
                .padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Coral.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", color = Coral, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(
                message,
                color = Muted,
                fontSize = 17.sp,
                lineHeight = 25.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val retryFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) {
                    // Let the Button start observing focus before focusing it.
                    androidx.compose.runtime.withFrameNanos { }
                    runCatching { retryFocus.requestFocus() }
                }
                // After a deleted video, "Try again" reloads the library; "Back to library" leaves at once.
                if (onBack != null) {
                    Button(onClick = onBack, modifier = Modifier.focusRequester(retryFocus)) { Text("Back to library") }
                    Button(onClick = onRetry, colors = ButtonDefaults.colors(containerColor = Panel)) { Text("Try again") }
                } else {
                    Button(onClick = onRetry, modifier = Modifier.focusRequester(retryFocus)) { Text("Try again") }
                }
                if (onConvert != null) Button(onClick = onConvert) { Text("Convert anyway") }
                if (onRemove != null) {
                    Button(onClick = onRemove, colors = ButtonDefaults.colors(containerColor = Coral)) { Text("Remove") }
                }
                if (onSignOut != null) {
                    Button(onClick = onSignOut, colors = ButtonDefaults.colors(containerColor = Panel)) {
                        Text("Unpair TV")
                    }
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
            Text("Sign in ${prompt.phoneName} to your Telegram?", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(
                "A phone in your household asked this TV to sign it in to your Telegram account. Allow only if you just " +
                    "chose \"Use the QR code on your TV\" on your own phone. Allowing gives that phone full access to your Telegram.",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 17.sp,
                lineHeight = 24.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.tv.material3.Button(
                    onClick = { viewModel.telegram.answerPhoneLogin(false) },
                    modifier = Modifier.focusRequester(denyFocus),
                ) { Text("Don't allow") }
                androidx.tv.material3.Button(onClick = { viewModel.telegram.answerPhoneLogin(true) }) { Text("Allow") }
            }
        }
    }
}

