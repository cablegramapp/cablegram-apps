package app.cablegram.phone

import androidx.compose.material3.RadioButton

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import app.cablegram.phone.remote.RemoteScreen
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LibraryShell(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addPickedFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { viewModel.openBrowseFolder(it) }
    }
    var accountHelpOpen by rememberSaveable { mutableStateOf(false) }
    if (accountHelpOpen) {
        TelegramAccountHelp(onBack = { accountHelpOpen = false })
        return
    }
    if (viewModel.collectionsDialogOpen) AlertDialog(
        onDismissRequest = { viewModel.collectionsDialogOpen = false },
        title = { Text(ui.getString(R.string.ui_collections_9f9fea)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(viewModel.collectionDraft, { viewModel.collectionDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(ui.getString(R.string.ui_collection_name_922214)) })
                OutlinedButton(onClick = viewModel::createCollection, enabled = viewModel.collectionDraft.isNotBlank()) { Text(ui.getString(R.string.ui_create_collection_69152b)) }
                viewModel.collections.forEach { collection ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(collection.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.deleteCollection(collection.id) }) { Text(ui.getString(R.string.ui_remove_c3812f)) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { viewModel.collectionsDialogOpen = false }) { Text(ui.getString(R.string.ui_done_11a676)) } },
    )
    BackHandler(enabled = viewModel.selected != null || viewModel.openSeriesKey != null || viewModel.tab != PhoneTab.Library) {
        if (viewModel.selected != null) viewModel.closeItem()
        else if (viewModel.openSeriesKey != null) viewModel.closeSeries()
        else if (viewModel.tab == PhoneTab.Storage) viewModel.tab = PhoneTab.Settings
        else viewModel.tab = PhoneTab.Library
    }
    Scaffold(
        containerColor = VlcBlack,
        bottomBar = {
            Column {
                // A single measured stack reserves space instead of covering content.
                if (viewModel.prepareStep != null) PrepareCard(viewModel, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                TransferCard(viewModel, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (viewModel.legacyRemote && (viewModel.nowPlaying != null || viewModel.startingTitle != null) && viewModel.cloudSheet == CloudSheet.None) {
                    RemoteBar(viewModel, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                }
                LibraryNav(viewModel)
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().background(VlcBlack)) {
            Column(Modifier.fillMaxSize()) {
                AddedVideosCard(viewModel)
                if (viewModel.pendingApprovals.isNotEmpty()) ApprovalCard(viewModel, Modifier.padding(16.dp))
                Box(Modifier.weight(1f)) {
                    when (viewModel.tab) {
                        PhoneTab.Library -> LibraryHome(viewModel)
                        PhoneTab.Browse -> BrowseScreen(viewModel, onTelegramAccountHelp = { accountHelpOpen = true }, onOpenLocalRoot = { pickFiles.launch(arrayOf("video/*")) }, onChooseFolder = { pickFolder.launch(null) })
                        PhoneTab.Remote -> if (viewModel.legacyRemote) RemoteScreen(viewModel) else LibraryHome(viewModel)
                        PhoneTab.Storage -> StorageScreen(viewModel)
                        PhoneTab.Settings -> SettingsScreen(viewModel)
                    }
                }
            }
            viewModel.shownSeries?.let { SeriesOverlay(viewModel, it) }
            viewModel.selected?.let { if (viewModel.subtitleFlowOpen) SubtitleFlow(viewModel, it) { viewModel.subtitleFlowOpen = false } else DetailOverlay(viewModel, it) }
            viewModel.deleteTarget?.let { DeleteDialog(viewModel, it) }
            viewModel.removeCloudCopyTarget?.let { RemoveCloudCopyDialog(viewModel, it) }
            if (viewModel.castPickerOpen) AlertDialog(
                onDismissRequest = viewModel::dismissCastPicker,
                title = { Text(ui.getString(R.string.ui_play_on_tv_f47dcd)) },
                text = { Column {
                    viewModel.castRoutes.forEach { route ->
                        TextButton(onClick = { viewModel.chooseCastRoute(route) }) { Text(route.name) }
                    }
                    if (viewModel.castRoutes.isEmpty()) Text(ui.getString(R.string.ui_looking_for_tvs_8edf98))
                } },
                confirmButton = {},
                dismissButton = { TextButton(onClick = viewModel::dismissCastPicker) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
            )
            CloudFlow(viewModel)
            if (viewModel.telegramSheetOpen) TelegramConnectSheet(viewModel)
        }
    }
}

@Composable
private fun LibraryNav(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val items = listOf(
        Triple(PhoneTab.Library, Icons.AutoMirrored.Filled.LibraryBooks, ui.getString(R.string.ui_library_dc20b3)),
        Triple(PhoneTab.Browse, Icons.Default.Add, ui.getString(R.string.ui_import_2cff9b)),
        Triple(PhoneTab.Remote, Icons.Default.Tv, ui.getString(R.string.ui_remote_ffa98e)),
        Triple(PhoneTab.Settings, Icons.Default.Settings, ui.getString(R.string.ui_settings_74a883)),
    )
    val visibleItems = items.filter { viewModel.legacyRemote || it.first != PhoneTab.Remote }
    NavigationBar(containerColor = VlcBlack, tonalElevation = 0.dp) {
        visibleItems.forEach { (tab, icon, label) ->
            NavigationBarItem(
                selected = viewModel.tab == tab || (tab == PhoneTab.Settings && viewModel.tab == PhoneTab.Storage),
                // A cloud sheet, a title page and a series page are full-screen overlays: leaving for another tab must not leave them covering it.
                onClick = { viewModel.dismissCloudSheet(); viewModel.tab = tab; viewModel.closeItem(); viewModel.closeSeries() },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.maestro(
                    when (tab) {
                        PhoneTab.Library -> MaestroIds.NAV_LIBRARY
                        PhoneTab.Browse -> MaestroIds.NAV_BROWSE
                        PhoneTab.Remote -> MaestroIds.NAV_REMOTE
                        PhoneTab.Storage -> MaestroIds.NAV_STORAGE
                        PhoneTab.Settings -> MaestroIds.NAV_SETTINGS
                    },
                ),
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = VlcOrange,
                    selectedTextColor = Color.White,
                    unselectedIconColor = VlcMuted,
                    unselectedTextColor = VlcMuted,
                    indicatorColor = Color(0xFF3D3022),
                ),
            )
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun LibraryHome(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var filter by rememberSaveable { mutableStateOf("all") }
    val queried = searchLibrary(viewModel.items, viewModel.searchQuery)
    val filtered = when (filter) {
        "movie" -> movies(queried)
        "tv" -> tvShows(queried)
        else -> queried
    }
    // Pull down to check for new videos, e.g. one just shared into the Telegram channel.
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = viewModel.librarySyncState == LibrarySyncState.Running,
        onRefresh = viewModel::syncNow,
        modifier = Modifier.fillMaxSize(),
    ) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BrandMark(compact = true)
                    ScreenHeading(ui.getString(R.string.ui_your_library_1ad842), modifier = Modifier.maestro(MaestroIds.LIBRARY_TITLE))
                }
                Surface(color = VlcPanel, shape = RoundedCornerShape(16.dp)) {
                    IconButton(onClick = { viewModel.collectionsDialogOpen = true }, modifier = Modifier.maestro("library_collections")) { Icon(Icons.Default.Folder, ui.getString(R.string.ui_collections_9f9fea), tint = VlcOrange) }
                }
                Surface(color = VlcPanel, shape = RoundedCornerShape(16.dp)) {
                    IconButton(onClick = { viewModel.tab = PhoneTab.Browse }) { Icon(Icons.Default.Add, ui.getString(R.string.ui_import_videos_fec728), tint = VlcOrange) }
                }
            }
        }
        item {
            OutlinedTextField(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                modifier = Modifier.fillMaxWidth().maestro(MaestroIds.LIBRARY_SEARCH),
                singleLine = true,
                placeholder = { Text(ui.getString(R.string.ui_search_your_films_and_shows_dac9bc)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (viewModel.searchQuery.isNotEmpty()) ({
                    IconButton(onClick = { viewModel.searchQuery = "" }) { Icon(Icons.Default.Close, ui.getString(R.string.ui_clear_search_3b7ea5)) }
                }) else null,
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to R.string.ui_all_a52ace, "movie" to R.string.ui_movies_dff924, "tv" to R.string.ui_tv_shows_222906).forEach { (key, label) ->
                    FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(ui.getString(label)) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (viewModel.paired) viewModel.tvName else ui.getString(R.string.ui_made_for_your_big_screen_eb0ced), color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(if (viewModel.paired) ui.getString(R.string.ui_keep_your_phone_on_the_same_wi_fi_e373e7) else ui.getString(R.string.ui_connect_a_tv_when_you_re_ready_to_watch_d1ae74), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (!viewModel.paired || viewModel.legacyRemote) TextButton(onClick = if (viewModel.paired) ({ viewModel.tab = PhoneTab.Remote }) else viewModel::startAddTv) {
                    Text(if (viewModel.paired) ui.getString(R.string.ui_remote_ffa98e) else ui.getString(R.string.ui_connect_1a2303))
                }
            }
            TextButton(onClick = viewModel::syncNow, enabled = viewModel.librarySyncState != LibrarySyncState.Running) {
                Icon(if (viewModel.librarySyncState == LibrarySyncState.Completed) Icons.Default.Check else Icons.Default.Sync, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(when (viewModel.librarySyncState) {
                    LibrarySyncState.Running -> ui.getString(R.string.ui_syncing_library_8e43e6)
                    LibrarySyncState.Completed -> ui.getString(R.string.ui_library_synced_704162)
                    LibrarySyncState.Failed -> ui.getString(R.string.ui_sync_needs_attention_retry_f4d943)
                    else -> ui.getString(R.string.ui_sync_library_dd3492)
                }, style = MaterialTheme.typography.labelMedium)
            }
        }
        viewModel.scanProgress?.let { item { StatusNote(it) } }
        viewModel.status?.let { item { StatusNote(it) } }
        when {
            viewModel.items.isEmpty() -> item {
                EmptyState(Icons.Default.Movie, ui.getString(R.string.ui_a_home_for_your_favorites_1b3984), ui.getString(R.string.ui_import_a_few_videos_from_your_phone_your_personal_cine_1f8181), ui.getString(R.string.ui_import_videos_fec728), { viewModel.tab = PhoneTab.Browse })
            }
            filtered.isEmpty() -> item {
                EmptyState(Icons.Default.Search, ui.getString(R.string.ui_no_titles_found_2aeb6d), ui.getString(R.string.ui_try_another_title_or_clear_your_filters_to_see_the_who_ab222f), ui.getString(R.string.ui_clear_filters_7179ea), { viewModel.searchQuery = ""; filter = "all" })
            }
            viewModel.searchQuery.isNotBlank() || filter != "all" -> item { Shelf(ui.getString(R.string.ui_titles_77f1d9, (filtered.size).toString()), filtered, viewModel) }
            else -> {
                if (continueWatching(filtered).isNotEmpty()) item { Shelf(ui.getString(R.string.ui_continue_watching_81ebbe), continueWatching(filtered), viewModel, showProgress = true) }
                item { Shelf(ui.getString(R.string.ui_recently_added_96ec5d), recentlyAdded(filtered), viewModel) }
                if (movies(filtered).isNotEmpty()) item { Shelf(ui.getString(R.string.ui_movies_dff924), movies(filtered), viewModel) }
                if (tvShows(filtered).isNotEmpty()) item { Shelf(ui.getString(R.string.ui_tv_shows_222906), tvShows(filtered), viewModel) }
                viewModel.collections.forEach { collection ->
                    val collectionItems = filtered.filter { collection.id in it.collectionIds }
                    if (collectionItems.isNotEmpty()) item(key = "collection_${collection.id}") { Shelf(collection.name, collectionItems, viewModel) }
                }
            }
        }
    }
    }
}

/** T075: in-app card mirroring the notification, so approvals also work with the app open. */
@Composable
fun ApprovalCard(viewModel: PhoneViewModel, modifier: Modifier = Modifier) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    viewModel.pendingApprovals.firstOrNull()?.let { approval ->
        Card(
            colors = CardDefaults.cardColors(containerColor = VlcPanel),
            border = BorderStroke(2.dp, VlcOrange),
            modifier = modifier.fillMaxWidth(),
        ) {
            val profile = approval.kind == ApprovalKind.Profile
            Column(Modifier.padding(14.dp).maestro(MaestroIds.APPROVAL_CARD), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        viewModel.pendingApprovals.size > 1 -> ui.getString(R.string.ui_tv_approval_requests_f2f42a, (viewModel.pendingApprovals.size).toString())
                        profile -> ui.getString(R.string.ui_wants_to_open_a_profile_56a29f, approval.tvName?.takeIf { it.isNotBlank() } ?: ui.getString(R.string.ui_a_tv_bf791d))
                        else -> ui.getString(R.string.ui_tv_playback_approval_4438c0)
                    },
                    color = VlcOrange,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    approval.title?.takeIf { it.isNotBlank() } ?: if (profile) ui.getString(R.string.ui_a_profile_c3d18a) else ui.getString(R.string.ui_a_private_title_wants_to_play_on_the_tv_840767),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (profile && approval.sharedTv) {
                    Text(ui.getString(R.string.ui_other_accounts_use_this_tv_too_allow_it_only_if_you_ar_7a66a8), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.respondToApproval(approval, approve = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = VlcOrange),
                        modifier = Modifier.maestro(MaestroIds.APPROVAL_ALLOW),
                    ) {
                        Text(if (profile) ui.getString(R.string.ui_allow_e213c1) else ui.getString(R.string.ui_allow_once_168511), color = VlcBlack)
                    }
                    OutlinedButton(onClick = { viewModel.respondToApproval(approval, approve = false) }, modifier = Modifier.maestro(MaestroIds.APPROVAL_DENY)) {
                        Text(ui.getString(R.string.ui_not_now_a0e63d), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(title: String, items: List<LibraryItem>, viewModel: PhoneViewModel, showProgress: Boolean = false) {
    if (items.isEmpty()) return
    // A series' episodes share one poster, as on the TV; its episodes open from there.
    val entries = groupIntoEntries(items)
    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(entries, key = { it.key }) { entry ->
            when (entry) {
                is LibraryEntry.Film -> PosterCard(entry.item, showProgress, viewModel)
                is LibraryEntry.Series -> SeriesCard(entry, showProgress, viewModel)
            }
        }
    }
}

@Composable
private fun SeriesCard(series: LibraryEntry.Series, showProgress: Boolean, viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val lead = series.lead
    Column(
        Modifier
            .width(144.dp)
            .clickable { viewModel.openSeries(series) }
            .maestro("series_${series.key}"),
    ) {
        Box(
            Modifier
                .size(144.dp, 210.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center,
        ) {
            MediaArtwork(lead, Modifier.fillMaxSize())
            Text(
                ui.getString(R.string.ui_tv_series_e50677),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (showProgress) {
                LinearProgressIndicator(
                    progress = { watchFraction(lead) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = VlcOrange,
                    trackColor = Color(0xFF333333),
                )
            }
        }
        Text(series.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Text(series.summary(ui), color = VlcMuted, fontSize = 11.sp, maxLines = 1)
        if (showProgress) {
            Text(
                listOfNotNull(episodeBadge(lead), remainingLabel(lead, resources = ui).ifBlank { null }).joinToString(" · ").ifBlank { ui.getString(R.string.ui_message_b6bb55, ((watchFraction(lead) * 100).toInt()).toString()) },
                color = VlcMuted,
                fontSize = 11.sp,
            )
        }
    }
}

/** The episodes of one series, by season; an episode opens like any title, and Play starts it on the TV. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SeriesOverlay(viewModel: PhoneViewModel, series: LibraryEntry.Series) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    BackHandler(enabled = viewModel.selected == null) { viewModel.closeSeries() }
    val seasons = series.episodes.groupBy { it.seasonNumber }.toSortedMap(compareBy { it ?: Int.MAX_VALUE })
    Column(
        Modifier.fillMaxSize().background(VlcBlack).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = viewModel::closeSeries) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(ui.getString(R.string.ui_library_dc20b3))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaArtwork(series.lead, Modifier.width(110.dp).height(160.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ui.getString(R.string.ui_tv_show_453862), color = VlcOrange, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(series.title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(series.summary(ui), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        seasons.forEach { (season, episodes) ->
            Text(if (season != null) ui.getString(R.string.ui_season_ab5756, season) else ui.getString(R.string.ui_episodes_83f972), color = Color.White, style = MaterialTheme.typography.titleMedium)
            episodes.forEach { episode ->
                // Tap opens the episode; long-press gives the same menu as a poster (Delete, Save to Telegram…).
                val rowKey = remember(episode.id) { "episode:${episode.id}:${java.util.UUID.randomUUID()}" }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(VlcPanel)
                        .combinedClickable(onClick = { viewModel.openItem(episode) }, onLongClick = { viewModel.actionMenuId = rowKey })
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(episodeBadge(episode) ?: ui.getString(R.string.ui_episode_b19c45), color = Color.White)
                        Text(episode.filename.substringBeforeLast('.'), color = VlcMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(storageStatusLine(episode, resources = ui), color = VlcMuted, fontSize = 11.sp, maxLines = 1)
                        if (episode.positionSeconds > 0) {
                            LinearProgressIndicator(
                                progress = { watchFraction(episode) },
                                modifier = Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp),
                                color = VlcOrange,
                                trackColor = Color(0xFF333333),
                            )
                        }
                    }
                    TextButton(
                        onClick = { viewModel.playOnTv(episode) },
                        enabled = episode.sourceAvailable != false || episode.cloudObjectPresent || episode.telegramCopy || episode.ownCloudCopy || episode.sourceKind == "telegram",
                    ) { Text(ui.getString(R.string.ui_play_436e61)) }
                    ItemActionMenu(viewModel, episode, viewModel.actionMenuId == rowKey)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(item: LibraryItem, showProgress: Boolean, viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    // Flow 2 (legacy parity): a TMDb poster always outranks the extracted
    // still. posterPath is only used when no TMDb artwork was downloaded yet.
    // The same title can sit on several shelves; each card opens only its own menu.
    val cardKey = remember { "${item.id}:${java.util.UUID.randomUUID()}" }
    val menu = viewModel.actionMenuId == cardKey
    Column(
        Modifier
            .width(144.dp)
            .combinedClickable(
                onClick = { viewModel.openItem(item) },
                onLongClick = { viewModel.actionMenuId = cardKey },
            )
            .maestro("poster_${item.id}"),
    ) {
        Box(
            Modifier
                .size(144.dp, 210.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1E1E1E)),
            contentAlignment = Alignment.Center,
        ) {
            MediaArtwork(item, Modifier.fillMaxSize())
            Text(
                storageBadgeLabel(storageBadge(item), ui),
                color = Color.White,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
            if (item.isPrivate) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = ui.getString(R.string.ui_private_video_f0d5d4),
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(18.dp),
                )
            }
            if (showProgress) {
                LinearProgressIndicator(
                    progress = { watchFraction(item) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = VlcOrange,
                    trackColor = Color(0xFF333333),
                )
            }
            ItemActionMenu(viewModel, item, menu)
        }
        Text(item.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Text(storageStatusLine(item, resources = ui), color = if (storageBadge(item) == StorageBadge.Failed) Color(0xFFFFB74D) else VlcMuted, fontSize = 11.sp, maxLines = 2)
        if (showProgress) {
            Text(
                remainingLabel(item, resources = ui).ifBlank { ui.getString(R.string.ui_message_b6bb55, ((watchFraction(item) * 100).toInt()).toString()) },
                color = VlcMuted,
                fontSize = 11.sp,
            )
        }

    }
}

/** The long-press menu of one title: on a poster, and on an episode inside a series. */
@Composable
private fun ItemActionMenu(viewModel: PhoneViewModel, item: LibraryItem, expanded: Boolean) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    DropdownMenu(expanded = expanded, onDismissRequest = { viewModel.actionMenuId = null }) {
        CompactMenuItem(ui.getString(R.string.ui_play_436e61), enabled = item.sourceAvailable != false || item.cloudObjectPresent || item.telegramCopy || item.ownCloudCopy || item.sourceKind == "telegram") {
            viewModel.actionMenuId = null
            viewModel.playOnTv(item)
        }
        if (canSaveToCloud(item)) {
            CompactMenuItem(ui.getString(R.string.ui_save_to_cloud_0fd280)) { viewModel.actionMenuId = null; viewModel.beginSaveToCloud(item) }
        }
        if (canSaveToTelegram(item) && viewModel.telegramSignedInHere && PhoneTelegram.configured) {
            CompactMenuItem(ui.getString(R.string.ui_save_to_telegram_f6c595)) { viewModel.actionMenuId = null; viewModel.saveToTelegram(item) }
        }
        if ((item.cloudObjectPresent || item.ownCloudCopy) && !item.copied) {
            CompactMenuItem(ui.getString(R.string.ui_download_from_cloud_14cd0d)) { viewModel.actionMenuId = null; viewModel.downloadFromCloud(item) }
        }
        if (canRemoveLocalCopy(item)) {
            CompactMenuItem(ui.getString(R.string.ui_free_up_phone_space_6d089c)) { viewModel.actionMenuId = null; viewModel.askFreeUp(item) }
        }
        if (canRemoveOwnCloudCopy(item)) {
            CompactMenuItem(ui.getString(R.string.ui_remove_cloud_copy_0b8eaf)) { viewModel.actionMenuId = null; viewModel.askRemoveCloudCopy(item) }
        }
        viewModel.collections.forEach { collection ->
            CompactMenuItem(ui.getString(R.string.ui_move_to_3fa287, collection.name)) {
                viewModel.actionMenuId = null
                viewModel.toggleCollection(item, collection.id)
            }
        }
        CompactMenuItem(ui.getString(R.string.ui_edit_details_fd9c61)) { viewModel.actionMenuId = null; viewModel.openItem(item); viewModel.editingMetadata = true }
        CompactMenuItem(ui.getString(R.string.ui_delete_e2d0a5)) { viewModel.actionMenuId = null; viewModel.askDelete(item) }
    }
}

@Composable
private fun CompactMenuItem(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    // DropdownMenuItem enforces a 48 dp minimum height, which left gaps between the compact rows.
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minWidth = 140.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .height(40.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .padding(horizontal = 12.dp),
    )
}

@Composable
private fun BrowseScreen(
    viewModel: PhoneViewModel,
    onTelegramAccountHelp: () -> Unit,
    onOpenLocalRoot: () -> Unit,
    onChooseFolder: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var folderToRemove by remember { mutableStateOf<IndexedFolder?>(null) }
    folderToRemove?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToRemove = null },
            title = { Text(ui.getString(R.string.ui_remove_e4dd7c, folder.name)) },
            text = { Text(ui.getString(R.string.ui_cablegram_will_stop_remembering_access_to_this_folder__425a31)) },
            confirmButton = {
                TextButton(onClick = { viewModel.removeBrowseFolder(folder); folderToRemove = null }) { Text(ui.getString(R.string.ui_remove_folder_f2a987)) }
            },
            dismissButton = { TextButton(onClick = { folderToRemove = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
        )
    }
    LaunchedEffect(Unit) {
        if (viewModel.browseSource == BrowseSource.None) {
            viewModel.ensureBrowseRoot()
        }
    }
    val atRoots = viewModel.browseSource == BrowseSource.Roots || viewModel.browseSource == BrowseSource.None
    val cloudRoots = viewModel.browseRoots.filter { it.kind == StorageRootKind.Cloud }
    val entries = visibleBrowseEntries(viewModel.browseEntries, viewModel.browseVideosOnly)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!atRoots) {
                    IconButton(onClick = viewModel::browseUp, modifier = Modifier.maestro(MaestroIds.BROWSE_BACK)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = ui.getString(R.string.ui_back_76900f), tint = Color.White)
                    }
                }
                Text(
                    ui.getString(R.string.ui_import_videos_fec728),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).maestro(MaestroIds.BROWSE_TITLE),
                )
            }
            if (!atRoots) {
                Text(
                    viewModel.browseCrumbs.joinToString(" / ") { it.name },
                    color = VlcMuted,
                    fontSize = 13.sp,
                )
                Text(
                    ui.getString(R.string.ui_select_the_videos_you_want_to_add_to_your_library_896a64),
                    color = VlcOrange,
                    fontSize = 13.sp,
                )
                TextButton(onClick = onChooseFolder) { Text(ui.getString(R.string.ui_add_or_reselect_folder_e5a07e), color = VlcOrange) }
                val fileCount = entries.count { !it.isDirectory }
                TextButton(onClick = viewModel::selectAllBrowseFiles, enabled = fileCount > 0) {
                    Text(
                        when {
                            fileCount == 0 -> ui.getString(R.string.ui_no_files_in_this_folder_38c9a3)
                            viewModel.selectedBrowseIds.isNotEmpty() -> ui.getString(R.string.ui_clear_selection_cea4d2)
                            else -> ui.getString(R.string.ui_select_all_files_7a4dba)
                        },
                        color = if (fileCount == 0) VlcMuted else VlcOrange,
                    )
                }
            }
            viewModel.status?.let { Text(it, color = VlcMuted, fontSize = 13.sp) }
        }
        if (viewModel.browseLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = VlcOrange, trackColor = Color(0xFF333333))
        }
        if (atRoots) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item { TelegramLibraryCard(viewModel) }
                item {
                    SectionCard(ui.getString(R.string.ui_from_your_phone_624fe9)) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(36.dp), tint = VlcOrange)
                        Text(ui.getString(R.string.ui_make_it_a_movie_night_95d58f), color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text(ui.getString(R.string.ui_choose_videos_or_add_folders_with_android_s_picker_one_af9bf1), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onOpenLocalRoot, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.BROWSE_PHONE)) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(ui.getString(R.string.ui_choose_videos_7cf642))
                        }
                        OutlinedButton(onClick = onChooseFolder, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.BROWSE_OPEN_FOLDER)) {
                            Text(ui.getString(R.string.ui_add_folder_5bbfc5))
                        }
                        if (viewModel.folders.isNotEmpty()) {
                            Text(ui.getString(R.string.ui_selected_folders_52fbae), color = Color.White, style = MaterialTheme.typography.titleSmall)
                        }
                        viewModel.folders.forEach { folder ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.openBrowseFolder(Uri.parse(folder.uri), folder.name) }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text(folder.name)
                                }
                                IconButton(onClick = { folderToRemove = folder }) {
                                    Icon(Icons.Default.Close, contentDescription = ui.getString(R.string.ui_remove_folder_1dc4af, folder.name), tint = VlcMuted)
                                }
                            }
                        }
                    }
                }
                item {
                    SectionCard(ui.getString(R.string.ui_connected_storage_ead366)) {
                        TelegramStorageEntry(viewModel, onAccountHelp = onTelegramAccountHelp)
                        if (cloudRoots.isEmpty()) {
                            Text(ui.getString(R.string.ui_keep_a_cloud_copy_for_more_ways_to_watch_you_can_conne_2f290d), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { viewModel.tab = PhoneTab.Storage }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_manage_storage_654d16)) }
                        } else cloudRoots.forEach { root -> StorageRootRow(root) { viewModel.openStorageRoot(root) } }
                    }
                }
                item { Text(ui.getString(R.string.ui_your_videos_stay_in_your_own_storage_cablegram_keeps_y_89b144), color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    BrowseRow(
                        entry,
                        selected = entry.id in viewModel.selectedBrowseIds,
                        onClick = { viewModel.openBrowseEntry(entry) },
                    )
                }
                if (!viewModel.browseLoading && entries.isEmpty()) {
                    item {
                        Text(ui.getString(R.string.ui_no_videos_in_this_folder_try_another_location_00ebc6), color = VlcMuted, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            val selectedCount = viewModel.selectedBrowseIds.size
            Button(
                onClick = viewModel::addSelectedBrowseFiles,
                modifier = Modifier.fillMaxWidth().padding(16.dp).maestro(MaestroIds.BROWSE_ADD_FILES),
                enabled = selectedCount > 0 && !viewModel.busy,
            ) {
                Text(
                    when {
                        selectedCount == 0 -> ui.getString(R.string.ui_select_files_to_add_b8d466)
                        selectedCount == 1 -> ui.getString(R.string.ui_add_1_file_0328b3)
                        else -> ui.getString(R.string.ui_add_files_6e3e20, (selectedCount).toString())
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageRootRow(root: StorageRoot, onClick: () -> Unit) {
    val icon = when (root.kind) {
        StorageRootKind.Internal -> Icons.Default.PhoneAndroid
        StorageRootKind.Attached -> Icons.Default.SdStorage
        StorageRootKind.Cloud -> Icons.Default.Cloud
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
            .maestro("storage_root_${root.id}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = VlcOrange)
        Column(Modifier.weight(1f)) {
            Text(root.name, color = Color.White)
            Text(root.detail, color = VlcMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BrowseRow(entry: BrowseEntry, selected: Boolean, onClick: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val icon = when {
        entry.isDirectory -> Icons.Default.Folder
        entry.isVideo -> Icons.Default.Movie
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!entry.isDirectory) {
            Checkbox(
                checked = selected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = VlcOrange, uncheckedColor = VlcMuted),
            )
        }
        Icon(icon, contentDescription = null, tint = if (entry.isVideo || entry.isDirectory) VlcOrange else VlcMuted)
        Column(Modifier.weight(1f)) {
            Text(entry.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val subtitle = when {
                entry.isDirectory -> ui.getString(R.string.ui_folder_74ccd4)
                entry.isVideo -> listOfNotNull(ui.getString(R.string.ui_video_d534be), entry.sizeBytes?.let(::formatBytes)).joinToString(" · ")
                else -> entry.mime.ifBlank { ui.getString(R.string.ui_file_50009c) }
            }
            Text(subtitle, color = VlcMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PrepareCard(viewModel: PhoneViewModel, modifier: Modifier = Modifier) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE1A1A1A))
            .padding(16.dp)
            .maestro(MaestroIds.PREPARE_CARD),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(ui.getString(R.string.ui_preparing_cf1aa6), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(viewModel.prepareStep.orEmpty(), color = VlcMuted, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { viewModel.prepareProgress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = VlcOrange,
            trackColor = Color(0xFF333333),
        )
    }
}

@Composable
private fun DestinationChoice(label: String, value: String, viewModel: PhoneViewModel) {
    Row(
        Modifier.fillMaxWidth().clickable { viewModel.updateImportDestination(value) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (viewModel.importDestination == value) "●  $label" else "○  $label", color = Color.White)
    }
}

@Composable
private fun StorageScreen(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val phone = viewModel.phoneStorage
    val usedFraction = if (phone.total > 0) (phone.used.toFloat() / phone.total).coerceIn(0f, 1f) else 0f
    val reclaim = freeUpBytes(viewModel.items)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        TextButton(onClick = { viewModel.tab = PhoneTab.Settings }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text(ui.getString(R.string.ui_settings_74a883))
        }
        ScreenHeading(ui.getString(R.string.ui_storage_a69c4d), ui.getString(R.string.ui_your_files_wherever_you_keep_them_b9f61b), Modifier.maestro(MaestroIds.STORAGE_TITLE))
        SectionCard(ui.getString(R.string.ui_on_this_phone_f97da4)) {
            Text(formatBytes(phone.used), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text(ui.getString(R.string.ui_of_used_1db5a8, formatBytes(phone.total)), color = VlcMuted)
            LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = VlcOrange, trackColor = PhoneOutline)
            StorageMetric(ui.getString(R.string.ui_cablegram_media_1fb5f3), formatBytes(phone.media))
            StorageMetric(ui.getString(R.string.ui_other_files_apps_a4dea1), formatBytes((phone.used - phone.media).coerceAtLeast(0)))
            if (reclaim > 0) OutlinedButton(onClick = viewModel::openFreeUp, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_free_up_8fe74c, formatBytes(reclaim))) }
        }
        SectionCard(ui.getString(R.string.ui_cloud_storage_9644ab)) {
            StorageMetric(ui.getString(R.string.ui_media_saved_9537f2), formatBytes(cloudMediaBytes(viewModel.items)))
            StorageMetric(ui.getString(R.string.ui_available_e67444), if (viewModel.cloudConnected) destinationSpaceLine(viewModel.storage, viewModel.cloudAvailable, resources = ui) else ui.getString(R.string.ui_connect_your_own_storage_10a917))
            if (viewModel.cloudConnected) Text(ui.getString(R.string.ui_connected_96d2ef, viewModel.storage?.connection?.displayLabel ?: ui.getString(R.string.ui_your_own_storage_cc541c)), color = Color(0xFF79D6B0))
            OutlinedButton(onClick = { viewModel.cloudSheet = CloudSheet.Manage }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.STORAGE_MANAGE)) { Text(ui.getString(R.string.ui_manage_cloud_storage_4ba3e8)) }
        }
        SectionCard(ui.getString(R.string.ui_saved_in_the_cloud_dc46d5)) {
            val cloudItems = cloudFiles(viewModel.items)
            if (cloudItems.isEmpty()) Text(ui.getString(R.string.ui_no_cloud_copies_yet_open_a_title_to_save_a_copy_ffe83b), color = VlcMuted)
            cloudItems.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { viewModel.openItem(item) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, ui.getString(R.string.ui_view_title_c9916d), tint = VlcMuted)
                }
            }
        }
        viewModel.status?.let { StatusNote(it) }
    }
}

@Composable
private fun StorageMetric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = VlcMuted, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsScreen(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (viewModel.subtitleAccountsOpen) {
        BackHandler { viewModel.subtitleAccountsOpen = false }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { viewModel.subtitleAccountsOpen = false }) { Text(ui.getString(R.string.ui_back_to_settings_ea37ec)) }
            SubtitleProviderSetup(viewModel, onDone = { viewModel.subtitleAccountsOpen = false }, doneLabel = ui.getString(R.string.ui_done_11a676), requireAccount = false)
        }
        return
    }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val overviewScroll = rememberScrollState()
    val detailScroll = rememberScrollState()
    LaunchedEffect(page) {
        viewModel.status = null
        detailScroll.scrollTo(0)
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var apiDraft by remember(viewModel.apiBaseUrl) { mutableStateOf(viewModel.apiBaseUrl) }
    var tunnelDraft by remember(viewModel.tunnelUrl) { mutableStateOf(viewModel.tunnelUrl) }
    var accountNameDraft by remember(viewModel.householdName) { mutableStateOf(viewModel.householdName) }
    var tvToRemove by remember { mutableStateOf<PairedTv?>(null) }
    var tvToRename by remember { mutableStateOf<PairedTv?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showLegal by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<HouseholdProfile?>(null) }
    var languageProfile by remember { mutableStateOf<HouseholdProfile?>(null) }
    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
        viewModel.loadProfileLock()
        viewModel.loadRelayUsage()
    }
    Column(Modifier.fillMaxSize().verticalScroll(if (page == null) overviewScroll else detailScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ScreenHeading(ui.getString(R.string.ui_settings_74a883), viewModel.householdName.ifBlank { ui.getString(R.string.ui_make_cablegram_yours_13baae) }, Modifier.fillMaxWidth().maestro(MaestroIds.SETTINGS_TITLE))
        if (page != null) {
            TextButton(onClick = { page = null }, modifier = Modifier.maestro("settings_back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(ui.getString(R.string.ui_back_to_settings_ea37ec))
            }
        } else {
            Surface(color = VlcPanel, shape = RoundedCornerShape(20.dp)) {
                Column {
                    SettingsNavigationRow(ui.getString(R.string.ui_account_7e1b0d), Icons.Default.Lock, "settings_category_account") { page = "account" }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PhoneOutline)
                    SettingsNavigationRow(ui.getString(R.string.ui_your_tvs_427608), Icons.Default.Tv, "settings_category_tvs") { page = "tvs" }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PhoneOutline)
                    SettingsNavigationRow(ui.getString(R.string.ui_profiles_535e52), Icons.Default.PhoneAndroid, "settings_category_profiles") { page = "profiles" }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PhoneOutline)
                    SettingsNavigationRow(ui.getString(R.string.ui_transfers_privacy_af4a2f), Icons.Default.Sync, "settings_category_transfers") { page = "transfers" }
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = PhoneOutline)
                    SettingsNavigationRow(ui.getString(R.string.ui_app_connection_535ee6), Icons.Default.Settings, "settings_category_connection") { page = "connection" }
                }
            }
            SettingsNavigationRow(ui.getString(R.string.ui_subtitle_accounts_9602a0), Icons.AutoMirrored.Filled.LibraryBooks, "settings_subtitle_accounts") { viewModel.subtitleAccountsOpen = true }
        }
        if (page == "account") SectionCard(ui.getString(R.string.ui_account_7e1b0d)) {
            OutlinedTextField(
                value = accountNameDraft,
                onValueChange = { accountNameDraft = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(ui.getString(R.string.ui_account_name_a704d8)) },
                supportingText = { Text(ui.getString(R.string.ui_shown_on_your_phones_and_tvs_b7ea60)) },
            )
            Button(
                onClick = { viewModel.saveHouseholdName(accountNameDraft) },
                enabled = accountNameDraft.trim().isNotEmpty() &&
                    accountNameDraft.trim() != viewModel.householdName && !viewModel.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (viewModel.busy) ui.getString(R.string.ui_saving_23e392) else ui.getString(R.string.ui_save_account_name_7c6744)) }
            HorizontalDivider(color = PhoneOutline)
            viewModel.profileLock?.let { locked ->
                PrefSwitch(ui.getString(R.string.ui_lock_account_on_tvs_011bbe), locked, viewModel::updateProfileLock, MaestroIds.SETTINGS_PROFILE_LOCK)
            }
            Text(
                when (viewModel.profileLock) {
                    true -> ui.getString(R.string.ui_a_tv_asks_this_phone_before_it_opens_any_profile_a60f8b)
                    false -> ui.getString(R.string.ui_a_tv_opens_profiles_without_asking_unless_other_accoun_d6d7d4)
                    null -> ui.getString(R.string.ui_couldn_t_load_the_account_lock_check_your_connection_85acd6)
                },
                color = VlcMuted, style = MaterialTheme.typography.bodySmall,
            )
            // CAB-67: a TV shared with other accounts always asks; this is how long an allowed profile stays open unused.
            viewModel.sharedTvIdleMinutes?.let { minutes ->
                Text(ui.getString(R.string.ui_on_a_tv_shared_with_other_accounts_ask_again_after_f81d7a), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30 to ui.getString(R.string.ui_30_min_f69fa2), 60 to ui.getString(R.string.ui_1_hour_f8b888), 240 to ui.getString(R.string.ui_4_hours_e5bc99), 720 to ui.getString(R.string.ui_12_hours_ff6272)).forEach { (value, label) ->
                        FilterChip(
                            selected = minutes == value,
                            onClick = { viewModel.updateSharedTvIdleMinutes(value) },
                            label = { Text(label) },
                            modifier = Modifier.maestro("settings_shared_tv_idle_$value"),
                        )
                    }
                }
                Text(ui.getString(R.string.ui_without_use_the_tv_then_goes_back_to_who_s_watching_an_cef3ab), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (page == "tvs") SectionCard(ui.getString(R.string.ui_your_tvs_427608)) {
            if (!viewModel.paired) Text(ui.getString(R.string.ui_connect_a_tv_to_enjoy_your_library_on_a_bigger_screen_a1212f), color = VlcMuted)
            viewModel.tvs.forEach { tv ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Tv, null, tint = VlcOrange)
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, color = Color.White)
                        Text(if (tv == viewModel.tvs.lastOrNull()) ui.getString(R.string.ui_current_tv_55310c) else ui.getString(R.string.ui_paired_tv_68fdc7), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                        TvTrustActions(viewModel, tv)
                    }
                    if (tv.deviceId != null) TextButton(onClick = { tvToRename = tv }, enabled = !viewModel.busy, modifier = Modifier.maestro("tv_rename_${tv.pin}")) {
                        Text(stringResource(R.string.tv_rename))
                    }
                    TextButton(onClick = { tvToRemove = tv }, enabled = !viewModel.busy) {
                        Text(ui.getString(R.string.ui_remove_c3812f), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Button(onClick = viewModel::startAddTv, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.SETTINGS_ADD_TV)) { Text(ui.getString(R.string.ui_connect_a_tv_0ff3f5)) }
        }
        if (page == "transfers") viewModel.relayUsage?.let { usage -> SectionCard(ui.getString(R.string.ui_relay_c226b0)) { RelayUsageCard(usage) } }
        if (page == "profiles") SectionCard(ui.getString(R.string.ui_profiles_535e52)) {
            viewModel.profiles.forEach { profile ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, color = Color.White)
                        Text(
                            when {
                                profile.sortOrder == 0 -> ui.getString(R.string.ui_account_owner_801ba1)
                                profile.pinSet -> ui.getString(R.string.ui_pin_protected_d6dcf5)
                                else -> ui.getString(R.string.ui_profile_d696a3)
                            },
                            color = VlcMuted, style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { languageProfile = profile }, enabled = !viewModel.busy,
                            modifier = Modifier.maestro("profile_language_${profile.id}")) {
                            val language = profile.uiLanguageOverride?.let { tag ->
                                java.util.Locale.forLanguageTag(phoneUiLanguage(listOf(tag), ui.getStringArray(R.array.ui_languages).toSet())).let { it.getDisplayLanguage(it) }
                            } ?: ui.getString(R.string.ui_follow_phone_language_5b74c6)
                            Text(ui.getString(R.string.ui_language_a4fe65) + ": " + language)
                        }
                    }
                    if (profile.sortOrder != 0) {
                        TextButton(onClick = { profileToDelete = profile }, enabled = !viewModel.busy, modifier = Modifier.maestro("profile_delete_${profile.name}")) {
                            Text(ui.getString(R.string.ui_delete_e2d0a5), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Text(ui.getString(R.string.ui_add_profiles_on_the_tv_deleting_one_removes_its_progre_22f2f3), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (page == null) Surface(color = VlcPanel, shape = RoundedCornerShape(20.dp), onClick = { viewModel.tab = PhoneTab.Storage }, modifier = Modifier.maestro(MaestroIds.NAV_STORAGE)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.Storage, null, tint = VlcOrange)
                Column(Modifier.weight(1f)) {
                    Text(ui.getString(R.string.ui_storage_a69c4d), color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(ui.getString(R.string.ui_phone_space_cloud_copies_transfers_f4925b), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null, tint = VlcMuted)
            }
        }
        if (page == "transfers") SectionCard(ui.getString(R.string.ui_transfers_privacy_af4a2f)) {
            PrefSwitch(ui.getString(R.string.ui_open_tv_with_cast_0dd4f2), viewModel.castConnect, viewModel::updateCastConnect, MaestroIds.SETTINGS_CAST_CONNECT)
            PrefSwitch(ui.getString(R.string.ui_show_remote_controls_b49919), viewModel.legacyRemote, viewModel::updateLegacyRemote, MaestroIds.SETTINGS_LEGACY_REMOTE)
            PrefSwitch(ui.getString(R.string.ui_transfer_on_wi_fi_only_992dd6), viewModel.wifiOnlyTransfers, viewModel::setWifiOnly)
            PrefSwitch(ui.getString(R.string.ui_transfer_only_while_charging_645032), viewModel.transferWhileCharging, viewModel::updateChargingOnly)
            HorizontalDivider(color = PhoneOutline)
            PrefSwitch(ui.getString(R.string.ui_sync_library_titles_b3b6cc), viewModel.syncLibraryToApi, viewModel::updateSyncLibrary)
            HorizontalDivider(color = PhoneOutline)
            Text(ui.getString(R.string.ui_relay_over_mobile_data_a8efaa), color = Color.White)
            Text(
                ui.getString(R.string.ui_when_your_tv_can_t_reach_this_phone_on_wi_fi_cablegram_b99318),
                color = VlcMuted, style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RelayMobileDataPolicy.Ask to ui.getString(R.string.ui_ask_b8c209), RelayMobileDataPolicy.Always to ui.getString(R.string.ui_always_de9f05), RelayMobileDataPolicy.Never to ui.getString(R.string.ui_never_6300ef)).forEach { (policy, label) ->
                    FilterChip(
                        selected = viewModel.relayMobileData == policy,
                        onClick = { viewModel.updateRelayMobileData(policy) },
                        label = { Text(label) },
                        modifier = Modifier.maestro("relay_mobile_${policy.name.lowercase()}"),
                    )
                }
            }
            Text(ui.getString(R.string.ui_share_your_catalog_with_your_household_your_video_file_fc6c1f), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (page == "connection") SectionCard(ui.getString(R.string.ui_app_connection_535ee6)) {
            OutlinedButton(onClick = viewModel::clearLocalMetadata, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_clear_artwork_cache_6b5bf3)) }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) ui.getString(R.string.ui_hide_advanced_settings_82557c) else ui.getString(R.string.ui_advanced_connection_settings_891724)) }
            if (advanced) {
                Text(ui.getString(R.string.ui_change_these_only_if_you_use_your_own_server_79f30a), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(apiDraft, { apiDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(ui.getString(R.string.ui_api_server_86067e)) }, isError = apiDraft.isNotBlank() && !isValidServerUrl(apiDraft))
                OutlinedTextField(tunnelDraft, { tunnelDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(ui.getString(R.string.ui_tunnel_url_optional_b31277)) }, isError = tunnelDraft.isNotBlank() && !isValidServerUrl(tunnelDraft))
                Button(onClick = { viewModel.updateApiBaseUrl(apiDraft); viewModel.updateTunnelUrl(tunnelDraft) },
                    enabled = isValidServerUrl(apiDraft) && (tunnelDraft.isBlank() || isValidServerUrl(tunnelDraft))) { Text(ui.getString(R.string.ui_save_connection_df24ef)) }
                Text(viewModel.phoneIps.joinToString(" · ").ifBlank { ui.getString(R.string.ui_no_wi_fi_address_d5b041) }, color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (page == "account") {
            OutlinedButton(
                onClick = { confirmSignOut = true },
                enabled = !viewModel.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.SETTINGS_SIGN_OUT),
            ) { Text(ui.getString(R.string.ui_sign_out_48f0d3), color = MaterialTheme.colorScheme.error) }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = viewModel::openDeleteAccount,
                    enabled = !viewModel.busy,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("settings_delete_account"),
                ) { Text(stringResource(R.string.delete_account_entry), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.delete_account_entry_hint), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        viewModel.status?.let { StatusNote(it) }
        if (page == null) {
            TextButton(onClick = viewModel::replayWelcomeTour, modifier = Modifier.maestro(MaestroIds.SETTINGS_WELCOME_TOUR)) { Text(ui.getString(R.string.ui_show_the_welcome_tour_140eac)) }
            TextButton(onClick = { showLegal = true }) { Text(ui.getString(R.string.ui_licence_and_third_party_notices_0dcfa5)) }
            Text(ui.getString(R.string.ui_cablegram_your_videos_your_screen_your_space_b83cca, BuildConfig.VERSION_NAME), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (page == "account") {
            val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uriHandler.openUri(DELETE_ACCOUNT_WEB_URL) }) { Text(stringResource(R.string.delete_account_web_link)) }
        }
    }
    if (showLegal) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val legal = remember { LegalText.read(context) }
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { showLegal = false },
            title = { Text(ui.getString(R.string.ui_licence_and_notices_ded184)) },
            text = { Text(legal, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showLegal = false }) { Text(ui.getString(R.string.ui_close_7d9eb7)) } },
        )
    }
    tvToRename?.let { tv ->
        var draft by remember(tv.pin) { mutableStateOf(tv.name) }
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { tvToRename = null },
            title = { Text(stringResource(R.string.tv_rename_title)) },
            text = { OutlinedTextField(draft, { draft = it.take(80) }, Modifier.fillMaxWidth().maestro("tv_rename_field"), singleLine = true, label = { Text(stringResource(R.string.tv_name_label)) }) },
            confirmButton = { TextButton(onClick = { viewModel.renameTv(tv, draft); tvToRename = null }, enabled = draft.isNotBlank()) { Text(ui.getString(R.string.ui_save_changes_dd0ae7)) } },
            dismissButton = { TextButton(onClick = { tvToRename = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
        )
    }
    tvToRemove?.let { tv ->
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { tvToRemove = null }, title = { Text(ui.getString(R.string.ui_remove_e4dd7c, tv.name)) },
            text = { Text(ui.getString(R.string.ui_the_tv_will_be_signed_out_and_lose_access_to_your_libr_699735)) },
            confirmButton = { TextButton(onClick = { viewModel.unpair(tv); tvToRemove = null }) { Text(ui.getString(R.string.ui_remove_tv_7c437e)) } },
            dismissButton = { TextButton(onClick = { tvToRemove = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
        )
    }
    if (confirmSignOut) AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { confirmSignOut = false },
        title = { Text(ui.getString(R.string.ui_sign_out_c1c084)) },
        text = { Text(ui.getString(R.string.ui_this_phone_stops_sharing_and_controlling_your_tvs_your_960038)) },
        confirmButton = { TextButton(onClick = { viewModel.signOut(); confirmSignOut = false }) { Text(ui.getString(R.string.ui_sign_out_48f0d3)) } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
    )
    languageProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { languageProfile = null },
            title = { Text(ui.getString(R.string.ui_language_a4fe65)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val options = listOf(null to ui.getString(R.string.ui_follow_phone_language_5b74c6)) +
                        ui.getStringArray(R.array.ui_languages).map { tag ->
                            tag to java.util.Locale.forLanguageTag(phoneUiLanguage(listOf(tag), ui.getStringArray(R.array.ui_languages).toSet())).let { it.getDisplayLanguage(it) }
                        }
                    options.forEach { (tag, label) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            viewModel.updateProfileLanguage(profile, tag)
                            languageProfile = null
                        }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = if (tag == null) profile.uiLanguageOverride == null else profile.uiLanguageOverride?.let { phoneUiLanguage(listOf(it), ui.getStringArray(R.array.ui_languages).toSet()) == tag } == true, onClick = null)
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { languageProfile = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
        )
    }
    profileToDelete?.let { profile ->
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { profileToDelete = null },
            title = { Text(ui.getString(R.string.ui_delete_647459, profile.name)) },
            text = { Text(ui.getString(R.string.ui_their_watch_progress_and_my_list_are_removed_from_ever_c54b92)) },
            confirmButton = { TextButton(onClick = { viewModel.deleteProfile(profile); profileToDelete = null }) { Text(ui.getString(R.string.ui_delete_profile_47311a)) } },
            dismissButton = { TextButton(onClick = { profileToDelete = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) } },
        )
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    maestroId: String,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, color = VlcPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.maestro(maestroId)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, null, tint = VlcOrange, modifier = Modifier.size(22.dp))
            Text(title, modifier = Modifier.weight(1f), color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Default.ChevronRight, null, tint = VlcMuted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun PrefSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit, maestroId: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            modifier = maestroId?.let { Modifier.maestro(it) } ?: Modifier)
    }
}

/** CAB-29: a cover made on this phone stays here unless the user saves it to the household. */
@Composable
private fun HouseholdArtworkRow(viewModel: PhoneViewModel, item: LibraryItem) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (!canSaveArtworkToHousehold(item)) return
    val saved = artworkSavedToHousehold(item)
    var confirm by remember(item.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (saved) ui.getString(R.string.ui_cover_saved_to_your_household_762369) else ui.getString(R.string.ui_cover_kept_on_this_phone_tvs_show_it_while_this_phone__7b244e),
            color = VlcMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).maestro("detail_artwork_household_status"),
        )
        if (saved) TextButton(onClick = { viewModel.keepArtworkOnPhone(item) }, modifier = Modifier.maestro("detail_artwork_remove_household")) { Text(ui.getString(R.string.ui_remove_c3812f)) }
        else TextButton(onClick = { confirm = true }, modifier = Modifier.maestro("detail_artwork_save_household")) { Text(ui.getString(R.string.ui_save_artwork_to_household_2be5ad)) }
    }
    if (confirm) AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { confirm = false },
        title = { Text(ui.getString(R.string.ui_save_artwork_to_household_e25fd0)) },
        text = {
            Text(ui.getString(R.string.ui_this_cover_was_made_on_this_phone_from_your_video_or_y_640d31) +
                ui.getString(R.string.ui_with_your_household_library_everyone_in_your_household_90c154) +
                ui.getString(R.string.ui_even_when_this_phone_is_away_and_anyone_given_its_link_c6c081) +
                ui.getString(R.string.ui_it_stays_on_the_server_until_you_tap_remove_here_chang_9f0819))
        },
        confirmButton = { TextButton(onClick = { confirm = false; viewModel.saveArtworkToHousehold(item) }, modifier = Modifier.maestro("artwork_household_confirm")) { Text(ui.getString(R.string.ui_save_to_household_f31fbf)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(ui.getString(R.string.ui_keep_on_phone_bded0c)) } },
    )
}

@Composable
private fun DetailOverlay(viewModel: PhoneViewModel, item: LibraryItem) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val sourceUnavailable = item.sourceAvailable == false && !item.cloudObjectPresent && !item.ownCloudCopy
    val draftBase = remember(item.id, viewModel.editingMetadata) { item }
    var title by remember(item.id, viewModel.editingMetadata) { mutableStateOf(item.title) }
    var year by remember(item.id, viewModel.editingMetadata) { mutableStateOf(item.year?.toString().orEmpty()) }
    var mediaType by remember(item.id, viewModel.editingMetadata) { mutableStateOf(item.mediaType) }
    var overview by remember(item.id, viewModel.editingMetadata) { mutableStateOf(item.overview.orEmpty()) }
    BackHandler { if (viewModel.editingMetadata) viewModel.editingMetadata = false else viewModel.closeItem() }
    Column(Modifier.fillMaxSize().background(VlcBlack).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (viewModel.editingMetadata) viewModel.editingMetadata = false else viewModel.closeItem() }, modifier = Modifier.maestro(MaestroIds.DETAIL_BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text(if (viewModel.editingMetadata) ui.getString(R.string.ui_cancel_19766e) else ui.getString(R.string.ui_library_dc20b3))
            }
            Spacer(Modifier.weight(1f))
            if (!viewModel.editingMetadata) {
                TextButton(onClick = { viewModel.findDetailsAndArtwork(item) }, modifier = Modifier.maestro("detail_edit_artwork")) { Text(ui.getString(R.string.ui_edit_details_cover_134bbc)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaArtwork(item, Modifier.width(130.dp).height(190.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (item.mediaType == "tv") ui.getString(R.string.ui_tv_show_453862) else ui.getString(R.string.ui_movie_941de4), color = VlcOrange, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(listOfNotNull(item.year?.toString(), formatDuration(item.durationSeconds)?.takeIf { it.isNotBlank() }, item.resolution).joinToString(" · "), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                if (item.isPrivate) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = VlcMuted)
                    Text(ui.getString(R.string.ui_phone_approval_required_112e8c), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!viewModel.editingMetadata) HouseholdArtworkRow(viewModel, item)
        if (item.metadataConflict) Text(ui.getString(R.string.ui_details_changed_on_another_device_your_edits_are_kept__e208b8), color = VlcOrange)
        else if (item.pendingMetadataFields.isNotEmpty()) Text(ui.getString(R.string.ui_details_saved_on_this_phone_waiting_to_sync_fd7196), color = VlcMuted)
        if (viewModel.editingMetadata) {
            SectionCard(ui.getString(R.string.ui_edit_details_fd9c61)) {
                Text(ui.getString(R.string.ui_message_13326c, if (item.sourceKind == "web") ui.getString(R.string.ui_original_link_1f7d58) else ui.getString(R.string.ui_original_file_0ee08b), item.filename), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(ui.getString(R.string.ui_title_7e8cd2)) })
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(ui.getString(R.string.ui_year_89f683)) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mediaType != "tv", onClick = { mediaType = "movie" }, label = { Text(ui.getString(R.string.ui_movie_941de4)) })
                    FilterChip(selected = mediaType == "tv", onClick = { mediaType = "tv" }, label = { Text(ui.getString(R.string.ui_tv_show_0f6684)) })
                }
                OutlinedTextField(overview, { overview = it.take(10000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, label = { Text(ui.getString(R.string.ui_summary_8e76a9)) })
                Button(onClick = { viewModel.saveMetadata(draftBase, title.trim(), year.toIntOrNull(), mediaType, overview) }, enabled = title.isNotBlank() && (year.isBlank() || year.toIntOrNull() in 1..9999) && !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_save_changes_dd0ae7)) }
            }
        } else {
            if (item.positionSeconds > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { watchFraction(item) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = VlcOrange, trackColor = PhoneOutline)
                    Text(remainingLabel(item, resources = ui), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = { viewModel.playOnTv(item) }, enabled = !sourceUnavailable && !viewModel.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro(MaestroIds.DETAIL_PLAY_TV)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(if (sourceUnavailable) ui.getString(R.string.ui_source_unavailable_a3feb8) else if (viewModel.paired) ui.getString(R.string.ui_play_on_tv_f47dcd) else ui.getString(R.string.ui_connect_a_tv_to_play_c4ff0b))
            }
            // What the TV did with the tap ("Starting on…", "Done on…", or why not), right where it was made.
            viewModel.remoteStatus?.let { Text(it, color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
            if (sourceUnavailable) {
                Text(
                    if (item.householdOnly) ui.getString(R.string.ui_this_title_is_in_your_household_library_but_its_file_i_7fef21)
                    else ui.getString(R.string.ui_reconnect_the_original_storage_or_restore_the_file_on__42719e),
                    color = VlcMuted, style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::syncNow, enabled = viewModel.librarySyncState != LibrarySyncState.Running) { Text(ui.getString(R.string.ui_check_source_again_115706)) }
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = VlcMuted, style = MaterialTheme.typography.bodyLarge) }
            LaunchedEffect(item.id) { viewModel.refreshSubtitleStatus(item) }
            if (viewModel.savedSubtitle != null) TextButton(onClick = { viewModel.removeSavedSubtitle(item) }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_remove_saved_subtitle_4ed88f)) }
            OutlinedButton(onClick = { viewModel.subtitleFlowOpen = true }, enabled = !sourceUnavailable && viewModel.accountTokenOrNull() != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.DETAIL_FIND_SUBTITLES)) {
                Text(viewModel.savedSubtitle?.let { ui.getString(R.string.ui_subtitles_find_another_59ba49, it) } ?: ui.getString(R.string.ui_find_subtitles_8a4dbc))
            }
            if (viewModel.savedSubtitle != null) SavedSubtitleTimingButton(viewModel, item, enabled = !sourceUnavailable && viewModel.accountTokenOrNull() != null)
            LocalSubtitleAttachmentButton(viewModel, item, enabled = !sourceUnavailable && viewModel.accountTokenOrNull() != null)
            SectionCard(ui.getString(R.string.ui_your_copy_c7710a)) {
                StorageMetric("Location", storageStatusLine(item, resources = ui))
                item.fileSizeBytes?.let { StorageMetric(ui.getString(R.string.ui_file_size_ff0442), formatBytes(it)) }
                if (item.transferStatus == TRANSFER_FAILED && item.webTransferError == TELEGRAM_SAVE_FAILED && !sourceUnavailable) {
                    OutlinedButton(onClick = { viewModel.saveToTelegram(item) }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_retry_saving_to_telegram_eed237)) }
                } else if (item.transferStatus == TRANSFER_FAILED && !sourceUnavailable && item.sourceKind != "web") OutlinedButton(onClick = { viewModel.retrySave(item) }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_retry_cloud_save_6ac597)) }
                else if (canSaveToCloud(item)) OutlinedButton(onClick = { viewModel.beginSaveToCloud(item) }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.DETAIL_SAVE_CLOUD)) { Text(ui.getString(R.string.ui_save_a_cloud_copy_dbaf92)) }
                if (viewModel.telegramSignedInHere && PhoneTelegram.configured && canSaveToTelegram(item) && item.webTransferError != TELEGRAM_SAVE_FAILED) {
                    OutlinedButton(onClick = { viewModel.saveToTelegram(item) }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_save_to_telegram_f6c595)) }
                }
                if ((item.cloudObjectPresent || item.ownCloudCopy) && !item.copied) OutlinedButton(onClick = { viewModel.downloadFromCloud(item) }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_download_to_phone_6265e3)) }
                if (canRemoveLocalCopy(item)) TextButton(onClick = { viewModel.askFreeUp(item) }) { Text(ui.getString(R.string.ui_free_up_phone_space_6d089c)) }
                if (canRemoveOwnCloudCopy(item)) TextButton(onClick = { viewModel.askRemoveCloudCopy(item) }) { Text(ui.getString(R.string.ui_remove_cloud_copy_0b8eaf)) }
            }
            if (viewModel.collections.isNotEmpty()) SectionCard(ui.getString(R.string.ui_collections_9f9fea)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.collections.forEach { collection ->
                        FilterChip(selected = collection.id in item.collectionIds, onClick = { viewModel.toggleCollection(item, collection.id) }, label = { Text(collection.name) })
                    }
                }
            }
            TextButton(onClick = { viewModel.askDelete(item) }) { Text(ui.getString(R.string.ui_remove_title_0a053b), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RemoveCloudCopyDialog(viewModel: PhoneViewModel, item: LibraryItem) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { viewModel.removeCloudCopyTarget = null },
        title = { Text(ui.getString(R.string.ui_remove_the_cloud_copy_bd936f)) },
        text = { Text(removeCopyQuestion(item.title, viewModel.saveDestinationName)) },
        confirmButton = {
            TextButton(onClick = { viewModel.removeCloudCopy(item) }) { Text(ui.getString(R.string.ui_remove_copy_63d1ae), color = Color(0xFFFF8A80)) }
        },
        dismissButton = { TextButton(onClick = { viewModel.removeCloudCopyTarget = null }) { Text(ui.getString(R.string.ui_keep_it_fdce5d)) } },
    )
}

