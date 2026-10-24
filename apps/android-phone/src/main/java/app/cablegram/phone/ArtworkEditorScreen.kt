package app.cablegram.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val added = viewModel.items.filter { it.id in viewModel.recentlyAddedIds }
    val visible = added.isNotEmpty() && viewModel.tab == PhoneTab.Library
    // Keep the last non-empty list so the exit animation doesn't render an empty card.
    var shown by remember { mutableStateOf(added) }
    if (added.isNotEmpty()) shown = added
    var review by remember(viewModel.recentlyAddedIds) { mutableStateOf(false) }

    LaunchedEffect(review) {
        if (review) {
            viewModel.cancelDismissAddedVideos()
        } else if (viewModel.recentlyAddedIds.isNotEmpty()) {
            viewModel.scheduleDismissAddedVideos()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).maestro("videos_added"), color = VlcPanel) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (shown.size == 1) ui.getString(R.string.ui_video_added_08c7ee) else ui.getString(R.string.ui_videos_added_cbf82e, shown.size), Modifier.weight(1f), color = Color.White)
                    TextButton(onClick = viewModel::dismissAddedVideos, modifier = Modifier.maestro("videos_added_dismiss")) { Text(ui.getString(R.string.ui_dismiss_48845b)) }
                }
                if (shown.size == 1) TextButton(onClick = { viewModel.findDetailsAndArtwork(shown.first()) }, modifier = Modifier.maestro("videos_added_edit")) { Text(ui.getString(R.string.ui_edit_details_cover_134bbc)) }
                else {
                    TextButton(onClick = { review = !review }, modifier = Modifier.maestro("videos_added_review")) { Text(if (review) ui.getString(R.string.ui_close_review_337f91) else ui.getString(R.string.ui_review_videos_70f10f)) }
                    if (review) Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { item -> TextButton(onClick = { viewModel.findDetailsAndArtwork(item) }) { Text(item.title) } }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtworkEditorScreen(controller: ArtworkEditorController) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
                TextButton(onClick = controller::requestClose, enabled = !draft.saving, modifier = Modifier.maestro("artwork_close")) { Text(ui.getString(R.string.ui_cancel_19766e)) }
                Text(ui.getString(R.string.ui_edit_video_fa924e), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Color.White)
                TextButton(onClick = controller::save, enabled = draft.changed && draft.valid && !draft.saving && !draft.searching, modifier = Modifier.maestro("artwork_save")) { Text(if (draft.saving) ui.getString(R.string.ui_saving_23e392) else ui.getString(R.string.ui_save_changes_dd0ae7)) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val coverPath = when (val choice = draft.cover) {
                    CoverChoice.Keep -> draft.base.posterPath
                    is CoverChoice.Frame -> choice.path
                    is CoverChoice.Catalog -> choice.path
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    MediaArtwork(draft.base.copy(posterPath = coverPath), Modifier.width(110.dp).height(155.dp)
                        .maestro("artwork_preview").semantics { contentDescription = ui.getString(R.string.ui_cover_preview_9cc1b7); stateDescription = when (draft.cover) { CoverChoice.Keep -> ui.getString(R.string.ui_current_cover_02edbb); is CoverChoice.Frame -> ui.getString(R.string.ui_selected_video_frame_81b28f); is CoverChoice.Catalog -> ui.getString(R.string.ui_selected_catalog_cover_d7f7f8) } })
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(draft.title.ifBlank { ui.getString(R.string.ui_your_video_6e928e) }, style = MaterialTheme.typography.titleLarge, color = Color.White)
                        Text(when (draft.cover) { CoverChoice.Keep -> ui.getString(R.string.ui_current_cover_02edbb); is CoverChoice.Frame -> ui.getString(R.string.ui_video_frame_selected_0e54db); is CoverChoice.Catalog -> ui.getString(R.string.ui_catalog_cover_selected_ff506a) }, color = VlcMuted, modifier = Modifier.maestro("artwork_cover_status"))
                        Text(ui.getString(R.string.ui_changes_are_saved_only_when_you_tap_save_changes_3d07da), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(draft.title, { value -> controller.update { it.copy(title = value.take(500)) } }, Modifier.fillMaxWidth().maestro("artwork_title"), label = { Text(ui.getString(R.string.ui_title_7e8cd2)) }, singleLine = true, enabled = !draft.saving)
                SectionCard(ui.getString(R.string.ui_cover_fa8d84)) {
                    OutlinedButton(onClick = { controller.update { it.copy(cover = CoverChoice.Keep) } }, enabled = !draft.saving, modifier = Modifier.fillMaxWidth().maestro("artwork_keep_cover")) { Text(ui.getString(R.string.ui_keep_current_cover_8963c2)) }
                    Text(ui.getString(R.string.ui_choose_a_video_frame_17c5be), color = Color.White)
                    if (draft.loadingFrames) Text(ui.getString(R.string.ui_loading_frames_af4f27), color = VlcMuted)
                    else if (draft.frames.isEmpty()) Text(ui.getString(R.string.ui_frames_aren_t_available_for_this_video_on_this_phone_y_09701f), color = VlcMuted)
                    else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        draft.frames.forEachIndexed { index, path ->
                            val selected = draft.cover == CoverChoice.Frame(path)
                            Surface(border = BorderStroke(if (selected) 3.dp else 1.dp, if (selected) VlcOrange else PhoneOutline), modifier = Modifier
                                .width(132.dp).selectable(selected, enabled = !draft.saving, role = Role.RadioButton, onClick = { controller.update { it.copy(cover = CoverChoice.Frame(path)) } })
                                .maestro("artwork_frame_$index").semantics(mergeDescendants = true) { contentDescription = ui.getString(R.string.ui_video_frame_a4a3c5, index + 1); stateDescription = if (selected) ui.getString(R.string.ui_selected_57fd7a) else ui.getString(R.string.ui_not_selected_df12ae) }) {
                                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    MediaArtwork(draft.base.copy(posterPath = path), Modifier.fillMaxWidth().height(75.dp))
                                    Text(if (selected) ui.getString(R.string.ui_frame_selected_7bd944, index + 1) else ui.getString(R.string.ui_frame_edb914, index + 1), color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }
                if (controller.lookupAvailable) draft.base.channelTmdb?.let { suggestion ->
                    SectionCard(ui.getString(R.string.ui_suggested_by_your_telegram_channel_379fe3)) {
                        val what = if (suggestion.type == "tv") {
                            ui.getString(R.string.ui_series_a8295e) + if (suggestion.season != null && suggestion.episode != null) " · S%02dE%02d".format(suggestion.season, suggestion.episode) else ""
                        } else ui.getString(R.string.ui_movie_941de4)
                        Text(ui.getString(R.string.ui_a_message_in_your_library_channel_names_a_tmdb_entry_f_47bc0f, what, suggestion.id), color = VlcMuted)
                        OutlinedButton(onClick = controller::useChannelSuggestion, enabled = !draft.searching && !draft.saving, modifier = Modifier.fillMaxWidth().maestro("artwork_use_channel_suggestion")) {
                            Text(if (draft.searching) ui.getString(R.string.ui_loading_ba3bbb) else ui.getString(R.string.ui_show_this_suggestion_3ecb8f))
                        }
                    }
                }
                if (controller.lookupAvailable) SectionCard(ui.getString(R.string.ui_find_movie_or_series_23295b)) {
                    Text(ui.getString(R.string.ui_preview_a_cover_and_details_from_the_catalog_your_curr_c33f98), color = VlcMuted)
                    OutlinedTextField(draft.query, controller::updateQuery, Modifier.fillMaxWidth().maestro("artwork_query"), label = { Text(ui.getString(R.string.ui_search_title_464919)) }, singleLine = true, enabled = !draft.saving)
                    Button(onClick = controller::search, enabled = draft.query.isNotBlank() && !draft.searching && !draft.saving, modifier = Modifier.fillMaxWidth().maestro("artwork_search")) { Text(if (draft.searching) ui.getString(R.string.ui_searching_c31723) else ui.getString(R.string.ui_search_49c266)) }
                    draft.candidate?.let { candidate ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            draft.catalogPath?.let { path -> MediaArtwork(draft.base.copy(posterPath = path), Modifier.width(80.dp).height(115.dp)) }
                            Column(Modifier.weight(1f)) {
                                Text(candidate.title, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.maestro("artwork_match_title"))
                                Text(listOfNotNull(candidate.year?.toString(), if (candidate.mediaType == "tv") ui.getString(R.string.ui_series_a8295e) else ui.getString(R.string.ui_movie_941de4)).joinToString(" · "), color = VlcMuted)
                                candidate.overview?.let { Text(it.take(220), color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                        OutlinedButton(onClick = controller::useDetails, enabled = !draft.saving && !draft.searching, modifier = Modifier.fillMaxWidth().maestro("artwork_use_details")) { Text(ui.getString(R.string.ui_use_these_details_780f71)) }
                        OutlinedButton(onClick = { controller.update { it.copy(cover = CoverChoice.Catalog(requireNotNull(draft.catalogPath), requireNotNull(candidate.posterUrl))) } }, enabled = draft.catalogPath != null && candidate.posterUrl != null && !draft.saving && !draft.searching, modifier = Modifier.fillMaxWidth().maestro("artwork_use_catalog_cover")) { Text(ui.getString(R.string.ui_use_this_cover_241378)) }
                    }
                }
                draft.message?.let { Text(it, color = VlcOrange, modifier = Modifier.maestro("artwork_message").semantics { liveRegion = LiveRegionMode.Polite }) }
                SectionCard(ui.getString(R.string.ui_details_45989d)) {
                    OutlinedTextField(draft.year, { value -> controller.update { it.copy(year = value.filter(Char::isDigit).take(4)) } }, Modifier.fillMaxWidth().maestro("artwork_year"), label = { Text(ui.getString(R.string.ui_year_optional_246c90)) }, singleLine = true, enabled = !draft.saving)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = draft.mediaType == "movie", onClick = { controller.update { it.copy(mediaType = "movie") } }, enabled = !draft.saving, label = { Text(ui.getString(R.string.ui_movie_941de4)) })
                        FilterChip(selected = draft.mediaType == "tv", onClick = { controller.update { it.copy(mediaType = "tv") } }, enabled = !draft.saving, label = { Text(ui.getString(R.string.ui_series_a8295e)) })
                    }
                    if (draft.useCatalogDetails && draft.mediaType == "tv") {
                        Text(ui.getString(R.string.ui_check_the_episode_numbers_season_0_is_for_specials_6ee601), color = VlcMuted)
                        OutlinedTextField(draft.season, { value -> controller.update { it.copy(season = value.filter(Char::isDigit).take(2), keepBoth = false) } }, Modifier.fillMaxWidth().maestro("artwork_season"), label = { Text(ui.getString(R.string.ui_season_bad2b8)) }, singleLine = true)
                        OutlinedTextField(draft.episode, { value -> controller.update { it.copy(episode = value.filter(Char::isDigit).take(3), keepBoth = false) } }, Modifier.fillMaxWidth().maestro("artwork_episode"), label = { Text(ui.getString(R.string.ui_episode_b19c45)) }, singleLine = true)
                        if (draft.duplicate) {
                            Text(ui.getString(R.string.ui_this_episode_already_exists_the_existing_copy_will_be__a1c21f), color = VlcOrange)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(draft.keepBoth, { checked -> controller.update { it.copy(keepBoth = checked) } }, modifier = Modifier.maestro("artwork_keep_both"))
                                Text(ui.getString(R.string.ui_keep_both_copies_c7bc68), color = Color.White)
                            }
                        }
                    }
                    OutlinedTextField(draft.overview, { value -> controller.update { it.copy(overview = value.take(10000)) } }, Modifier.fillMaxWidth().maestro("artwork_summary"), label = { Text(ui.getString(R.string.ui_summary_optional_c6c899)) }, minLines = 3, maxLines = 8, enabled = !draft.saving)
                    Text(ui.getString(R.string.ui_original_file_9088ed, draft.base.filename), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (draft.discardRequested) AlertDialog(
            modifier = Modifier.maestroRoot(), onDismissRequest = { controller.update { it.copy(discardRequested = false) } },
            title = { Text(ui.getString(R.string.ui_discard_changes_85bcf4)) }, text = { Text(ui.getString(R.string.ui_your_current_details_and_cover_will_be_kept_e0092d)) },
            confirmButton = { TextButton(onClick = controller::close, modifier = Modifier.maestro("artwork_discard")) { Text(ui.getString(R.string.ui_discard_eb1a70)) } },
            dismissButton = { TextButton(onClick = { controller.update { it.copy(discardRequested = false) } }) { Text(ui.getString(R.string.ui_keep_editing_e76fd2)) } },
        )
    }
}
