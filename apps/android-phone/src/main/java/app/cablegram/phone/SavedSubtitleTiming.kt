package app.cablegram.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A saved subtitle's original cues and the timing it plays with now (`GET /api/subtitles/:id/timing`). */
@Serializable internal data class SavedSubtitleTiming(val id: String, val language: String, val offset: Double, val scale: Double, val cues: List<SubtitleCue>)

private val timingJson = Json { ignoreUnknownKeys = true }

/** What to tell the person when a saved subtitle's timing can't be opened. */
internal fun savedTimingError(code: String?): String = when (code) {
    "source_identity_changed" -> "This video changed since the subtitle was saved. Find or attach the subtitle again."
    "subtitle_not_found" -> "This subtitle isn't saved any more."
    else -> "The subtitle timing couldn't be opened. Check your connection and try again."
}

/**
 * The timing of a saved subtitle can be adjusted at any time, not only right after choosing or attaching it: the same
 * preview, shift buttons, two-point sync and live adjustment on the TV, starting from the timing saved now.
 */
@Composable
internal fun SavedSubtitleTimingButton(viewModel: PhoneViewModel, item: LibraryItem, enabled: Boolean) {
    var open by remember(item.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("detail_adjust_subtitle_timing")) {
        Text("Adjust subtitle timing")
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SavedSubtitleTimingScreen(viewModel, item) { open = false }
    }
}

@Composable
private fun SavedSubtitleTimingScreen(viewModel: PhoneViewModel, item: LibraryItem, onClose: () -> Unit) {
    var timing by remember { mutableStateOf<SavedSubtitleTiming?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        val token = viewModel.accountTokenOrNull() ?: run { error = savedTimingError(null); return@LaunchedEffect }
        runCatching {
            val saved = timingJson.decodeFromString<SavedSubtitleList>(viewModel.subtitleRequest("playback/item/${item.id}", token))
                .subtitles.firstOrNull { it.trackId.isNotEmpty() } ?: error("subtitle_not_found")
            timingJson.decodeFromString<SavedSubtitleTiming>(viewModel.subtitleRequest("${saved.trackId}/timing", token))
        }.fold({ timing = it }, { error = savedTimingError(it.message) })
    }
    Column(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onClose) { Text("Done") }
        Text("Subtitle timing", color = Color.White, style = MaterialTheme.typography.titleLarge)
        val loaded = timing
        when {
            error != null -> Text(error!!, color = VlcMuted)
            loaded == null -> Text("Opening the subtitle…", color = VlcMuted)
            else -> AdjustContent(
                viewModel, item,
                SubtitleMatch(id = loaded.id, language = loaded.language, cues = loaded.cues, provider = "saved"),
                loaded.id, start = loaded.offset to loaded.scale,
            ) { viewModel.refreshSubtitleStatus(item); onClose() }
        }
    }
}