@Composable
private fun DeleteDialog(viewModel: PhoneViewModel, item: LibraryItem) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    if (item.sourceKind == "telegram") {
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { viewModel.deleteTarget = null },
            title = { Text(ui.getString(R.string.ui_remove_e4dd7c, item.title)) },
            text = {
                Text(
                    ui.getString(R.string.ui_this_video_is_stored_in_your_telegram_channel_not_on_t_c9f607) +
                        ui.getString(R.string.ui_on_the_phone_and_every_tv_and_you_can_restore_it_later_69a576) +
                        ui.getString(R.string.ui_from_the_channel_in_telegram_and_can_t_be_undone_732b94),
                )
            },
            // One column in the confirm slot: the dialog's own button row would space two slots apart and clip the third.
            confirmButton = {
                StackedDialogButtons {
                    TextButton(onClick = { viewModel.removeTelegramTitle(item, permanently = true) }) {
                        Text(ui.getString(R.string.ui_delete_permanently_280b10), color = Color(0xFFFF8A80))
                    }
                    TextButton(onClick = { viewModel.removeTelegramTitle(item, permanently = false) }) { Text(ui.getString(R.string.ui_remove_from_library_8e7161)) }
                    TextButton(onClick = { viewModel.deleteTarget = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) }
                }
            },
        )
        return
    }
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { viewModel.deleteTarget = null },
        title = { Text(ui.getString(R.string.ui_remove_e4dd7c, item.title)) },
        text = { Text(ui.getString(R.string.ui_remove_from_library_keeps_the_file_delete_file_permane_9b57b7)) },
        confirmButton = {
            StackedDialogButtons {
                TextButton(onClick = { viewModel.deleteFilePermanently(item) }) { Text(ui.getString(R.string.ui_delete_file_permanently_7afb48)) }
                TextButton(onClick = { viewModel.removeFromLibrary(item) }) { Text(ui.getString(R.string.ui_remove_from_library_8e7161)) }
                TextButton(onClick = { viewModel.deleteTarget = null }) { Text(ui.getString(R.string.ui_cancel_19766e)) }
            }
        },
    )
}

