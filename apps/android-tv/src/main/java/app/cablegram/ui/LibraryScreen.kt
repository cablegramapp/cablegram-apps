package app.cablegram.ui

import androidx.compose.runtime.mutableLongStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import app.cablegram.data.Video
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private enum class RailDestination { Home, Player, Library, Settings }

/** Second Back within this window closes the app (see LibraryScreen's BackHandler). */
private const val EXIT_CONFIRM_MS = 2_500L
private const val EXIT_HINT = "Press Back again to exit"

@Composable
internal fun LibraryScreen(
    videos: List<Video>,
    @Suppress("UNUSED_PARAMETER") isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onPlay: (Video) -> Unit,
    onToggleMyList: (videoIds: List<String>, inMyList: Boolean) -> Unit = { _, _ -> },
    selectedType: String,
    onTypeSelected: (String) -> Unit,
    profileName: String,
    profileAvatarUrl: String?,
    onSwitchProfile: () -> Unit,
    restoredFocusId: String? = null,
    restoredShowKey: String? = null,
    onExplorerFocus: (focusItemId: String?, showKey: String?) -> Unit = { _, _ -> },
    isDemoMode: Boolean = false,
    adHeadline: String? = null,
    adBody: String? = null,
    tvLanIp: String? = null,
    telegramStatus: app.cablegram.TvTelegramStatus = app.cablegram.TvTelegramStatus.Off,
    onTelegramPassword: (String) -> Unit = {},
    onTelegramAskPhone: () -> Unit = {},
    telegramViaPhone: Boolean = false,
    onTelegramViaPhone: (Boolean) -> Unit = {},
    onTelegramConnect: () -> Unit = {},
    onTelegramCancel: () -> Unit = {},
) {
    var selectedGenre by remember { mutableStateOf<String?>(null) }
    var selectedShow by remember { mutableStateOf<TvShow?>(null) }
    var focusedEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var rail by remember(selectedType) {
        mutableStateOf(
            when (selectedType) {
                "watchlist" -> RailDestination.Player
                "mylist" -> RailDestination.Library
                else -> RailDestination.Home
            },
        )
    }
    var listNotice by remember { mutableStateOf<String?>(null) }
    val homeFocus = remember { FocusRequester() }
    val profileFocus = remember { FocusRequester() }
    var sidebarFocused by remember { mutableStateOf(false) }
    var profileFocused by remember { mutableStateOf(false) }
    var exitArmedAt by remember { mutableLongStateOf(0L) }
    val activity = LocalActivity.current
    /*
     * Back never leaves the app by surprise:
     *  1. from any section, filter or genre → the main library (Home);
     *  2. on Home with focus in the rows or menu → the profile avatar at the top of the menu;
     *  3. on the avatar → "Press Back again to exit"; a second Back within 2.5 s closes the app.
     * An open show (EpisodeRow) handles Back itself first, returning to the rows.
     */
    BackHandler {
        val onMainView = rail == RailDestination.Home && selectedType == "all" && selectedGenre == null
        when {
            !onMainView -> {
                rail = RailDestination.Home
                selectedGenre = null
                selectedShow = null
                onExplorerFocus(null, null)
                onTypeSelected("all")
                runCatching { homeFocus.requestFocus() }
            }
            !profileFocused -> runCatching { profileFocus.requestFocus() }
            System.currentTimeMillis() - exitArmedAt < EXIT_CONFIRM_MS -> activity?.finish()
            else -> {
                exitArmedAt = System.currentTimeMillis()
                listNotice = EXIT_HINT
            }
        }
    }
    LaunchedEffect(listNotice) {
        val notice = listNotice ?: return@LaunchedEffect
        // The exit hint stays for the whole window in which a second Back exits.
        delay(if (notice == EXIT_HINT) EXIT_CONFIRM_MS else 1_600)
        listNotice = null
    }
    val genres = remember(videos) { videos.flatMap { it.genres }.distinct().sorted() }
    val shelves = remember(videos, selectedType, selectedGenre) {
        buildLibraryShelves(videos, selectedType, selectedGenre)
    }
    val catalogEntries = remember(shelves) { shelves.flatMap { it.entries }.distinctBy { it.id } }
    // Shelves hold filtered episode subsets (Continue Watching keeps only the
    // episode in progress); opening a show always lists every episode.
    val fullShows = remember(videos) {
        toLibraryEntries(videos).filterIsInstance<ShowEntry>().associate { it.id to it.show }
    }
    val restoredShow = remember(fullShows, restoredShowKey) {
        restoredShowKey?.let { key -> fullShows[key] }
    }
    LaunchedEffect(restoredShow?.id) {
        if (restoredShow != null) selectedShow = restoredShow
    }
    val activeShow = selectedShow ?: restoredShow
    LaunchedEffect(restoredFocusId, restoredShowKey) {
        if (restoredFocusId == null && restoredShowKey == null) {
            withFrameNanos { }
            runCatching { homeFocus.requestFocus() }
        }
    }

    val hero: LibraryEntry? = when {
        rail == RailDestination.Settings -> null
        // Resolve by id so the hero reflects refreshed data (My List, progress).
        activeShow != null -> focusedEntry?.id
            ?.let { id -> activeShow.episodes.firstOrNull { it.id == id } }?.let(::MovieEntry)
            ?: ShowEntry(activeShow)
        else -> focusedEntry?.id?.let { id -> catalogEntries.firstOrNull { it.id == id } } ?: catalogEntries.firstOrNull()
    }
    // Debounce the backdrop so scrolling along a row does not decode a
    // full-screen image for every poster the focus passes over.
    val backdropTarget = hero?.backdropUrl
    var backdrop by remember { mutableStateOf(backdropTarget) }
    LaunchedEffect(backdropTarget) {
        delay(220)
        backdrop = backdropTarget
    }

    Box(Modifier.fillMaxSize().background(InkDeep)) {
        AmbientBackdrop(url = backdrop, seed = hero?.title)
        Row(Modifier.fillMaxSize()) {
            CollapsedSidebar(
                destination = rail,
                profileName = profileName,
                profileAvatarUrl = profileAvatarUrl,
                homeFocusRequester = homeFocus,
                onHome = {
                    rail = RailDestination.Home
                    selectedShow = null
                    onExplorerFocus(null, null)
                    onTypeSelected("all")
                },
                onPlayer = {
                    rail = RailDestination.Player
                    selectedShow = null
                    onTypeSelected("watchlist")
                },
                onLibrary = {
                    rail = RailDestination.Library
                    selectedShow = null
                    onTypeSelected("mylist")
                },
                onSettings = {
                    rail = RailDestination.Settings
                    selectedShow = null
                },
                onSwitchProfile = onSwitchProfile,
                profileFocusRequester = profileFocus,
                onFocusChanged = { inSidebar, onProfile ->
                    sidebarFocused = inSidebar
                    profileFocused = onProfile
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 36.dp, end = 40.dp, top = 22.dp),
            ) {
                LibraryTopBar(
                    title = when (rail) {
                        RailDestination.Home -> "Home"
                        RailDestination.Player -> "Continue watching"
                        RailDestination.Library -> "My List"
                        RailDestination.Settings -> "Settings"
                    },
                    selectedType = selectedType,
                    selectedGenre = selectedGenre,
                    showFilters = rail == RailDestination.Home && activeShow == null,
                    menuFocus = homeFocus,
                    onTypeSelected = { type ->
                        rail = RailDestination.Home
                        onTypeSelected(type)
                    },
                    onGenreCycle = {
                        if (genres.isNotEmpty()) {
                            val current = selectedGenre?.let(genres::indexOf) ?: -1
                            selectedGenre = if (current == genres.lastIndex) null else genres[(current + 1) % genres.size]
                        }
                    },
                )
                if (rail == RailDestination.Settings) {
                    AboutSettingsPanel(
                        isDemoMode = isDemoMode,
                        tvLanIp = tvLanIp,
                        telegramStatus = telegramStatus,
                        onTelegramPassword = onTelegramPassword,
                        onTelegramAskPhone = onTelegramAskPhone,
                        telegramViaPhone = telegramViaPhone,
                        onTelegramViaPhone = onTelegramViaPhone,
                        onTelegramConnect = onTelegramConnect,
                        onTelegramCancel = onTelegramCancel,
                    )
                    return@Column
                }
                if (telegramStatus is app.cablegram.TvTelegramStatus.NeedsPassword) {
                    Text(
                        "Finish signing in to Telegram: enter your Telegram password in Settings on this TV.",
                        color = Aqua,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!adHeadline.isNullOrBlank()) {
                    Text(adHeadline, color = Aqua, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    if (!adBody.isNullOrBlank()) Text(adBody, color = Muted, fontSize = 12.sp)
                }
                if (activeShow != null) {
                    EpisodeRow(
                        show = activeShow,
                        hero = hero,
                        focusEpisodeId = restoredFocusId,
                        menuFocus = homeFocus,
                        onBack = {
                            val showId = activeShow.id
                            selectedShow = null
                            focusedEntry = null
                            onExplorerFocus(showId, null)
                        },
                        onPlay = onPlay,
                        onFocused = { video ->
                            focusedEntry = MovieEntry(video)
                            onExplorerFocus(video.id, activeShow.id)
                        },
                    )
                    return@Column
                }
                HeroHeader(
                    title = hero?.title ?: when (rail) {
                        RailDestination.Player -> "Nothing in progress"
                        RailDestination.Library -> "Your list is empty"
                        else -> "Your library"
                    },
                    metadata = hero?.metadataParts() ?: emptyList(),
                    overview = if (hero != null) hero.overview else when (rail) {
                        RailDestination.Library -> "Hold OK on any title to save it here."
                        else -> "Movies and shows from your phone appear here."
                    },
                    hint = hero?.actionHint(),
                    progress = hero?.progress ?: 0f,
                    remaining = hero?.remainingLabel(),
                )
                when {
                    videos.isEmpty() -> EmptyLibrary(onRefresh, Modifier.weight(1f))
                    shelves.isEmpty() -> EmptySearchResult(
                        message = when (rail) {
                            RailDestination.Player -> "Titles you start watching show up here."
                            RailDestination.Library -> "Nothing saved yet. Hold OK on a title to add it."
                            else -> "No titles match these filters."
                        },
                        modifier = Modifier.weight(1f),
                    )
                    else -> LibraryShelves(
                        modifier = Modifier.weight(1f),
                        shelves = shelves,
                        focusId = restoredFocusId,
                        menuFocus = homeFocus,
                        onPlay = onPlay,
                        onSelectShow = { show ->
                            selectedShow = fullShows[show.id] ?: show
                            focusedEntry = null
                            onExplorerFocus(show.episodes.firstOrNull()?.id, show.id)
                        },
                        onToggleMyList = { entry ->
                            val ids = entry.myListVideoIds()
                            val next = !entry.inMyList
                            onToggleMyList(ids, next)
                            listNotice = if (next) "Added to My List" else "Removed from My List"
                        },
                        onFocused = { entry ->
                            focusedEntry = entry
                            onExplorerFocus(entry.id, null)
                        },
                    )
                }
            }
        }
        listNotice?.let { message ->
            Text(
                message,
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Acid)
                    .padding(horizontal = 26.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Full-screen artwork for the focused title, washed into the ink background
 * so text and posters stay legible. Titles without a backdrop get a soft tint
 * derived from the title instead of a flat black screen.
 */
@Composable
private fun AmbientBackdrop(url: String?, seed: String?) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = url, animationSpec = tween(450), label = "ambient-backdrop") { target ->
            if (target != null) {
                AsyncImage(
                    model = target,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 0.62f },
                )
            } else if (seed != null) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(placeholderGradient(seed).first().copy(alpha = 0.55f), Color.Transparent),
                            start = Offset(Float.POSITIVE_INFINITY, 0f),
                            end = Offset(0f, Float.POSITIVE_INFINITY),
                        ),
                    ),
                )
            }
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to InkDeep,
                    0.38f to InkDeep.copy(alpha = 0.86f),
                    0.72f to InkDeep.copy(alpha = 0.25f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.34f to InkDeep.copy(alpha = 0.35f),
                    0.56f to InkDeep.copy(alpha = 0.92f),
                    1f to InkDeep,
                ),
            ),
        )
    }
}

