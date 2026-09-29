package app.cablegram.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PrivacyPrompt(viewModel: PhoneViewModel) {
    viewModel.pendingPrivacyItem ?: return
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = { },
        title = { Text("Require phone approval to play?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The title stays visible on the TV, but your phone must approve each play — one playback at a time.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.choosePrivacy(true) }) { Text("Require approval") }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.choosePrivacy(false) }) { Text("No, play freely on the TV") }
        },
    )
}

@Composable
fun TitlePrompt(viewModel: PhoneViewModel) {
    val aiMatch = viewModel.pendingAiMatch
    when {
        aiMatch != null && viewModel.askingSeriesDetails -> SeriesDetailsDialog(viewModel, aiMatch)
        aiMatch != null -> AiMatchConfirmDialog(viewModel, aiMatch)
        else -> NameTitleDialog(viewModel)
    }
}

@Composable
private fun NameTitleDialog(viewModel: PhoneViewModel) {
    val sparkEnabled = viewModel.titleDraft.trim().isNotEmpty() && !viewModel.identifyingStills
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = viewModel::skipPendingTitle,
        title = { Text("Find details & artwork") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Search for a title, then choose a match. You can keep your current details and selected still instead.")
                if (viewModel.previewFrames.isNotEmpty()) {
                    Text(
                        "Stills from this file. The second is selected by default — tap another for the poster.",
                        fontSize = 13.sp,
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        viewModel.previewFrames.forEach { path ->
                            val bitmap = remember(path) { decodePosterBitmap(path, maxEdge = 240) }
                            if (bitmap != null) {
                                Image(
                                    bitmap.asImageBitmap(),
                                    contentDescription = "Video still",
                                    modifier = Modifier
                                        .height(72.dp)
                                        .width(128.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (path == viewModel.selectedPreviewFrame) Color(0xFFFF8800) else Color(0xFF333333),
                                        )
                                        .clickable { viewModel.choosePreviewFrame(path) }
                                        .padding(if (path == viewModel.selectedPreviewFrame) 2.dp else 0.dp),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = viewModel.titleDraft,
                        onValueChange = viewModel::updateTitleDraft,
                        modifier = Modifier.weight(1f).maestro(MaestroIds.TITLE_PROMPT_FIELD),
                        singleLine = true,
                        label = { Text("Title") },
                    )
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable(enabled = sparkEnabled, onClick = viewModel::resolveTitleFromText)
                            .maestro(MaestroIds.TITLE_PROMPT_AI),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (viewModel.identifyingStills) {
                            CircularProgressIndicator(
                                Modifier.size(26.dp),
                                strokeWidth = 2.5.dp,
                                color = Color(0xFFFF8800),
                            )
                        } else {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Look up title",
                                tint = if (sparkEnabled) Color(0xFFFF8800) else Color(0xFF666666),
                                modifier = Modifier.size(26.dp),
                            )
                        }
                    }
                }
                viewModel.status?.let { Text(it, fontSize = 13.sp, color = Color(0xFFBBBBBB)) }
                viewModel.titleSuggestions.forEach { suggestion ->
                    Text(
                        listOfNotNull(suggestion.title, suggestion.year?.toString(), suggestion.source).joinToString(" · "),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.pickTitleSuggestion(suggestion) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmPendingTitle() },
                enabled = viewModel.titleDraft.trim().isNotEmpty() && !viewModel.busy,
                modifier = Modifier.maestro(MaestroIds.TITLE_PROMPT_CONFIRM),
            ) { Text("Continue") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::skipPendingTitle) { Text("Skip") }
        },
    )
}

@Composable
private fun AiMatchConfirmDialog(viewModel: PhoneViewModel, match: CatalogMetadata) {
    val kind = if (match.mediaType.equals("tv", ignoreCase = true)) "TV series" else "Movie"
    val line = listOfNotNull(match.title, match.year?.toString(), kind).joinToString(" · ")
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = viewModel::dismissAiMatch,
        title = { Text("Is this correct?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(line)
                match.overview?.takeIf { it.isNotBlank() }?.let {
                    Text(it.take(220), fontSize = 13.sp, color = Color(0xFFBBBBBB))
                }
                if (match.genres.isNotEmpty()) {
                    Text(match.genres.take(4).joinToString(" · "), fontSize = 12.sp, color = Color(0xFF888888))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::acceptAiMatch, enabled = !viewModel.busy) {
                Text(if (match.mediaType.equals("tv", ignoreCase = true)) "Yes — next" else "Yes — use this")
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissAiMatch) { Text("No") }
        },
    )
}

@Composable
private fun SeriesDetailsDialog(viewModel: PhoneViewModel, match: CatalogMetadata) {
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = viewModel::dismissAiMatch,
        title = { Text(match.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("This looks like a series. Enter the season and episode for this file. Season 0 is for specials.")
                viewModel.seasonEpisodeSuggestLabel?.let {
                    Text(it, fontSize = 13.sp, color = Color(0xFFFF8800))
                }
                OutlinedTextField(
                    value = viewModel.seasonDraft,
                    onValueChange = { viewModel.seasonDraft = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Season") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = viewModel.episodeDraft,
                    onValueChange = { viewModel.episodeDraft = it.filter(Char::isDigit).take(3) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Episode") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                viewModel.status?.let { Text(it, fontSize = 13.sp, color = Color(0xFFBBBBBB)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::confirmSeriesDetails,
                enabled = !viewModel.busy &&
                    viewModel.seasonDraft.toIntOrNull()?.let { it >= 0 } == true &&
                    viewModel.episodeDraft.toIntOrNull()?.let { it > 0 } == true,
            ) { Text("Fetch artwork") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissAiMatch) { Text("Cancel") }
        },
    )
}

/** T-UX04: explicit Add-duplicate decision instead of a hidden double-confirm tap. */
@Composable
fun DuplicateEpisodeDialog(viewModel: PhoneViewModel) {
    val (seriesTitle, candidate) = viewModel.duplicatePrompt ?: return
    AlertDialog(
        modifier = Modifier.maestroRoot(),
        onDismissRequest = viewModel::cancelDuplicateEpisode,
        title = { Text("This episode already exists") },
        text = {
            Text(
                "“%s” already has S%02dE%02d in your library. Add this file as a second copy, or cancel to pick different numbers?"
                    .format(seriesTitle, candidate.first, candidate.second),
            )
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmDuplicateEpisode) { Text("Add duplicate copy") }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelDuplicateEpisode) { Text("Cancel") }
        },
    )
}