/** Three or more dialog actions, stacked and right-aligned with no extra gaps. */
@Composable
private fun StackedDialogButtons(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, content = content)
}

@Composable
private fun RemoteBar(viewModel: PhoneViewModel, modifier: Modifier = Modifier) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val lines = miniPlayerLines(viewModel.nowPlaying?.title, viewModel.startingTitle, viewModel.tvName, viewModel.remoteStatus) ?: return
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE1A1A1A))
            .clickable { viewModel.tab = PhoneTab.Remote; viewModel.closeItem() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Tv, contentDescription = null, tint = VlcOrange)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(lines.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The command's progress and result show here, so a TV that does not confirm is visible
            // without opening the Remote tab.
            Text(lines.caption, color = VlcMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (lines.canToggle) IconButton(onClick = viewModel::togglePlayPause) {
            Icon(if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (viewModel.paused) ui.getString(R.string.ui_resume_playback_b19825) else ui.getString(R.string.ui_pause_playback_65a4a8), tint = Color.White)
        }
    }
}

@Composable
private fun RemoteControls(viewModel: PhoneViewModel) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val item = viewModel.nowPlaying ?: return
    Text(ui.getString(R.string.ui_message_fed2d9, viewModel.tvName, item.title), color = VlcMuted)
    LinearProgressIndicator(progress = { watchFraction(item) }, modifier = Modifier.fillMaxWidth(), color = VlcOrange, trackColor = Color(0xFF333333))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { viewModel.skipSeconds(-CastRemoteReceiver.SEEK_SECONDS) }) { Text(ui.getString(R.string.ui_message_a4d4c7, (CastRemoteReceiver.SEEK_SECONDS).toString())) }
        IconButton(onClick = viewModel::togglePlayPause) {
            Icon(if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White)
        }
        TextButton(onClick = { viewModel.skipSeconds(CastRemoteReceiver.SEEK_SECONDS) }) { Text(ui.getString(R.string.ui_message_9262e3, (CastRemoteReceiver.SEEK_SECONDS).toString())) }
    }
    TextButton(onClick = viewModel::stopCast) { Text(ui.getString(R.string.ui_stop_casting_0f1360)) }
}