@Composable
private fun Modifier.moveFocusToMenu(menuFocus: FocusRequester): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return focusProperties {
        if (rtl) {
            right = menuFocus
        } else {
            left = menuFocus
        }
    }
}

@Composable
private fun Modifier.rowStartMenuFocus(menuFocus: FocusRequester, index: Int): Modifier {
    if (index != 0) return this
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return focusProperties {
        if (rtl) right = menuFocus else left = menuFocus
    }
}

@Composable
private fun LibraryTopBar(
    title: String,
    selectedType: String,
    selectedGenre: String?,
    showFilters: Boolean,
    menuFocus: FocusRequester,
    onTypeSelected: (String) -> Unit,
    onGenreCycle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark()
        Spacer(Modifier.width(28.dp))
        if (showFilters) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterPill("All", selected = selectedType == "all", menuFocus = menuFocus) { onTypeSelected("all") }
                FilterPill("Movies", selected = selectedType == "movie", menuFocus = menuFocus) { onTypeSelected("movie") }
                FilterPill("TV Shows", selected = selectedType == "tv", menuFocus = menuFocus) { onTypeSelected("tv") }
                FilterPill(
                    label = "Genre: ${selectedGenre ?: "All"}",
                    selected = selectedGenre != null,
                    outlined = true,
                    menuFocus = menuFocus,
                    onClick = onGenreCycle,
                )
            }
        } else {
            Text(title, color = Paper, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.weight(1f))
        Clock()
    }
}

