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
    var open by remember(item.id) { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("detail_attach_subtitle")) {
        Text("Attach subtitle file")
    }
    if (open) Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LocalSubtitleAttachment(viewModel, item) { open = false }
    }
}

/** No provider setup, search or video/audio analysis is needed to attach a file. */
@Composable
private fun LocalSubtitleAttachment(viewModel: PhoneViewModel, item: LibraryItem, onClose: () -> Unit) {
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
        if (token == null) { sourceError = "Sign in before attaching a subtitle."; return@LaunchedEffect }
        try {
            source = attachmentJson.decodeFromString<SubtitleSourceContext>(viewModel.subtitleRequest(
                "source-context", token, "POST", buildJsonObject { put("identity", item.id) }.toString()))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { sourceError = "The video couldn't be loaded. Check your connection, then close this screen and try again." }
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
                "local_subtitle_too_large" -> "This subtitle is too large. Choose a file smaller than 2 MB."
                "local_subtitle_format" -> "Choose a single .srt or .vtt subtitle file."
                "invalid_subtitle" -> "This file has no usable subtitle cues or has invalid timing. Choose another SRT/VTT file."
                else -> "The subtitle file couldn't be read. Choose it again or save a copy on this phone."
            }
        } finally { reading = false }
    }

    val selection = saved
    Column(Modifier.fillMaxSize().background(VlcBlack).safeDrawingPadding()
        .then(if (selection == null) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onClose) { Text(if (selection == null) "Back" else "Done") }
        if (selection != null) {
            Text("Subtitle attached", color = Color.White, style = MaterialTheme.typography.titleLarge)
            AdjustContent(viewModel, item, SubtitleMatch(id = selection.id, language = selection.language,
                cues = file?.cues, provider = "local", releaseName = filename), selection.id) {
                viewModel.refreshSubtitleStatus(item); onClose()
            }
        } else {
            Text("Attach subtitle file", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(item.title, color = VlcMuted)
            Text("Choose an SRT or VTT file, then check its language and preview before attaching it.", color = VlcMuted)
            OutlinedTextField(value = language, onValueChange = { value ->
                val normalized = value.lowercase(Locale.ROOT).filter { it in 'a'..'z' }.take(2)
                if (language != normalized) { file = null; language = normalized }
            }, enabled = !saving, singleLine = true, label = { Text("Language code") },
                supportingText = { Text("Two-letter code, for example en, fa or de. This also helps decode older files.") },
                isError = !validLanguage, modifier = Modifier.fillMaxWidth().maestro("local_subtitle_language"))
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().maestro("local_subtitle_choose")) { Text("Choose SRT/VTT file") }
            // Some document providers mislabel subtitles as text/plain or octet-stream. Validate the
            // chosen filename and content rather than hiding these files with a restrictive MIME filter.
            if (reading) { CircularProgressIndicator(); Text("Reading subtitle…", color = VlcMuted) }
            sourceError?.let { StatusNote(it) }
            fileError?.let { StatusNote(it) }
            saveError?.let { StatusNote(it) }
            file?.let { parsed ->
                Text(filename, color = Color.White)
                Text("${parsed.cues.size} cues · ${languageName(language)}", color = VlcMuted)
                SectionCard("Preview") { parsed.cues.take(3).forEach { Text(it.text, color = Color.White) } }
            }
            Text("Attaching uploads the selected subtitle text to Cablegram with this video for playback on your paired TVs. " +
                "It replaces your saved subtitle for this copy. You can remove it from video details. " +
                "Only attach subtitles you have permission to use.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
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
                        catch (e: Exception) { saveError = errorMessage(e.message) }
                        finally { saving = false }
                    }
                }) { Text(if (saving) "Attaching…" else "Attach to video") }
        }
    }
}
