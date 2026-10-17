package app.cablegram.phone

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

private val attachmentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Composable
internal fun LocalSubtitleAttachmentButton(viewModel: PhoneViewModel, item: LibraryItem, enabled: Boolean) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    var open by remember(item.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("detail_attach_subtitle")) {
        Text(ui.getString(R.string.ui_attach_subtitle_file_5a9bf5))
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LocalSubtitleAttachment(viewModel, item) { open = false }
    }
}

/** No provider setup, search or video/audio analysis is needed to attach a file. */
@Composable
private fun LocalSubtitleAttachment(viewModel: PhoneViewModel, item: LibraryItem, onClose: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val token = viewModel.accountTokenOrNull()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var fileRevision by remember { mutableStateOf(0) }
    var language by remember { mutableStateOf("en") }
    var filename by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<LocalSubtitleFile?>(null) }
    var source by remember { mutableStateOf<SubtitleSourceContext?>(null) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<SubtitleSelection?>(null) }
    val validLanguage = Regex("^[a-z]{2}$").matches(language)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        // Cancellation keeps the previous selection. URI access is only needed for this visit.
        if (picked != null) { file = null; fileError = null; saveError = null; uri = picked; fileRevision++ }
    }

    LaunchedEffect(item.id, token) {
        source = null; sourceError = null
        if (token == null) { sourceError = ui.getString(R.string.ui_sign_in_before_attaching_a_subtitle_cdbcab); return@LaunchedEffect }
        try {
            source = attachmentJson.decodeFromString<SubtitleSourceContext>(viewModel.subtitleRequest(
                "source-context", token, "POST", buildJsonObject { put("identity", item.id) }.toString()))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { sourceError = ui.getString(R.string.ui_the_video_couldn_t_be_loaded_check_your_connection_the_e810b2) }
    }

    LaunchedEffect(uri, language, fileRevision) {
        file = null; fileError = null; saveError = null
        val selectedUri = uri ?: return@LaunchedEffect
        if (!validLanguage) return@LaunchedEffect
        reading = true
        try {
            val loaded = withContext(Dispatchers.IO) {
                val name = resolver.query(selectedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                } ?: throw LocalSubtitleError("local_subtitle_format")
                val cancellation = currentCoroutineContext()
                val bytes = resolver.openInputStream(selectedUri)?.use { LocalSubtitleFile.read(it) { cancellation.ensureActive() } }
                    ?: throw LocalSubtitleError("local_subtitle_unreadable")
                name to LocalSubtitleFile.parse(name, bytes, language)
            }
            filename = loaded.first; file = loaded.second
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            fileError = when ((e as? LocalSubtitleError)?.code) {
                "local_subtitle_too_large" -> ui.getString(R.string.ui_this_subtitle_is_too_large_choose_a_file_smaller_than__3cd02a)
                "local_subtitle_format" -> ui.getString(R.string.ui_choose_a_single_srt_or_vtt_subtitle_file_641ce0)
                "invalid_subtitle" -> ui.getString(R.string.ui_this_file_has_no_usable_subtitle_cues_or_has_invalid_t_f01576)
                else -> ui.getString(R.string.ui_the_subtitle_file_couldn_t_be_read_choose_it_again_or__f88faf)
            }
        } finally { reading = false }
    }

    val selection = saved
    Column(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding()
        .then(if (selection == null) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text(if (selection == null) ui.getString(R.string.ui_back_76900f) else ui.getString(R.string.ui_done_11a676)) }
        if (selection != null) {
            Text(ui.getString(R.string.ui_subtitle_attached_d084f3), color = Color.White, style = MaterialTheme.typography.titleLarge)
            AdjustContent(viewModel, item, SubtitleMatch(id = selection.id, language = selection.language,
                cues = file?.cues, provider = "local", releaseName = filename), selection.id) {
                viewModel.refreshSubtitleStatus(item); onClose()
            }
        } else {
            Text(ui.getString(R.string.ui_attach_subtitle_file_5a9bf5), color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(item.title, color = VlcMuted)
            Text(ui.getString(R.string.ui_choose_an_srt_or_vtt_file_then_check_its_language_and__609d6c), color = VlcMuted)
            OutlinedTextField(value = language, onValueChange = { value ->
                val normalized = value.lowercase(Locale.ROOT).filter { it in 'a'..'z' }.take(2)
                if (language != normalized) { file = null; language = normalized }
            }, enabled = !saving, singleLine = true, label = { Text(ui.getString(R.string.ui_language_code_26dec0)) },
                supportingText = { Text(ui.getString(R.string.ui_two_letter_code_for_example_en_fa_or_de_this_also_help_2d9123)) },
                isError = !validLanguage, modifier = Modifier.fillMaxWidth().maestro("local_subtitle_language"))
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().maestro("local_subtitle_choose")) { Text(ui.getString(R.string.ui_choose_srt_vtt_file_6633f6)) }
            // Some document providers mislabel subtitles as text/plain or octet-stream. Validate the
            // chosen filename and content rather than hiding these files with a restrictive MIME filter.
            if (reading) { CircularProgressIndicator(); Text(ui.getString(R.string.ui_reading_subtitle_f0bc52), color = VlcMuted) }
            sourceError?.let { StatusNote(it) }
            fileError?.let { StatusNote(it) }
            saveError?.let { StatusNote(it) }
            file?.let { parsed ->
                Text(filename, color = Color.White)
                Text(ui.getString(R.string.ui_cues_752a1f, parsed.cues.size, languageName(language, resources = ui)), color = VlcMuted)
                SectionCard(ui.getString(R.string.ui_preview_324b13)) { parsed.cues.take(3).forEach { Text(it.text, color = Color.White) } }
            }
            Text(ui.getString(R.string.ui_attaching_uploads_the_selected_subtitle_text_to_cableg_49b856) +
                ui.getString(R.string.ui_it_replaces_your_saved_subtitle_for_this_copy_you_can__428008) +
                ui.getString(R.string.ui_only_attach_subtitles_you_have_permission_to_use_22c046), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            Button(enabled = file != null && source != null && validLanguage && !reading && !saving,
                modifier = Modifier.fillMaxWidth().maestro("local_subtitle_attach"), onClick = {
                    val parsed = file ?: return@Button
                    val context = source ?: return@Button
                    val account = token ?: return@Button
                    if (saving) return@Button
                    saving = true; saveError = null
                    scope.launch {
                        try {
                            if (viewModel.accountTokenOrNull() != account) throw LocalSubtitleError("sign_in_required")
                            val result = attachmentJson.decodeFromString<SubtitleSelection>(viewModel.subtitleRequest(
                                "selected-track", account, "POST", attachmentJson.encodeToString(parsed.selected(context, language))))
                            saved = result; viewModel.refreshSubtitleStatus(item)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { saveError = errorMessage(e.message, resources = ui) }
                        finally { saving = false }
                    }
                }) { Text(if (saving) ui.getString(R.string.ui_attaching_513d9a) else ui.getString(R.string.ui_attach_to_video_e7fae7)) }
        }
    }
}