internal fun displayName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
}

/** Spec 003 US4: this month's relay traffic against the plan, with the 80 % warning. */
@Composable
private fun RelayUsageCard(usage: RelayUsage) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)
    val quota = usage.quotaBytes
    val resets = usage.resetsAt?.take(10)
    Text(
        when {
            quota == null -> ui.getString(R.string.ui_unlimited_relay_gb_used_this_month_3d67d0, gb(usage.usedBytes))
            else -> ui.getString(R.string.ui_of_gb_used_this_month_0cb41d, gb(usage.usedBytes), gb(quota))
        },
        color = Color.White,
    )
    if (quota != null && quota > 0) {
        val fraction = (usage.usedBytes.toFloat() / quota).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (fraction >= 0.8f) Color(0xFFFFB74D) else VlcOrange,
            trackColor = PhoneOutline,
        )
        val note = when {
            usage.limitedReason == "relay_email_unverified" -> ui.getString(R.string.ui_confirm_your_email_above_to_use_free_relay_73d5f0)
            usage.limitedReason == "relay_free_device_limit" ->
                ui.getString(R.string.ui_free_relay_isn_t_available_here_this_phone_already_giv_decf4c)
            fraction >= 1f -> ui.getString(R.string.ui_this_month_s_free_relay_traffic_is_used_up_same_wi_fi__ef740a, resets?.let { " (resets $it)" } ?: "")
            fraction >= 0.8f -> ui.getString(R.string.ui_you_ve_used_of_this_month_s_free_relay_traffic_f1430c, (fraction * 100).toInt())
            else -> ui.getString(R.string.ui_the_relay_is_used_when_your_tv_can_t_reach_this_phone__b14902, resets?.let { ui.getString(R.string.ui_resets_ecf9e5, it) } ?: "")
        }
        Text(note, color = if (fraction >= 0.8f) Color(0xFFFFB74D) else VlcMuted, style = MaterialTheme.typography.bodySmall)
    }
}
