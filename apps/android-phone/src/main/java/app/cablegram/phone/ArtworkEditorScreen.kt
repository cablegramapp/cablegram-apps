package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import android.view.WindowManager

@Composable
fun AddedVideosCard(viewModel: PhoneViewModel) {
    val added = viewModel.items.filter { it.id in viewModel.recentlyAddedIds }
    if (added.isEmpty()) return
    var review by remember(viewModel.recentlyAddedIds) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).maestro("videos_added"), color = VlcPanel) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (added.size == 1) "Video added" else "${added.size} videos added", Modifier.weight(1f), color = Color.White)
                TextButton(onClick = viewModel::dismissAddedVideos, modifier = Modifier.maestro("videos_added_dismiss")) { Text("Dismiss") }
            }
            if (added.size == 1) TextButton(onClick = { viewModel.findDetailsAndArtwork(added.first()) }, modifier = Modifier.maestro("videos_added_edit")) { Text("Edit details & cover") }
            else {
                TextButton(onClick = { review = !review }, modifier = Modifier.maestro("videos_added_review")) { Text(if (review) "Close review" else "Review videos") }
                if (review) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    added.forEach { item -> TextButton(onClick = { viewModel.findDetailsAndArtwork(item) }) { Text(item.title) } }
                }
            }
        }
    }
}