/** A glanceable wall clock, the way every living-room screen has one. */
@Composable
private fun Clock() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }
    val pattern = if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    Text(now.format(DateTimeFormatter.ofPattern(pattern)), color = Paper.copy(alpha = 0.82f), fontSize = 18.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun HeroHeader(
    title: String,
    metadata: List<String>,
    overview: String? = null,
    hint: String? = null,
    progress: Float = 0f,
    remaining: String? = null,
) {
    // Fixed height: moving focus between titles must not shift the shelves.
    Column(
        modifier = Modifier
            .fillMaxWidth(0.64f)
            .height(164.dp)
            .padding(top = 10.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 36.sp,
            lineHeight = 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (metadata.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                metadata.forEachIndexed { index, part ->
                    MetaChip(part, accent = index == 0)
                }
            }
        }
        overview?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                color = Paper.copy(alpha = 0.78f),
                fontSize = 15.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (remaining != null || hint != null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (remaining != null) {
                    Box(
                        Modifier
                            .width(120.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Outline),
                    ) {
                        Box(Modifier.fillMaxWidth(progress.coerceIn(0.04f, 1f)).fillMaxHeight().background(Cyan))
                    }
                    Text(remaining, color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 10.dp, end = 18.dp))
                }
                hint?.let { Text(it, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun MetaChip(label: String, accent: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        label,
        color = if (accent) Ink else Paper.copy(alpha = 0.86f),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(shape)
            .background(if (accent) Cyan else Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun CollapsedSidebar(
    destination: RailDestination,
    profileName: String,
    profileAvatarUrl: String?,
    homeFocusRequester: FocusRequester,
    onHome: () -> Unit,
    onPlayer: () -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
    onSwitchProfile: () -> Unit,
    profileFocusRequester: FocusRequester,
    onFocusChanged: (inSidebar: Boolean, onProfile: Boolean) -> Unit,
) {
    var avatarFocused by remember { mutableStateOf(false) }
    var sidebarHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(sidebarHasFocus, avatarFocused) { onFocusChanged(sidebarHasFocus, avatarFocused) }
    Column(
        modifier = Modifier
            .width(96.dp)
            .fillMaxHeight()
            .onFocusChanged { sidebarHasFocus = it.hasFocus }
            .background(Brush.horizontalGradient(listOf(Panel.copy(alpha = 0.96f), Panel.copy(alpha = 0.82f))))
            .focusRestorer(homeFocusRequester)
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SidebarAvatar(
            profileName = profileName,
            avatarUrl = profileAvatarUrl,
            onClick = onSwitchProfile,
            focusRequester = profileFocusRequester,
            onFocused = { avatarFocused = it },
        )
        Spacer(Modifier.height(28.dp))
        RailIconButton(RailIcon.Home, "Home", destination == RailDestination.Home, onHome, homeFocusRequester)
        RailIconButton(RailIcon.Player, "Continue", destination == RailDestination.Player, onPlayer)
        RailIconButton(RailIcon.Library, "My List", destination == RailDestination.Library, onLibrary)
        Spacer(Modifier.weight(1f))
        RailIconButton(RailIcon.Settings, "Settings", selected = destination == RailDestination.Settings, onClick = onSettings)
    }
}

@Composable
private fun SidebarAvatar(
    profileName: String,
    avatarUrl: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(avatarUrl) { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(140), label = "avatar-scale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .border(if (focused) 3.dp else 1.dp, if (focused) Cyan else Outline, CircleShape)
                .background(placeholderGradient(profileName).first())
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.hasFocus
                    onFocused(it.hasFocus)
                }
                .clickable(interactionSource = null, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null && !imageFailed) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = profileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                )
            } else {
                Text(
                    profileName.trim().firstOrNull()?.uppercase() ?: "C",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            if (focused) "Switch" else profileName,
            color = if (focused) Cyan else Muted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp).width(84.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

private enum class RailIcon { Home, Player, Library, Settings }

@Composable
private fun RailIconButton(
    icon: RailIcon,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(84.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.hasFocus }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    when {
                        focused -> Paper
                        selected -> Cyan
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            LineIcon(icon, if (focused || selected) Ink else Muted)
        }
        Text(
            label,
            color = when {
                focused -> Paper
                selected -> Cyan
                else -> Muted
            },
            fontSize = 12.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
}

@Composable
private fun LineIcon(icon: RailIcon, tint: Color) {
    Box(
        Modifier
            .size(20.dp)
            .drawBehind {
                val stroke = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                when (icon) {
                    RailIcon.Home -> {
                        val w = size.width
                        val h = size.height
                        drawLine(tint, Offset(w * .12f, h * .48f), Offset(w * .5f, h * .14f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .5f, h * .14f), Offset(w * .88f, h * .48f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .22f, h * .42f), Offset(w * .22f, h * .86f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .78f, h * .42f), Offset(w * .78f, h * .86f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .22f, h * .86f), Offset(w * .78f, h * .86f), stroke.width, StrokeCap.Round)
                    }
                    RailIcon.Player -> {
                        drawCircle(tint, radius = size.minDimension / 2.1f, style = stroke)
                        val cx = size.width * .42f
                        drawLine(tint, Offset(cx, size.height * .32f), Offset(size.width * .72f, size.height * .5f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(size.width * .72f, size.height * .5f), Offset(cx, size.height * .68f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(cx, size.height * .68f), Offset(cx, size.height * .32f), stroke.width, StrokeCap.Round)
                    }
                    RailIcon.Library -> {
                        val w = size.width
                        val h = size.height
                        // Bookmark: My List.
                        drawLine(tint, Offset(w * .26f, h * .1f), Offset(w * .74f, h * .1f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .26f, h * .1f), Offset(w * .26f, h * .9f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .74f, h * .1f), Offset(w * .74f, h * .9f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .26f, h * .9f), Offset(w * .5f, h * .68f), stroke.width, StrokeCap.Round)
                        drawLine(tint, Offset(w * .74f, h * .9f), Offset(w * .5f, h * .68f), stroke.width, StrokeCap.Round)
                    }
                    RailIcon.Settings -> {
                        drawCircle(tint, radius = size.minDimension * .16f, style = stroke)
                        drawCircle(tint, radius = size.minDimension * .42f, style = stroke)
                    }
                }
            },
    )
}

@Composable
private fun FilterPill(
    label: String,
    selected: Boolean,
    outlined: Boolean = false,
    menuFocus: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .then(if (menuFocus != null) Modifier.moveFocusToMenu(menuFocus) else Modifier)
            .clip(shape)
            .background(
                when {
                    focused -> Paper
                    selected -> FocusFill
                    else -> Color.Transparent
                },
            )
            .border(
                width = if (outlined && !focused) 1.dp else 0.dp,
                color = if (outlined && !focused) Outline else Color.Transparent,
                shape = shape,
            )
            .onFocusChanged { focused = it.hasFocus }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                focused -> Ink
                selected -> Paper
                else -> Muted
            },
            fontSize = 14.sp,
            fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun LibraryShelves(
    shelves: List<LibraryShelf>,
    focusId: String?,
    menuFocus: FocusRequester,
    onPlay: (Video) -> Unit,
    onSelectShow: (TvShow) -> Unit,
    onFocused: (LibraryEntry) -> Unit,
    onToggleMyList: (LibraryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var restoreItemId by remember { mutableStateOf<String?>(null) }
    var restoreShelfId by remember { mutableStateOf<String?>(null) }
    var restoreSettled by remember { mutableStateOf(false) }
    LaunchedEffect(focusId, shelves) {
        if (restoreSettled || shelves.isEmpty()) return@LaunchedEffect
        val id = focusId
        if (id != null) {
            restoreShelfId = shelves.firstOrNull { shelf -> shelf.entries.any { it.id == id } }?.id
            restoreItemId = id
        }
        restoreSettled = true
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(bottom = 36.dp),
    ) {
        items(shelves, key = { it.id }) { shelf ->
            LabeledPosterRow(
                title = shelf.title,
                entries = shelf.entries,
                focusId = if (shelf.id == restoreShelfId) restoreItemId else null,
                menuFocus = menuFocus,
                onPlay = onPlay,
                onSelectShow = onSelectShow,
                onFocused = { entry ->
                    restoreItemId = null
                    restoreShelfId = null
                    onFocused(entry)
                },
                onToggleMyList = onToggleMyList,
            )
        }
    }
}

@Composable
private fun LabeledPosterRow(
    title: String,
    entries: List<LibraryEntry>,
    focusId: String?,
    menuFocus: FocusRequester,
    onPlay: (Video) -> Unit,
    onSelectShow: (TvShow) -> Unit,
    onFocused: (LibraryEntry) -> Unit,
    onToggleMyList: (LibraryEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusId, entries) {
        val index = entries.indexOfFirst { it.id == focusId }
        if (index >= 0) listState.scrollToItem(index)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entries.size}",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRestorer(),
            contentPadding = PaddingValues(start = 4.dp, end = 40.dp, top = 14.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                when (entry) {
                    is MovieEntry -> PosterCard(
                        title = entry.title,
                        caption = entry.title,
                        posterUrl = entry.video.posterUrl,
                        progress = entry.progress,
                        preparing = entry.video.tier == "ingesting",
                        prepareProgress = entry.video.ingestProgress,
                        inMyList = entry.inMyList,
                        requestFocus = entry.id == focusId,
                        menuFocus = menuFocus,
                        rowIndex = index,
                        onFocused = { onFocused(entry) },
                        onClick = { onPlay(entry.video) },
                        onLongClick = { onToggleMyList(entry) },
                    )
                    is ShowEntry -> PosterCard(
                        title = entry.title,
                        caption = entry.title,
                        posterUrl = entry.show.posterUrl,
                        progress = entry.progress,
                        preparing = false,
                        prepareProgress = null,
                        inMyList = entry.inMyList,
                        badge = entry.show.episodes.singleOrNull()?.episodeBadge() ?: "${entry.show.episodes.size} EP",
                        requestFocus = entry.id == focusId,
                        menuFocus = menuFocus,
                        rowIndex = index,
                        onFocused = { onFocused(entry) },
                        onClick = { onSelectShow(entry.show) },
                        onLongClick = { onToggleMyList(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    show: TvShow,
    hero: LibraryEntry?,
    focusEpisodeId: String?,
    menuFocus: FocusRequester,
    onBack: () -> Unit,
    onPlay: (Video) -> Unit,
    onFocused: (Video) -> Unit,
) {
    BackHandler(onBack = onBack)
    val listState = rememberLazyListState()
    val targetId = focusEpisodeId?.takeIf { id -> show.episodes.any { it.id == id } }
        ?: show.nextEpisode()?.id
    LaunchedEffect(show.id, targetId) {
        val index = show.episodes.indexOfFirst { it.id == targetId }
        if (index >= 0) listState.scrollToItem(index)
    }
    val episode = (hero as? MovieEntry)?.video
    HeroHeader(
        title = show.title,
        metadata = episode?.metadataParts() ?: ShowEntry(show).metadataParts(),
        overview = episode?.let { listOfNotNull(it.episodeTitle, it.overview).joinToString(" — ").ifBlank { null } }
            ?: ShowEntry(show).overview,
        hint = "OK to play · Back for all titles",
        progress = hero?.progress ?: 0f,
        remaining = hero?.remainingLabel(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilterPill("‹  All titles", selected = false, outlined = true, menuFocus = menuFocus, onClick = onBack)
        Text(
            show.seasonSummary(),
            color = Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().focusRestorer(),
        contentPadding = PaddingValues(start = 4.dp, end = 48.dp, top = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        itemsIndexed(show.episodes, key = { _, item -> item.id }) { index, item ->
            PosterCard(
                title = item.showTitle(),
                caption = item.episodeCaption(),
                posterUrl = item.backdropUrl ?: item.posterUrl ?: show.posterUrl,
                progress = item.progressFraction(),
                preparing = item.tier == "ingesting",
                prepareProgress = item.ingestProgress,
                landscape = true,
                badge = item.episodeBadge(),
                requestFocus = item.id == targetId,
                menuFocus = menuFocus,
                rowIndex = index,
                onFocused = { onFocused(item) },
                onClick = { onPlay(item) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(
    title: String,
    caption: String?,
    posterUrl: String?,
    progress: Float,
    preparing: Boolean,
    prepareProgress: Int? = null,
    landscape: Boolean = false,
    badge: String? = null,
    inMyList: Boolean = false,
    requestFocus: Boolean = false,
    menuFocus: FocusRequester? = null,
    rowIndex: Int = 0,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    var imageFailed by remember(posterUrl) { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestFocus) {
        if (!requestFocus) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            val focusedNow = runCatching { focusRequester.requestFocus() }.getOrDefault(false)
            if (focusedNow) return@LaunchedEffect
        }
    }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "poster-scale")
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .width(if (landscape) 240.dp else 128.dp)
            .zIndex(if (focused) 1f else 0f)
            .then(if (menuFocus != null) Modifier.rowStartMenuFocus(menuFocus, rowIndex) else Modifier)
            .focusRequester(focusRequester)
            .onFocusChanged {
                // Track the whole card; focus lives on clickable's own focus target.
                val now = it.hasFocus
                if (now && !focused) onFocused()
                focused = now
            }
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(interactionSource = null, indication = null, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (landscape) 16f / 9f else 2f / 3f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(
                    if (focused) Modifier.drawBehind {
                        drawRoundRect(
                            color = Cyan.copy(alpha = 0.55f),
                            topLeft = Offset(-4.dp.toPx(), -4.dp.toPx()),
                            size = Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()),
                            cornerRadius = CornerRadius(16.dp.toPx()),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    } else Modifier,
                )
                .clip(shape)
                .background(PanelRaised)
                .border(if (focused) 3.dp else 1.dp, if (focused) Paper else Color.White.copy(alpha = 0.06f), shape),
        ) {
            when {
                posterUrl != null && !imageFailed -> AsyncImage(
                    model = posterUrl,
                    contentDescription = "$title poster",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                )
                else -> PlaceholderArt(title, landscape)
            }
            if (badge != null) {
                CardBadge(badge, Ink.copy(alpha = 0.78f), Paper, Modifier.align(Alignment.TopStart))
            }
            if (inMyList) {
                CardBadge("✓", Acid, Ink, Modifier.align(Alignment.TopEnd))
            }
            if (preparing) {
                PreparationProgressBar(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    progress = prepareProgress?.coerceIn(0, 100)?.div(100f),
                )
            } else if (progress > 0f) {
                Box(Modifier.fillMaxWidth().height(5.dp).align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.55f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0.04f, 1f)).fillMaxHeight().background(Cyan))
                }
            }
        }
        if (caption != null) {
            Text(
                caption,
                color = if (focused) Paper else Muted,
                fontSize = 13.sp,
                fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (focused) 12.dp else 8.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/** Artwork for titles without a poster: a per-title tint with a monogram. */
@Composable
private fun PlaceholderArt(title: String, landscape: Boolean) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(placeholderGradient(title))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monogram(title),
            color = Color.White.copy(alpha = 0.9f),
            fontSize = if (landscape) 34.sp else 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun CardBadge(label: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Text(
        label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
internal fun PreparationProgressBar(modifier: Modifier = Modifier, progress: Float? = null) {
    val transition = rememberInfiniteTransition(label = "preparation-progress")
    val animated by transition.animateFloat(
        initialValue = .18f,
        targetValue = .82f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "preparation-progress-value",
    )
    val fraction = progress?.coerceIn(0.02f, 1f) ?: animated
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .45f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Cyan))
    }
}

@Composable
private fun EmptySearchResult(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(bottom = 48.dp), contentAlignment = Alignment.Center) {
        Text(message, color = Muted, fontSize = 18.sp)
    }
}

@Composable
private fun EmptyLibrary(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(PanelSoft.copy(alpha = 0.9f))
                .border(1.dp, Outline, RoundedCornerShape(20.dp))
                .padding(horizontal = 44.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Nothing here yet", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(
                "Add videos in the Cablegram phone app. They appear on this TV within a few seconds.",
                color = Muted,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(22.dp))
            Button(onClick = onRefresh) { Text("Check again") }
        }
    }
}

private data class LibraryShelf(
    val id: String,
    val title: String,
    val entries: List<LibraryEntry>,
)

internal data class LibraryShelfSummary(
    val id: String,
    val title: String,
    val entryIds: List<String>,
)

internal fun summarizeLibraryShelves(
    videos: List<Video>,
    selectedType: String = "all",
    selectedGenre: String? = null,
): List<LibraryShelfSummary> = buildLibraryShelves(videos, selectedType, selectedGenre).map { shelf ->
    LibraryShelfSummary(shelf.id, shelf.title, shelf.entries.map { it.id })
}

private fun buildLibraryShelves(
    videos: List<Video>,
    selectedType: String,
    selectedGenre: String?,
): List<LibraryShelf> {
    val typed = when (selectedType) {
        "tv" -> videos.filter { it.mediaType == "tv" }
        "movie" -> videos.filter { it.mediaType != "tv" }
        "watchlist" -> videos.filter { (it.resumePositionSeconds ?: 0) > 0 }
        "mylist" -> videos.filter { it.inMyList }
        else -> videos
    }
    val scoped = selectedGenre?.let { genre -> typed.filter { genre in it.genres } } ?: typed
    val catalog = toLibraryEntries(scoped).sortedByDescending { it.addedAt }
    if (catalog.isEmpty()) return emptyList()

    val continueWatching = toLibraryEntries(scoped.filter { (it.resumePositionSeconds ?: 0) > 0 })
        .sortedByDescending { it.resumeSeconds() }
    // The Continue and My List destinations are single lists; repeating the
    // Home shelves ("Recently Added", genres) inside them was confusing.
    when (selectedType) {
        "watchlist" -> return listOf(LibraryShelf("continue", "Continue Watching", continueWatching))
        "mylist" -> return listOf(LibraryShelf("mylist", "My List", catalog))
    }
    val recentlyAdded = catalog.take(24)
    val shelves = mutableListOf<LibraryShelf>()
    if (continueWatching.isNotEmpty()) {
        shelves += LibraryShelf("continue", "Continue Watching", continueWatching)
    }
    if (recentlyAdded.isNotEmpty()) {
        shelves += LibraryShelf("recent", "Recently Added", recentlyAdded)
    }
    val myList = toLibraryEntries(scoped.filter { it.inMyList }).sortedByDescending { it.addedAt }
    if (myList.isNotEmpty()) {
        shelves += LibraryShelf("mylist", "My List", myList)
    }
    if (selectedGenre == null) {
        scoped.flatMap { it.genres }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().forEach { genre ->
            val genreEntries = toLibraryEntries(scoped.filter { genre in it.genres }).sortedByDescending { it.addedAt }
            if (genreEntries.isNotEmpty()) {
                shelves += LibraryShelf("genre:$genre", genre, genreEntries)
            }
        }
    }
    return shelves
}

private fun toLibraryEntries(videos: List<Video>): List<LibraryEntry> {
    val shows = videos.filter { it.mediaType == "tv" }
        .groupBy { it.tvShowKey() }
        .map { (key, episodes) -> ShowEntry(TvShow(key, episodes.sortedWith(episodeComparator))) }
    val movies = videos.filter { it.mediaType != "tv" }.map(::MovieEntry)
    return movies + shows
}

private fun LibraryEntry.resumeSeconds(): Int = when (this) {
    is MovieEntry -> video.resumePositionSeconds ?: 0
    is ShowEntry -> show.episodes.maxOf { it.resumePositionSeconds ?: 0 }
}

private sealed interface LibraryEntry {
    val id: String
    val title: String
    val addedAt: String
    val inMyList: Boolean
    val overview: String?
    val backdropUrl: String?
    /** Watched fraction shown on the card and hero (0 when not started). */
    val progress: Float
    fun metadataParts(): List<String>
    fun remainingLabel(): String?
    fun actionHint(): String
    fun myListVideoIds(): List<String>
}

private data class MovieEntry(val video: Video) : LibraryEntry {
    override val id = video.id
    override val title = video.heroTitle()
    override val addedAt = video.addedAtTimestamp
    override val inMyList = video.inMyList
    override val overview = video.overview
    override val backdropUrl = video.backdropUrl
    override val progress = video.progressFraction()
    override fun metadataParts() = video.metadataParts()
    override fun remainingLabel() = video.remainingLabel()
    override fun actionHint() = listOf(
        if ((video.resumePositionSeconds ?: 0) > 0) "OK to resume" else "OK to play",
        if (inMyList) "Hold OK to remove from list" else "Hold OK to add to My List",
    ).joinToString("  ·  ")
    override fun myListVideoIds() = listOf(video.id)
}

private data class ShowEntry(val show: TvShow) : LibraryEntry {
    override val id = show.id
    override val title = show.title
    override val addedAt = show.addedAt
    override val inMyList = show.episodes.any { it.inMyList }
    override val overview = show.heroEpisode().overview
    override val backdropUrl = show.episodes.firstNotNullOfOrNull { it.backdropUrl }
    override val progress = show.heroEpisode().progressFraction()
    override fun metadataParts() = listOfNotNull(
        "TV series",
        show.seasonSummary(),
        show.episodes.flatMap { it.genres }.distinct().take(2).joinToString(", ").ifBlank { null },
    )
    override fun remainingLabel() = show.heroEpisode().takeIf { (it.resumePositionSeconds ?: 0) > 0 }?.let { episode ->
        listOfNotNull(episode.episodeBadge(), episode.remainingLabel()).joinToString(" · ")
    }
    override fun actionHint() = listOf(
        "OK for episodes",
        if (inMyList) "Hold OK to remove from list" else "Hold OK to add to My List",
    ).joinToString("  ·  ")
    override fun myListVideoIds() = show.episodes.map { it.id }
}

private data class TvShow(val id: String, val episodes: List<Video>) {
    val title: String = episodes.first().showTitle()
    val posterUrl: String? = episodes.firstNotNullOfOrNull { it.posterUrl }
    val addedAt: String = episodes.maxOf { it.addedAtTimestamp }
    fun heroEpisode(): Video = episodes.maxBy { it.resumePositionSeconds ?: 0 }

    /** The episode to land on: the one in progress, else the first unwatched. */
    fun nextEpisode(): Video? = episodes.firstOrNull { (it.resumePositionSeconds ?: 0) > 0 } ?: episodes.firstOrNull()

    fun seasonSummary(): String {
        val seasons = episodes.mapNotNull { it.seasonNumber }.distinct().size
        val count = if (episodes.size == 1) "1 episode" else "${episodes.size} episodes"
        return if (seasons > 1) "$seasons seasons · $count" else count
    }
}

private val episodeComparator = compareBy<Video>(
    { it.seasonNumber ?: Int.MAX_VALUE },
    { it.episodeNumber ?: Int.MAX_VALUE },
    { it.addedAtTimestamp },
)

internal const val RECEIVING_TITLE = "Receiving..."

internal fun Video.displayTitle(): String {
    val base = showTitle()
    return if (mediaType == "tv" && seasonNumber != null && episodeNumber != null && !isCatalogPending()) {
        "$base · S${seasonNumber.toString().padStart(2, '0')}E${episodeNumber.toString().padStart(2, '0')}"
    } else base
}

internal fun Video.showTitle(): String {
    if (isCatalogPending()) return RECEIVING_TITLE
    val candidate = title?.trim()
    return candidate
        ?.takeIf { it.isNotBlank() && it != RECEIVING_TITLE && !it.looksLikeInternalIdentifier() }
        ?: "Personal video"
}

private fun String.looksLikeInternalIdentifier(): Boolean {
    if (length >= 12 && all { it.isDigit() || it == '_' || it == '-' }) return true
    val normalized = lowercase()
    return normalized.contains("webrip") || normalized.contains("bluray") ||
        normalized.contains("softsub") || normalized.contains("1080p") ||
        normalized.contains("2160p") || normalized.matches(Regex("^(hot|telegram|file)[ _-].*"))
}

internal fun Video.isCatalogPending(): Boolean {
    if (title == RECEIVING_TITLE) return true
    if (matchStatus != "pending") return false
    if (tier != "ingesting") return false
    val stage = ingestStage?.lowercase()
    return stage.isNullOrBlank() || stage == "catalog" || (ingestProgress ?: 0) < 10
}

internal fun Video.tvShowKey(): String = when {
    isCatalogPending() -> "pending:$id"
    else -> tmdbId?.let { "tmdb:$it" } ?: "title:${showTitle().trim().lowercase()}"
}

private fun Video.heroTitle(): String = showTitle()

internal fun Video.episodeBadge(): String? = when {
    seasonNumber != null && episodeNumber != null -> "S$seasonNumber · E$episodeNumber"
    episodeNumber != null -> "E$episodeNumber"
    else -> null
}

internal fun Video.episodeCaption(): String =
    episodeTitle?.takeIf { it.isNotBlank() } ?: episodeNumber?.let { "Episode $it" } ?: displayTitle()

/** "1h 52m" / "48m". */
internal fun formatRuntime(seconds: Int): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes.coerceAtLeast(1)}m"
}

internal fun Video.remainingLabel(): String? {
    val position = resumePositionSeconds ?: return null
    val duration = durationSeconds ?: return null
    if (position <= 0 || duration <= 0 || position >= duration) return null
    return "${formatRuntime(duration - position)} left"
}

private fun Video.progressFraction(): Float {
    val duration = durationSeconds ?: return if ((resumePositionSeconds ?: 0) > 0) 0.35f else 0f
    if (duration <= 0) return 0f
    return ((resumePositionSeconds ?: 0).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
}

internal fun Video.metadataParts(): List<String> = listOfNotNull(
    if (mediaType == "tv") episodeBadge() else releaseYear?.toString(),
    durationSeconds?.takeIf { it > 0 }?.let(::formatRuntime),
    genres.take(2).joinToString(", ").ifBlank { null },
    resolution?.uppercase()?.replace('_', ' '),
)
