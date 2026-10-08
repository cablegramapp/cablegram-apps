package app.cablegram.phone

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
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.addPickedFiles(uris)
    }
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
                        PhoneTab.Browse -> BrowseScreen(viewModel, onOpenLocalRoot = { pickFiles.launch(arrayOf("video/*")) })
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
                title = { Text("Play on TV") },
                text = { Column {
                    viewModel.castRoutes.forEach { route ->
                        TextButton(onClick = { viewModel.chooseCastRoute(route) }) { Text(route.name) }
                    }
                    if (viewModel.castRoutes.isEmpty()) Text("Looking for TVs…")
                } },
                confirmButton = {},
                dismissButton = { TextButton(onClick = viewModel::dismissCastPicker) { Text("Cancel") } },
            )
            CloudFlow(viewModel)
            if (viewModel.telegramSheetOpen) TelegramConnectSheet(viewModel)
        }
    }
}

@Composable
private fun LibraryNav(viewModel: PhoneViewModel) {
    val items = listOf(
        Triple(PhoneTab.Library, Icons.AutoMirrored.Filled.LibraryBooks, "Library"),
        Triple(PhoneTab.Browse, Icons.Default.Add, "Import"),
        Triple(PhoneTab.Remote, Icons.Default.Tv, "Remote"),
        Triple(PhoneTab.Settings, Icons.Default.Settings, "Settings"),
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
    var filter by rememberSaveable { mutableStateOf("All") }
    val queried = searchLibrary(viewModel.items, viewModel.searchQuery)
    val filtered = when (filter) {
        "Movies" -> movies(queried)
        "TV shows" -> tvShows(queried)
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
                    ScreenHeading("Your library", modifier = Modifier.maestro(MaestroIds.LIBRARY_TITLE))
                }
                Surface(color = VlcPanel, shape = RoundedCornerShape(16.dp)) {
                    IconButton(onClick = { viewModel.tab = PhoneTab.Browse }) { Icon(Icons.Default.Add, "Import videos", tint = VlcOrange) }
                }
            }
        }
        item {
            OutlinedTextField(
                value = viewModel.searchQuery,
                onValueChange = { viewModel.searchQuery = it },
                modifier = Modifier.fillMaxWidth().maestro(MaestroIds.LIBRARY_SEARCH),
                singleLine = true,
                placeholder = { Text("Search your films and shows") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = if (viewModel.searchQuery.isNotEmpty()) ({
                    IconButton(onClick = { viewModel.searchQuery = "" }) { Icon(Icons.Default.Close, "Clear search") }
                }) else null,
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", "Movies", "TV shows").forEach { label ->
                    FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (viewModel.paired) viewModel.tvName else "Made for your big screen", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(if (viewModel.paired) "Keep your phone on the same Wi-Fi" else "Connect a TV when you're ready to watch", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (!viewModel.paired || viewModel.legacyRemote) TextButton(onClick = if (viewModel.paired) ({ viewModel.tab = PhoneTab.Remote }) else viewModel::startAddTv) {
                    Text(if (viewModel.paired) "Remote" else "Connect")
                }
            }
            TextButton(onClick = viewModel::syncNow, enabled = viewModel.librarySyncState != LibrarySyncState.Running) {
                Icon(if (viewModel.librarySyncState == LibrarySyncState.Completed) Icons.Default.Check else Icons.Default.Sync, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(when (viewModel.librarySyncState) {
                    LibrarySyncState.Running -> "Syncing library…"
                    LibrarySyncState.Completed -> "Library synced"
                    LibrarySyncState.Failed -> "Sync needs attention · Retry"
                    else -> "Sync library"
                }, style = MaterialTheme.typography.labelMedium)
            }
        }
        viewModel.scanProgress?.let { item { StatusNote(it) } }
        viewModel.status?.let { item { StatusNote(it) } }
        when {
            viewModel.items.isEmpty() -> item {
                EmptyState(Icons.Default.Movie, "A home for your favorites", "Import a few videos from your phone. Your personal cinema starts here.", "Import videos", { viewModel.tab = PhoneTab.Browse })
            }
            filtered.isEmpty() -> item {
                EmptyState(Icons.Default.Search, "No titles found", "Try another title or clear your filters to see the whole library.", "Clear filters", { viewModel.searchQuery = ""; filter = "All" })
            }
            viewModel.searchQuery.isNotBlank() || filter != "All" -> item { Shelf("${filtered.size} titles", filtered, viewModel) }
            else -> {
                if (continueWatching(filtered).isNotEmpty()) item { Shelf("Continue watching", continueWatching(filtered), viewModel, showProgress = true) }
                item { Shelf("Recently added", recentlyAdded(filtered), viewModel) }
                if (movies(filtered).isNotEmpty()) item { Shelf("Movies", movies(filtered), viewModel) }
                if (tvShows(filtered).isNotEmpty()) item { Shelf("TV shows", tvShows(filtered), viewModel) }
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
    viewModel.pendingApprovals.firstOrNull()?.let { approval ->
        Card(
            colors = CardDefaults.cardColors(containerColor = VlcPanel),
            border = BorderStroke(2.dp, VlcOrange),
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (viewModel.pendingApprovals.size > 1) "TV approval · ${viewModel.pendingApprovals.size} requests" else "TV playback approval",
                    color = VlcOrange,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    approval.title?.takeIf { it.isNotBlank() } ?: "A private title wants to play on the TV",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { viewModel.respondToApproval(approval, approve = true) },
                        colors = ButtonDefaults.buttonColors(containerColor = VlcOrange),
                    ) {
                        Text("Allow once", color = VlcBlack)
                    }
                    OutlinedButton(onClick = { viewModel.respondToApproval(approval, approve = false) }) {
                        Text("Not now", color = Color.White)
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
                "TV series",
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
        Text(series.summary(), color = VlcMuted, fontSize = 11.sp, maxLines = 1)
        if (showProgress) {
            Text(
                listOfNotNull(episodeBadge(lead), remainingLabel(lead).ifBlank { null }).joinToString(" · ").ifBlank { "${(watchFraction(lead) * 100).toInt()}%" },
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
    BackHandler(enabled = viewModel.selected == null) { viewModel.closeSeries() }
    val seasons = series.episodes.groupBy { it.seasonNumber }.toSortedMap(compareBy { it ?: Int.MAX_VALUE })
    Column(
        Modifier.fillMaxSize().background(VlcBlack).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = viewModel::closeSeries) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Library")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaArtwork(series.lead, Modifier.width(110.dp).height(160.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TV SHOW", color = VlcOrange, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(series.title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(series.summary(), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        seasons.forEach { (season, episodes) ->
            Text(if (season != null) "Season $season" else "Episodes", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
                        Text(episodeBadge(episode) ?: "Episode", color = Color.White)
                        Text(episode.filename.substringBeforeLast('.'), color = VlcMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(storageStatusLine(episode), color = VlcMuted, fontSize = 11.sp, maxLines = 1)
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
                    ) { Text("Play") }
                    ItemActionMenu(viewModel, episode, viewModel.actionMenuId == rowKey)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PosterCard(item: LibraryItem, showProgress: Boolean, viewModel: PhoneViewModel) {
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
                storageBadgeLabel(storageBadge(item)),
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
                    contentDescription = "Private video",
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
        Text(storageStatusLine(item), color = if (storageBadge(item) == StorageBadge.Failed) Color(0xFFFFB74D) else VlcMuted, fontSize = 11.sp, maxLines = 2)
        if (showProgress) {
            Text(
                remainingLabel(item).ifBlank { "${(watchFraction(item) * 100).toInt()}%" },
                color = VlcMuted,
                fontSize = 11.sp,
            )
        }

    }
}

/** The long-press menu of one title: on a poster, and on an episode inside a series. */
@Composable
private fun ItemActionMenu(viewModel: PhoneViewModel, item: LibraryItem, expanded: Boolean) {
    DropdownMenu(expanded = expanded, onDismissRequest = { viewModel.actionMenuId = null }) {
        CompactMenuItem("Play", enabled = item.sourceAvailable != false || item.cloudObjectPresent || item.telegramCopy || item.ownCloudCopy || item.sourceKind == "telegram") {
            viewModel.actionMenuId = null
            viewModel.playOnTv(item)
        }
        if (canSaveToCloud(item)) {
            CompactMenuItem("Save to Cloud") { viewModel.actionMenuId = null; viewModel.beginSaveToCloud(item) }
        }
        if (canSaveToTelegram(item) && viewModel.telegramLink?.linked == true && PhoneTelegram.configured) {
            CompactMenuItem("Save to Telegram") { viewModel.actionMenuId = null; viewModel.saveToTelegram(item) }
        }
        if ((item.cloudObjectPresent || item.ownCloudCopy) && !item.copied) {
            CompactMenuItem("Download from Cloud") { viewModel.actionMenuId = null; viewModel.downloadFromCloud(item) }
        }
        if (canRemoveLocalCopy(item)) {
            CompactMenuItem("Free up phone space") { viewModel.actionMenuId = null; viewModel.askFreeUp(item) }
        }
        if (canRemoveOwnCloudCopy(item)) {
            CompactMenuItem("Remove cloud copy") { viewModel.actionMenuId = null; viewModel.askRemoveCloudCopy(item) }
        }
        viewModel.collections.forEach { collection ->
            CompactMenuItem("Move to ${collection.name}") {
                viewModel.actionMenuId = null
                viewModel.toggleCollection(item, collection.id)
            }
        }
        CompactMenuItem("Edit details") { viewModel.actionMenuId = null; viewModel.openItem(item); viewModel.editingMetadata = true }
        CompactMenuItem("Delete") { viewModel.actionMenuId = null; viewModel.askDelete(item) }
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
    onOpenLocalRoot: () -> Unit,
) {
    var webUrl by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (viewModel.browseSource == BrowseSource.None || viewModel.browseRoots.isEmpty()) {
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
                Text(
                    "Import videos",
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
                    "Select the videos you want to add to your library.",
                    color = VlcOrange,
                    fontSize = 13.sp,
                )
                val fileCount = entries.count { !it.isDirectory }
                TextButton(onClick = viewModel::selectAllBrowseFiles, enabled = fileCount > 0) {
                    Text(
                        when {
                            fileCount == 0 -> "No files in this folder"
                            viewModel.selectedBrowseIds.isNotEmpty() -> "Clear selection"
                            else -> "Select all files"
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
                item {
                    SectionCard("Play from Web") {
                        Text("Paste a public video page or direct video URL. Cablegram will add its available title, description, and artwork.", color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = webUrl,
                            onValueChange = { webUrl = it.trim().take(4096) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Video URL") },
                            placeholder = { Text("https://…") },
                        )
                        Button(
                            onClick = { viewModel.importWeb(webUrl); webUrl = "" },
                            enabled = !viewModel.busy && (webUrl.startsWith("https://") || webUrl.startsWith("http://")),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            Text(if (viewModel.busy) "Analyzing…" else "Add to library")
                        }
                    }
                }
                item {
                    SectionCard("From your phone") {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(36.dp), tint = VlcOrange)
                        Text("Make it a movie night", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text("Choose videos from your phone or connected storage. Select several to add them together.", color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = onOpenLocalRoot, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.BROWSE_PHONE)) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Choose videos")
                        }
                    }
                }
                item {
                    SectionCard("Connected storage") {
                        TelegramStorageEntry(viewModel)
                        if (cloudRoots.isEmpty()) {
                            Text("Keep a cloud copy for more ways to watch. You can connect your own storage in Settings.", color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { viewModel.tab = PhoneTab.Storage }, modifier = Modifier.fillMaxWidth()) { Text("Manage storage") }
                        } else cloudRoots.forEach { root -> StorageRootRow(root) { viewModel.openStorageRoot(root) } }
                    }
                }
                item { Text("Your videos stay in your own storage. Cablegram keeps your library organized and ready for your TV.", color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
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
                        Text("No videos in this folder. Try another location.", color = VlcMuted, modifier = Modifier.padding(16.dp))
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
                        selectedCount == 0 -> "Select files to add"
                        selectedCount == 1 -> "Add 1 file"
                        else -> "Add $selectedCount files"
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
                entry.isDirectory -> "Folder"
                entry.isVideo -> listOfNotNull("Video", entry.sizeBytes?.let(::formatBytes)).joinToString(" · ")
                else -> entry.mime.ifBlank { "File" }
            }
            Text(subtitle, color = VlcMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PrepareCard(viewModel: PhoneViewModel, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xEE1A1A1A))
            .padding(16.dp)
            .maestro(MaestroIds.PREPARE_CARD),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Preparing", color = Color.White, style = MaterialTheme.typography.titleMedium)
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
    val phone = viewModel.phoneStorage
    val usedFraction = if (phone.total > 0) (phone.used.toFloat() / phone.total).coerceIn(0f, 1f) else 0f
    val reclaim = freeUpBytes(viewModel.items)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        TextButton(onClick = { viewModel.tab = PhoneTab.Settings }) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp)); Text("Settings")
        }
        ScreenHeading("Storage", "Your files, wherever you keep them.", Modifier.maestro(MaestroIds.STORAGE_TITLE))
        SectionCard("On this phone") {
            Text(formatBytes(phone.used), color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("of ${formatBytes(phone.total)} used", color = VlcMuted)
            LinearProgressIndicator(progress = { usedFraction }, modifier = Modifier.fillMaxWidth().height(6.dp), color = VlcOrange, trackColor = PhoneOutline)
            StorageMetric("Cablegram media", formatBytes(phone.media))
            StorageMetric("Other files & apps", formatBytes((phone.used - phone.media).coerceAtLeast(0)))
            if (reclaim > 0) OutlinedButton(onClick = viewModel::openFreeUp, modifier = Modifier.fillMaxWidth()) { Text("Free up ${formatBytes(reclaim)}") }
        }
        SectionCard("Cloud storage") {
            StorageMetric("Media saved", formatBytes(cloudMediaBytes(viewModel.items)))
            StorageMetric("Available", if (viewModel.cloudConnected) destinationSpaceLine(viewModel.storage, viewModel.cloudAvailable) else "Connect your own storage")
            if (viewModel.cloudConnected) Text("Connected: ${viewModel.storage?.connection?.displayLabel ?: "your own storage"}", color = Color(0xFF79D6B0))
            OutlinedButton(onClick = { viewModel.cloudSheet = CloudSheet.Manage }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.STORAGE_MANAGE)) { Text("Manage cloud storage") }
        }
        SectionCard("Saved in the cloud") {
            val cloudItems = cloudFiles(viewModel.items)
            if (cloudItems.isEmpty()) Text("No cloud copies yet. Open a title to save a copy.", color = VlcMuted)
            cloudItems.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { viewModel.openItem(item) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.fileSizeBytes?.let(::formatBytes).orEmpty(), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    Icon(Icons.Default.ChevronRight, "View title", tint = VlcMuted)
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
    var subtitleAccounts by remember { mutableStateOf(false) }
    if (subtitleAccounts) {
        BackHandler { subtitleAccounts = false }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { subtitleAccounts = false }) { Text("Back to Settings") }
            SubtitleProviderSetup(viewModel, onDone = { subtitleAccounts = false }, doneLabel = "Done")
        }
        return
    }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var apiDraft by remember(viewModel.apiBaseUrl) { mutableStateOf(viewModel.apiBaseUrl) }
    var tunnelDraft by remember(viewModel.tunnelUrl) { mutableStateOf(viewModel.tunnelUrl) }
    var accountNameDraft by remember(viewModel.householdName) { mutableStateOf(viewModel.householdName) }
    var tvToRemove by remember { mutableStateOf<PairedTv?>(null) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var showLegal by remember { mutableStateOf(false) }
    var profileToDelete by remember { mutableStateOf<HouseholdProfile?>(null) }
    LaunchedEffect(Unit) {
        viewModel.loadProfiles()
        viewModel.loadRelayUsage()
        viewModel.refreshEmailStatus()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        ScreenHeading("Settings", viewModel.householdName.ifBlank { "Make Cablegram yours." }, Modifier.maestro(MaestroIds.SETTINGS_TITLE))
        SectionCard("Account") {
            OutlinedTextField(
                value = accountNameDraft,
                onValueChange = { accountNameDraft = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Account name") },
                supportingText = { Text("Shown on your phones and TVs") },
            )
            Button(
                onClick = { viewModel.saveHouseholdName(accountNameDraft) },
                enabled = accountNameDraft.trim().isNotEmpty() &&
                    accountNameDraft.trim() != viewModel.householdName && !viewModel.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (viewModel.busy) "Saving…" else "Save account name") }
            if (viewModel.emailVerified == false) {
                Text("Your email isn't confirmed yet. Free relay streaming needs a confirmed email.", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = viewModel::startEmailVerification, modifier = Modifier.fillMaxWidth().maestro("settings_confirm_email")) { Text("Confirm email") }
            }
        }
        OutlinedButton(onClick = { subtitleAccounts = true }, modifier = Modifier.fillMaxWidth().maestro("settings_subtitle_accounts")) { Text("Subtitle provider accounts") }
        SectionCard("Your TVs") {
            if (!viewModel.paired) Text("Connect a TV to enjoy your library on a bigger screen.", color = VlcMuted)
            viewModel.tvs.forEach { tv ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.Tv, null, tint = VlcOrange)
                    Column(Modifier.weight(1f)) {
                        Text(tv.name, color = Color.White)
                        Text(if (tv == viewModel.tvs.lastOrNull()) "Current TV" else "Paired TV", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                        TvTrustActions(viewModel, tv)
                    }
                    TextButton(onClick = { tvToRemove = tv }, enabled = !viewModel.busy) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Button(onClick = viewModel::startAddTv, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.SETTINGS_ADD_TV)) { Text("Connect a TV") }
        }
        viewModel.relayUsage?.let { usage -> SectionCard("Relay") { RelayUsageCard(usage) } }
        if (viewModel.profiles.isNotEmpty()) SectionCard("Profiles") {
            viewModel.profiles.forEach { profile ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, color = Color.White)
                        Text(
                            when {
                                profile.sortOrder == 0 -> "Account owner"
                                profile.pinSet -> "PIN protected"
                                else -> "Profile"
                            },
                            color = VlcMuted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (profile.sortOrder != 0) {
                        TextButton(onClick = { profileToDelete = profile }, enabled = !viewModel.busy, modifier = Modifier.maestro("profile_delete_${profile.name}")) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            Text("Add profiles on the TV. Deleting one removes its progress and My List.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        Surface(color = VlcPanel, shape = RoundedCornerShape(20.dp), onClick = { viewModel.tab = PhoneTab.Storage }, modifier = Modifier.maestro(MaestroIds.NAV_STORAGE)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Default.Storage, null, tint = VlcOrange)
                Column(Modifier.weight(1f)) {
                    Text("Storage", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("Phone space, cloud copies & transfers", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.ChevronRight, null, tint = VlcMuted)
            }
        }
        SectionCard("Collections") {
            OutlinedTextField(viewModel.collectionDraft, { viewModel.collectionDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Collection name") })
            OutlinedButton(onClick = viewModel::createCollection, enabled = viewModel.collectionDraft.isNotBlank()) { Text("Create collection") }
            viewModel.collections.forEach { collection ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(collection.name, color = Color.White, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.deleteCollection(collection.id) }) { Text("Remove") }
                }
            }
        }
        SectionCard("Transfers & privacy") {
            PrefSwitch("Open TV with Cast", viewModel.castConnect, viewModel::updateCastConnect, MaestroIds.SETTINGS_CAST_CONNECT)
            PrefSwitch("Show Remote controls", viewModel.legacyRemote, viewModel::updateLegacyRemote, MaestroIds.SETTINGS_LEGACY_REMOTE)
            PrefSwitch("Transfer on Wi-Fi only", viewModel.wifiOnlyTransfers, viewModel::setWifiOnly)
            PrefSwitch("Transfer only while charging", viewModel.transferWhileCharging, viewModel::updateChargingOnly)
            HorizontalDivider(color = PhoneOutline)
            PrefSwitch("Sync library titles", viewModel.syncLibraryToApi, viewModel::updateSyncLibrary)
            HorizontalDivider(color = PhoneOutline)
            Text("Relay over mobile data", color = Color.White)
            Text(
                "When your TV can't reach this phone on Wi‑Fi, Cablegram can stream through its relay. On mobile data this uses your data plan.",
                color = VlcMuted, style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RelayMobileDataPolicy.Ask to "Ask", RelayMobileDataPolicy.Always to "Always", RelayMobileDataPolicy.Never to "Never").forEach { (policy, label) ->
                    FilterChip(
                        selected = viewModel.relayMobileData == policy,
                        onClick = { viewModel.updateRelayMobileData(policy) },
                        label = { Text(label) },
                        modifier = Modifier.maestro("relay_mobile_${policy.name.lowercase()}"),
                    )
                }
            }
            Text("Share your catalog with your household. Your video files stay in your own storage.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("App & connection") {
            OutlinedButton(onClick = viewModel::clearLocalMetadata, modifier = Modifier.fillMaxWidth()) { Text("Clear artwork cache") }
            TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced settings" else "Advanced connection settings") }
            if (advanced) {
                Text("Change these only if you use your own server.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(apiDraft, { apiDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API server") }, isError = apiDraft.isNotBlank() && !isValidServerUrl(apiDraft))
                OutlinedTextField(tunnelDraft, { tunnelDraft = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tunnel URL (optional)") }, isError = tunnelDraft.isNotBlank() && !isValidServerUrl(tunnelDraft))
                Button(onClick = { viewModel.updateApiBaseUrl(apiDraft); viewModel.updateTunnelUrl(tunnelDraft) },
                    enabled = isValidServerUrl(apiDraft) && (tunnelDraft.isBlank() || isValidServerUrl(tunnelDraft))) { Text("Save connection") }
                Text(viewModel.phoneIps.joinToString(" · ").ifBlank { "No Wi-Fi address" }, color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedButton(
            onClick = { confirmSignOut = true },
            enabled = !viewModel.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.SETTINGS_SIGN_OUT),
        ) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(
                onClick = viewModel::openDeleteAccount,
                enabled = !viewModel.busy,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("settings_delete_account"),
            ) { Text(stringResource(R.string.delete_account_entry), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.delete_account_entry_hint), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        viewModel.status?.let { StatusNote(it) }
        TextButton(onClick = { showLegal = true }) { Text("Licence and third-party notices") }
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(DELETE_ACCOUNT_WEB_URL) }) { Text(stringResource(R.string.delete_account_web_link)) }
        Text("CABLEGRAM  ·  ${BuildConfig.VERSION_NAME}\nYour videos. Your screen. Your space.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
    }
    if (showLegal) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val legal = remember { LegalText.read(context) }
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { showLegal = false },
            title = { Text("Licence and notices") },
            text = { Text(legal, modifier = Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { showLegal = false }) { Text("Close") } },
        )
    }
    tvToRemove?.let { tv ->
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { tvToRemove = null }, title = { Text("Remove ${tv.name}?") },
            text = { Text("The TV will be signed out and lose access to your library. Your video files stay on this phone.") },
            confirmButton = { TextButton(onClick = { viewModel.unpair(tv); tvToRemove = null }) { Text("Remove TV") } },
            dismissButton = { TextButton(onClick = { tvToRemove = null }) { Text("Cancel") } },
        )
    }
    if (confirmSignOut) AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { confirmSignOut = false },
        title = { Text("Sign out?") },
        text = { Text("This phone stops sharing and controlling your TVs. Your videos stay on this phone, and your library comes back when you sign in again.") },
        confirmButton = { TextButton(onClick = { viewModel.signOut(); confirmSignOut = false }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
    )
    profileToDelete?.let { profile ->
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { profileToDelete = null },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("Their watch progress and My List are removed from every TV. This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteProfile(profile); profileToDelete = null }) { Text("Delete profile") } },
            dismissButton = { TextButton(onClick = { profileToDelete = null }) { Text("Cancel") } },
        )
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
    if (!canSaveArtworkToHousehold(item)) return
    val saved = artworkSavedToHousehold(item)
    var confirm by remember(item.id) { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (saved) "Cover saved to your household" else "Cover kept on this phone. TVs show it while this phone is on the same Wi-Fi.",
            color = VlcMuted, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f).maestro("detail_artwork_household_status"),
        )
        if (saved) TextButton(onClick = { viewModel.keepArtworkOnPhone(item) }, modifier = Modifier.maestro("detail_artwork_remove_household")) { Text("Remove") }
        else TextButton(onClick = { confirm = true }, modifier = Modifier.maestro("detail_artwork_save_household")) { Text("Save artwork to household") }
    }
    if (confirm) AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { confirm = false },
        title = { Text("Save artwork to household?") },
        text = {
            Text("This cover was made on this phone from your video or your own image. Saving uploads it to Cablegram's server " +
                "with your household library. Everyone in your household can then see it on their TVs, phones and the web remote, " +
                "even when this phone is away, and anyone given its link can open it.\n\n" +
                "It stays on the server until you tap Remove here, change the cover, or make the video private.")
        },
        confirmButton = { TextButton(onClick = { confirm = false; viewModel.saveArtworkToHousehold(item) }, modifier = Modifier.maestro("artwork_household_confirm")) { Text("Save to household") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep on phone") } },
    )
}

@Composable
private fun DetailOverlay(viewModel: PhoneViewModel, item: LibraryItem) {
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
                Spacer(Modifier.width(8.dp)); Text(if (viewModel.editingMetadata) "Cancel" else "Library")
            }
            Spacer(Modifier.weight(1f))
            if (!viewModel.editingMetadata) {
                TextButton(onClick = { viewModel.findDetailsAndArtwork(item) }, modifier = Modifier.maestro("detail_edit_artwork")) { Text("Edit details & cover") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            MediaArtwork(item, Modifier.width(130.dp).height(190.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (item.mediaType == "tv") "TV SHOW" else "FILM", color = VlcOrange, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.5.sp)
                Text(item.title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Text(listOfNotNull(item.year?.toString(), formatDuration(item.durationSeconds)?.takeIf { it.isNotBlank() }, item.resolution).joinToString(" · "), color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
                if (item.isPrivate) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = VlcMuted)
                    Text("Phone approval required", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (!viewModel.editingMetadata) HouseholdArtworkRow(viewModel, item)
        if (item.metadataConflict) Text("Details changed on another device. Your edits are kept on this phone. Review them and save again to use your version.", color = VlcOrange)
        else if (item.pendingMetadataFields.isNotEmpty()) Text("Details saved on this phone · Waiting to sync", color = VlcMuted)
        if (viewModel.editingMetadata) {
            SectionCard("Edit details") {
                Text("${if (item.sourceKind == "web") "Original link" else "Original file"}: ${item.filename}", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(title, { title = it.take(500) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") })
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Year") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mediaType != "tv", onClick = { mediaType = "movie" }, label = { Text("Movie") })
                    FilterChip(selected = mediaType == "tv", onClick = { mediaType = "tv" }, label = { Text("TV show") })
                }
                OutlinedTextField(overview, { overview = it.take(10000) }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, label = { Text("Summary") })
                Button(onClick = { viewModel.saveMetadata(draftBase, title.trim(), year.toIntOrNull(), mediaType, overview) }, enabled = title.isNotBlank() && (year.isBlank() || year.toIntOrNull() in 1..9999) && !viewModel.busy, modifier = Modifier.fillMaxWidth()) { Text("Save changes") }
            }
        } else {
            if (item.positionSeconds > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(progress = { watchFraction(item) }, modifier = Modifier.fillMaxWidth().height(4.dp), color = VlcOrange, trackColor = PhoneOutline)
                    Text(remainingLabel(item), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            Button(onClick = { viewModel.playOnTv(item) }, enabled = !sourceUnavailable && !viewModel.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).maestro(MaestroIds.DETAIL_PLAY_TV)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text(if (sourceUnavailable) "Source unavailable" else if (viewModel.paired) "Play on TV" else "Connect a TV to play")
            }
            // What the TV did with the tap ("Starting on…", "Done on…", or why not), right where it was made.
            viewModel.remoteStatus?.let { Text(it, color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
            if (sourceUnavailable) {
                Text(
                    if (item.householdOnly) "This title is in your household library, but its file isn't on this phone. Import the same file here to play it from this phone."
                    else "Reconnect the original storage or restore the file on your phone, then check again. Your title and watch progress are kept.",
                    color = VlcMuted, style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = viewModel::syncNow, enabled = viewModel.librarySyncState != LibrarySyncState.Running) { Text("Check source again") }
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = VlcMuted, style = MaterialTheme.typography.bodyLarge) }
            LaunchedEffect(item.id) { viewModel.refreshSubtitleStatus(item) }
            if (viewModel.savedSubtitle != null) TextButton(onClick = { viewModel.removeSavedSubtitle(item) }, modifier = Modifier.fillMaxWidth()) { Text("Remove saved subtitle") }
            OutlinedButton(onClick = { viewModel.subtitleFlowOpen = true }, enabled = !sourceUnavailable && viewModel.accountTokenOrNull() != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.DETAIL_FIND_SUBTITLES)) {
                Text(viewModel.savedSubtitle?.let { "Subtitles: $it · Find another" } ?: "Find Subtitles")
            }
            SectionCard("Your copy") {
                StorageMetric("Location", storageStatusLine(item))
                item.fileSizeBytes?.let { StorageMetric("File size", formatBytes(it)) }
                if (item.transferStatus == TRANSFER_FAILED && item.webTransferError == TELEGRAM_SAVE_FAILED && !sourceUnavailable) {
                    OutlinedButton(onClick = { viewModel.saveToTelegram(item) }, modifier = Modifier.fillMaxWidth()) { Text("Retry saving to Telegram") }
                } else if (item.transferStatus == TRANSFER_FAILED && !sourceUnavailable && item.sourceKind != "web") OutlinedButton(onClick = { viewModel.retrySave(item) }, modifier = Modifier.fillMaxWidth()) { Text("Retry cloud save") }
                else if (canSaveToCloud(item)) OutlinedButton(onClick = { viewModel.beginSaveToCloud(item) }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.DETAIL_SAVE_CLOUD)) { Text("Save a cloud copy") }
                if (viewModel.telegramLink?.linked == true && PhoneTelegram.configured && canSaveToTelegram(item) && item.webTransferError != TELEGRAM_SAVE_FAILED) {
                    OutlinedButton(onClick = { viewModel.saveToTelegram(item) }, modifier = Modifier.fillMaxWidth()) { Text("Save to Telegram") }
                }
                if ((item.cloudObjectPresent || item.ownCloudCopy) && !item.copied) OutlinedButton(onClick = { viewModel.downloadFromCloud(item) }, modifier = Modifier.fillMaxWidth()) { Text("Download to phone") }
                if (canRemoveLocalCopy(item)) TextButton(onClick = { viewModel.askFreeUp(item) }) { Text("Free up phone space") }
                if (canRemoveOwnCloudCopy(item)) TextButton(onClick = { viewModel.askRemoveCloudCopy(item) }) { Text("Remove cloud copy") }
            }
            if (viewModel.collections.isNotEmpty()) SectionCard("Collections") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.collections.forEach { collection ->
                        FilterChip(selected = collection.id in item.collectionIds, onClick = { viewModel.toggleCollection(item, collection.id) }, label = { Text(collection.name) })
                    }
                }
            }
            TextButton(onClick = { viewModel.askDelete(item) }) { Text("Remove title…", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun RemoveCloudCopyDialog(viewModel: PhoneViewModel, item: LibraryItem) {
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { viewModel.removeCloudCopyTarget = null },
        title = { Text("Remove the cloud copy?") },
        text = { Text(removeCopyQuestion(item.title, viewModel.saveDestinationName)) },
        confirmButton = {
            TextButton(onClick = { viewModel.removeCloudCopy(item) }) { Text("Remove copy", color = Color(0xFFFF8A80)) }
        },
        dismissButton = { TextButton(onClick = { viewModel.removeCloudCopyTarget = null }) { Text("Keep it") } },
    )
}

@Composable
private fun DeleteDialog(viewModel: PhoneViewModel, item: LibraryItem) {
    if (item.sourceKind == "telegram") {
        AlertDialog(
            modifier = Modifier.maestroRoot(),
            onDismissRequest = { viewModel.deleteTarget = null },
            title = { Text("Remove ${item.title}?") },
            text = {
                Text(
                    "This video is stored in your Telegram channel, not on this phone. Remove from library hides it " +
                        "on the phone and every TV, and you can restore it later. Delete permanently also deletes it " +
                        "from the channel in Telegram, and can't be undone.",
                )
            },
            // One column in the confirm slot: the dialog's own button row would space two slots apart and clip the third.
            confirmButton = {
                StackedDialogButtons {
                    TextButton(onClick = { viewModel.removeTelegramTitle(item, permanently = true) }) {
                        Text("Delete permanently", color = Color(0xFFFF8A80))
                    }
                    TextButton(onClick = { viewModel.removeTelegramTitle(item, permanently = false) }) { Text("Remove from library") }
                    TextButton(onClick = { viewModel.deleteTarget = null }) { Text("Cancel") }
                }
            },
        )
        return
    }
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { viewModel.deleteTarget = null },
        title = { Text("Remove ${item.title}?") },
        text = { Text("Remove from library keeps the file. Delete file permanently removes Cablegram’s copy.") },
        confirmButton = {
            StackedDialogButtons {
                TextButton(onClick = { viewModel.deleteFilePermanently(item) }) { Text("Delete file permanently") }
                TextButton(onClick = { viewModel.removeFromLibrary(item) }) { Text("Remove from library") }
                TextButton(onClick = { viewModel.deleteTarget = null }) { Text("Cancel") }
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
            Icon(if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = if (viewModel.paused) "Resume playback" else "Pause playback", tint = Color.White)
        }
    }
}

@Composable
private fun RemoteControls(viewModel: PhoneViewModel) {
    val item = viewModel.nowPlaying ?: return
    Text("${viewModel.tvName} · ${item.title}", color = VlcMuted)
    LinearProgressIndicator(progress = { watchFraction(item) }, modifier = Modifier.fillMaxWidth(), color = VlcOrange, trackColor = Color(0xFF333333))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { viewModel.skipSeconds(-CastRemoteReceiver.SEEK_SECONDS) }) { Text("-${CastRemoteReceiver.SEEK_SECONDS}") }
        IconButton(onClick = viewModel::togglePlayPause) {
            Icon(if (viewModel.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null, tint = Color.White)
        }
        TextButton(onClick = { viewModel.skipSeconds(CastRemoteReceiver.SEEK_SECONDS) }) { Text("+${CastRemoteReceiver.SEEK_SECONDS}") }
    }
    TextButton(onClick = viewModel::stopCast) { Text("Stop casting") }
}

internal fun displayName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
}

/** Spec 003 US4: this month's relay traffic against the plan, with the 80 % warning. */
@Composable
private fun RelayUsageCard(usage: RelayUsage) {
    fun gb(bytes: Long) = "%.1f".format(bytes / 1_073_741_824.0)
    val quota = usage.quotaBytes
    val resets = usage.resetsAt?.take(10)
    Text(
        when {
            quota == null -> "Unlimited relay · ${gb(usage.usedBytes)} GB used this month"
            else -> "${gb(usage.usedBytes)} of ${gb(quota)} GB used this month"
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
            usage.limitedReason == "relay_email_unverified" -> "Confirm your email (above) to use free relay."
            usage.limitedReason == "relay_free_device_limit" ->
                "Free relay isn't available here: this phone or TV already gives free relay to other Cablegram accounts."
            fraction >= 1f -> "This month's free relay traffic is used up${resets?.let { " (resets $it)" } ?: ""}. Same Wi‑Fi still works."
            fraction >= 0.8f -> "You've used ${(fraction * 100).toInt()}% of this month's free relay traffic."
            else -> "The relay is used when your TV can't reach this phone on Wi‑Fi${resets?.let { ". Resets $it" } ?: ""}."
        }
        Text(note, color = if (fraction >= 0.8f) Color(0xFFFFB74D) else VlcMuted, style = MaterialTheme.typography.bodySmall)
    }
}