@Composable
fun ArtworkEditorScreen(controller: ArtworkEditorController) {
    val draft = controller.draft ?: return
    Dialog(onDismissRequest = controller::requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        val window = (LocalView.current.parent as DialogWindowProvider).window
        DisposableEffect(window) {
            val previous = window.attributes.softInputMode
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            onDispose { window.setSoftInputMode(previous) }
        }
        BackHandler(onBack = controller::requestClose)
        Column(Modifier.fillMaxSize().background(VlcBlack).maestroRoot().maestro("artwork_editor").systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = controller::requestClose, enabled = !draft.saving, modifier = Modifier.maestro("artwork_close")) { Text("Cancel") }
                Text("Edit video", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Color.White)
                TextButton(onClick = controller::save, enabled = draft.changed && draft.valid && !draft.saving && !draft.searching, modifier = Modifier.maestro("artwork_save")) { Text(if (draft.saving) "Saving…" else "Save changes") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val coverPath = when (val choice = draft.cover) {
                    CoverChoice.Keep -> draft.base.posterPath
                    is CoverChoice.Frame -> choice.path
                    is CoverChoice.Catalog -> choice.path
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaArtwork(draft.base.copy(posterPath = coverPath), Modifier.width(110.dp).height(155.dp)
                        .maestro("artwork_preview").semantics { contentDescription = "Cover preview"; stateDescription = when (draft.cover) { CoverChoice.Keep -> "Current cover"; is CoverChoice.Frame -> "Selected video frame"; is CoverChoice.Catalog -> "Selected catalog cover" } })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(draft.title.ifBlank { "Your video" }, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(when (draft.cover) { CoverChoice.Keep -> "Current cover"; is CoverChoice.Frame -> "Video frame selected"; is CoverChoice.Catalog -> "Catalog cover selected" }, color = VlcMuted, modifier = Modifier.maestro("artwork_cover_status"))
                        Text("Changes are saved only when you tap Save changes.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(draft.title, { value -> controller.update { it.copy(title = value.take(500)) } }, Modifier.fillMaxWidth().maestro("artwork_title"), label = { Text("Title") }, singleLine = true, enabled = !draft.saving)
                SectionCard("Cover") {
                    OutlinedButton(onClick = { controller.update { it.copy(cover = CoverChoice.Keep) } }, enabled = !draft.saving, modifier = Modifier.fillMaxWidth().maestro("artwork_keep_cover")) { Text("Keep current cover") }
                    Text("Choose a video frame", color = Color.White)
                    if (draft.loadingFrames) Text("Loading frames…", color = VlcMuted)
                    else if (draft.frames.isEmpty()) Text("Frames aren't available for this video on this phone. You can keep the cover or search the catalog.", color = VlcMuted)
                    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        draft.frames.forEachIndexed { index, path ->
                            val selected = draft.cover == CoverChoice.Frame(path)
                            Surface(border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) VlcOrange else PhoneOutline), modifier = Modifier
                                .width(132.dp).selectable(selected, enabled = !draft.saving, role = Role.RadioButton, onClick = { controller.update { it.copy(cover = CoverChoice.Frame(path)) } })
                                .maestro("artwork_frame_$index").semantics(mergeDescendants = true) { contentDescription = "Video frame ${index + 1}"; stateDescription = if (selected) "Selected" else "Not selected" }) {
                                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    MediaArtwork(draft.base.copy(posterPath = path), Modifier.fillMaxWidth().height(75.dp))
                                    Text(if (selected) "Frame ${index + 1} · Selected" else "Frame ${index + 1}", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                SectionCard("Find movie or series") {
                    Text("Preview a cover and details from the catalog. Your current cover and details stay until you choose replacements.", color = VlcMuted)
                    OutlinedTextField(draft.query, controller::updateQuery, Modifier.fillMaxWidth().maestro("artwork_query"), label = { Text("Search title") }, singleLine = true, enabled = !draft.saving)
                    Button(onClick = controller::search, enabled = draft.query.isNotBlank() && !draft.searching && !draft.saving, modifier = Modifier.fillMaxWidth().maestro("artwork_search")) { Text(if (draft.searching) "Searching…" else "Search") }
                    draft.candidate?.let { candidate ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            draft.catalogPath?.let { path -> MediaArtwork(draft.base.copy(posterPath = path), Modifier.width(80.dp).height(115.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(candidate.title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.maestro("artwork_match_title"))
                                Text(listOfNotNull(candidate.year?.toString(), if (candidate.mediaType == "tv") "Series" else "Movie").joinToString(" · "), color = VlcMuted)
                                candidate.overview?.let { Text(it.take(220), color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        OutlinedButton(onClick = controller::useDetails, enabled = !draft.saving && !draft.searching, modifier = Modifier.fillMaxWidth().maestro("artwork_use_details")) { Text("Use these details") }
                        OutlinedButton(onClick = { controller.update { it.copy(cover = CoverChoice.Catalog(requireNotNull(draft.catalogPath), requireNotNull(candidate.posterUrl))) } }, enabled = draft.catalogPath != null && candidate.posterUrl != null && !draft.saving && !draft.searching, modifier = Modifier.fillMaxWidth().maestro("artwork_use_catalog_cover")) { Text("Use this cover") }
                    }
                }
                draft.message?.let { Text(it, color = VlcOrange, modifier = Modifier.maestro("artwork_message").semantics { liveRegion = LiveRegionMode.Polite }) }
                SectionCard("Details") {
                    OutlinedTextField(draft.year, { value -> controller.update { it.copy(year = value.filter(Char::isDigit).take(4)) } }, Modifier.fillMaxWidth().maestro("artwork_year"), label = { Text("Year (optional)") }, singleLine = true, enabled = !draft.saving)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.mediaType == "movie", onClick = { controller.update { it.copy(mediaType = "movie") } }, enabled = !draft.saving, label = { Text("Movie") })
                        FilterChip(selected = draft.mediaType == "tv", onClick = { controller.update { it.copy(mediaType = "tv") } }, enabled = !draft.saving, label = { Text("Series") })
                    }
                    if (draft.useCatalogDetails && draft.mediaType == "tv") {
                        Text("Check the episode numbers. Season 0 is for specials.", color = VlcMuted)
                        OutlinedTextField(draft.season, { value -> controller.update { it.copy(season = value.filter(Char::isDigit).take(2), keepBoth = false) } }, Modifier.fillMaxWidth().maestro("artwork_season"), label = { Text("Season") }, singleLine = true)
                        OutlinedTextField(draft.episode, { value -> controller.update { it.copy(episode = value.filter(Char::isDigit).take(3), keepBoth = false) } }, Modifier.fillMaxWidth().maestro("artwork_episode"), label = { Text("Episode") }, singleLine = true)
                        if (draft.duplicate) {
                            Text("This episode already exists. The existing copy will be kept.", color = VlcOrange)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(draft.keepBoth, { checked -> controller.update { it.copy(keepBoth = checked) } }, modifier = Modifier.maestro("artwork_keep_both"))
                                Text("Keep both copies", color = Color.White)
                            }
                        }
                    }
                    OutlinedTextField(draft.overview, { value -> controller.update { it.copy(overview = value.take(10000)) } }, Modifier.fillMaxWidth().maestro("artwork_summary"), label = { Text("Summary (optional)") }, minLines = 3, maxLines = 8, enabled = !draft.saving)
                    Text("Original file: ${draft.base.filename}", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (draft.discardRequested) AlertDialog(
            modifier = Modifier.maestroRoot(), onDismissRequest = { controller.update { it.copy(discardRequested = false) } },
            title = { Text("Discard changes?") }, text = { Text("Your current details and cover will be kept.") },
            confirmButton = { TextButton(onClick = controller::close, modifier = Modifier.maestro("artwork_discard")) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { controller.update { it.copy(discardRequested = false) } }) { Text("Keep editing") } },
        )
    }
}
