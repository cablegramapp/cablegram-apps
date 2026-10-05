package app.cablegram.phone

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val subtitleJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Language choices offered when the person wants another language than their preferences. */
private val moreLanguages = listOf("en", "fa", "de", "fr", "es", "ar", "tr", "ru", "it")

private sealed interface SubtitleStage {
    data object Searching : SubtitleStage
    data class Results(val discovery: SubtitleDiscovery) : SubtitleStage
    data class Adjust(val discovery: SubtitleDiscovery, val match: SubtitleMatch, val subtitleId: String) : SubtitleStage
    data class Failed(val message: String) : SubtitleStage
}

/** Phone-side driver for Find Subtitles: local analysis → one server call → selection → corrections. */
@Composable
internal fun SubtitleFlow(viewModel: PhoneViewModel, item: LibraryItem, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<SubtitleStage>(SubtitleStage.Searching) }
    var preferences by remember { mutableStateOf(SubtitlePreferences()) }
    var progress by remember { mutableStateOf("Looking at your video…") }
    var job by remember { mutableStateOf<Job?>(null) }
    var requestId by remember { mutableStateOf<String?>(null) }
    var requestedLanguage by remember { mutableStateOf<String?>(null) }
    val token = viewModel.accountTokenOrNull()

    fun cancelSearch() {
        job?.cancel()
        val id = requestId; val t = token
        if (id != null && t != null) scope.launch(NonCancellable) { runCatching { viewModel.subtitleRequest("discoveries/$id", t, "DELETE") } }
    }

    fun search(language: String?) {
        val t = token ?: run { stage = SubtitleStage.Failed(errorMessage(null)); return }
        cancelSearch(); requestedLanguage = language; stage = SubtitleStage.Searching
        val id = UUID.randomUUID().toString(); requestId = id
        job = scope.launch {
            try {
                progress = "Looking at your video…"
                val (technical, fingerprint) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    progress = if (item.sourceKind == "telegram") "Opening your video from Telegram…" else "Looking at your video…"
                    val media = runCatching { viewModel.openVideo(item) }.getOrNull()
                    if (media == null) null to null else media.use {
                        val tech = runCatching { LocalSubtitleAnalysis.probe(it) }.getOrNull()
                        progress = "Listening for dialogue…"
                        val print = tech?.duration?.let { d -> runCatching { LocalSubtitleAnalysis.fingerprint(it, d) }.getOrNull() }
                        tech to print
                    }
                }
                progress = "Searching for subtitles…"
                val body = subtitleJson.encodeToString(SubtitleSearchRequest(id, item.id, language, technical, fingerprint))
                val text = viewModel.subtitleRequest("discoveries", t, "POST", body)
                val discovery = subtitleJson.decodeFromString<SubtitleDiscovery>(text)
                if (discovery.selected != null) viewModel.refreshSubtitleStatus(item)
                stage = SubtitleStage.Results(discovery)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { android.util.Log.w("Subtitles", "search failed: ${e::class.simpleName} ${e.message}", e); stage = SubtitleStage.Failed(errorMessage(e.message)) }
        }
    }

    LaunchedEffect(item.id) {
        token?.let { t -> runCatching { subtitleJson.decodeFromString<SubtitlePreferences>(viewModel.subtitleRequest("preferences", t)) }.getOrNull()?.let { preferences = it } }
        search(null)
    }
    val close = { cancelSearch(); onClose() }
    BackHandler { if (stage is SubtitleStage.Adjust) (stage as SubtitleStage.Adjust).let { stage = SubtitleStage.Results(it.discovery) } else close() }

    val adjusting = stage is SubtitleStage.Adjust
    Column(Modifier.fillMaxSize().background(VlcBlack).then(if (adjusting) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(horizontal = 20.dp, vertical = if (adjusting) 8.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(if (adjusting) 8.dp else 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = close, modifier = Modifier.maestro(MaestroIds.SUBTITLES_BACK)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text("Back") }
        }
        if (!adjusting) {
            Text("Subtitles", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(item.title, color = VlcMuted)
        }
        when (val s = stage) {
            SubtitleStage.Searching -> {
                Row(Modifier.maestro(MaestroIds.SUBTITLES_SEARCHING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), color = VlcOrange); Text(progress, color = Color.White)
                }
                Text("Your video stays on your phone. Only a short timing pattern is sent for matching.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { cancelSearch(); stage = SubtitleStage.Failed(errorMessage("cancelled")) }) { Text("Cancel") }
            }
            is SubtitleStage.Failed -> {
                StatusNote(s.message)
                Button(onClick = { search(requestedLanguage) }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            is SubtitleStage.Results -> ResultsContent(viewModel, item, s.discovery, preferences, requestedLanguage,
                onSearchLanguage = ::search,
                onPreferences = { updated -> preferences = updated; token?.let { t -> scope.launch { runCatching { viewModel.subtitleRequest("preferences", t, "PUT", subtitleJson.encodeToString(updated)) } } } },
                onSelected = { match, id -> viewModel.refreshSubtitleStatus(item); stage = SubtitleStage.Adjust(s.discovery, match, id) },
                onError = { stage = SubtitleStage.Failed(it) })
            is SubtitleStage.Adjust -> AdjustContent(viewModel, item, s.match, s.subtitleId, onDone = { viewModel.refreshSubtitleStatus(item); onClose() })
        }
    }
}

@Composable
private fun ResultsContent(
    viewModel: PhoneViewModel, item: LibraryItem, discovery: SubtitleDiscovery, preferences: SubtitlePreferences, language: String?,
    onSearchLanguage: (String?) -> Unit, onPreferences: (SubtitlePreferences) -> Unit,
    onSelected: (SubtitleMatch, String) -> Unit, onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val token = viewModel.accountTokenOrNull()
    val usable = discovery.candidates.filter { it.cues != null && it.error == null && it.confidence != "Poor Match" }
    // Unprepared candidates (ranked beyond the strongest five) are fetched only when explicitly opened.
    val more = discovery.candidates.filter { it !in usable && it.error == null && it.confidence != "Poor Match" }
    var showOthers by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }

    fun choose(match: SubtitleMatch) {
        val t = token ?: return onError(errorMessage(null)); busyId = match.id
        scope.launch {
            try {
                val prepared = if (match.cues != null) match else subtitleJson.decodeFromString<PreviewResponse>(
                    viewModel.subtitleRequest("discoveries/${discovery.id}/preview", t, "POST", """{"candidateId":${subtitleJson.encodeToString(match.id)}}""")).let { match.copy(cues = it.cues, alignment = it.candidate.alignment) }
                val saved = subtitleJson.decodeFromString<SubtitleSelection>(
                    viewModel.subtitleRequest("discoveries/${discovery.id}/select", t, "POST", """{"candidateId":${subtitleJson.encodeToString(match.id)}}"""))
                onSelected(prepared, saved.id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { busyId = null; onError(errorMessage(e.message)) }
        }
    }

    discoveryNotice(discovery)?.let { StatusNote(it) }
    discovery.selected?.let { StatusNote("${languageName(it.language)} subtitles were selected automatically.") }
    val best = usable.firstOrNull()
    if (best != null) {
        OptionCard(best, primary = true, busy = busyId == best.id, details = details, onUse = { choose(best) })
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
    }
    val others = usable.drop(if (best != null) 1 else 0) + more
    if (others.isNotEmpty()) {
        TextButton(onClick = { showOthers = !showOthers }, modifier = Modifier.maestro(MaestroIds.SUBTITLES_OTHERS)) { Text("Other matches (${others.size})") }
        if (showOthers) others.forEach { OptionCard(it, primary = false, busy = busyId == it.id, details = details, onUse = { choose(it) }) }
    }
    SectionCard("Another language") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moreLanguages.forEach { code -> FilterChip(selected = language == code, onClick = { onSearchLanguage(code) }, label = { Text(languageName(code)) }) }
        }
        if (language != null) TextButton(onClick = { onSearchLanguage(null) }) { Text("Use my preferred languages") }
    }
    TextButton(onClick = { settings = !settings }) { Text(if (settings) "Hide subtitle preferences" else "Subtitle preferences") }
    if (settings) PreferencesCard(preferences, onPreferences)
}

@Serializable private data class PreviewResponse(val candidate: SubtitleMatch, val cues: List<SubtitleCue>)

@Composable
private fun OptionCard(match: SubtitleMatch, primary: Boolean, busy: Boolean, details: Boolean, onUse: () -> Unit) {
    val h = headline(match)
    Surface(color = VlcPanel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, if (h.verified) VlcOrange else PhoneOutline.copy(alpha = 0.55f))) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(h.language, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Text(h.trust, color = if (h.verified) VlcOrange else Color.White)
            Text(h.timing, color = VlcMuted, style = MaterialTheme.typography.bodyMedium)
            if (details) {
                Text(listOfNotNull(match.provider.takeIf { it.isNotBlank() }, match.releaseName.takeIf { it.isNotBlank() }).joinToString(" · "), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                if (match.explanations.isNotEmpty()) Text(match.explanations.joinToString(" · "), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            }
            val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            if (primary) Button(onClick = onUse, enabled = !busy, modifier = modifier.maestro(MaestroIds.SUBTITLES_USE)) { Text(if (busy) "Saving…" else "Use Subtitle") }
            else OutlinedButton(onClick = onUse, enabled = !busy, modifier = modifier) { Text(if (busy) "Saving…" else "Use Subtitle") }
        }
    }
}

@Composable
private fun PreferencesCard(p: SubtitlePreferences, onChange: (SubtitlePreferences) -> Unit) {
    SectionCard("Preferred languages") {
        p.languages.forEachIndexed { index, code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${languageName(code)}", color = Color.White, modifier = Modifier.weight(1f))
                TextButton(enabled = index > 0, onClick = { onChange(p.copy(languages = p.languages.toMutableList().also { it.add(index - 1, it.removeAt(index)) })) }) { Text("Up") }
                TextButton(enabled = p.languages.size > 1, onClick = { onChange(p.copy(languages = p.languages - code)) }) { Text("Remove") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moreLanguages.filter { it !in p.languages }.take(8).forEach { code -> FilterChip(selected = false, onClick = { if (p.languages.size < 8) onChange(p.copy(languages = p.languages + code)) }, label = { Text("+ ${languageName(code)}") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("normal" to "Normal", "prefer" to "Hearing impaired", "any" to "Either").forEach { (value, label) -> FilterChip(selected = p.sdh == value, onClick = { onChange(p.copy(sdh = value)) }, label = { Text(label) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Include forced subtitles", color = Color.White, modifier = Modifier.weight(1f)); Switch(p.includeForced, { onChange(p.copy(includeForced = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Automatically use verified matches", color = Color.White, modifier = Modifier.weight(1f)); Switch(p.autoSelect, { onChange(p.copy(autoSelect = it)) }) }
    }
}

private data class SyncPoint(val cue: SubtitleCue, val video: Double)

@Composable
private fun ColumnScope.AdjustContent(viewModel: PhoneViewModel, item: LibraryItem, match: SubtitleMatch, subtitleId: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val token = viewModel.accountTokenOrNull()
    val cues = match.cues.orEmpty()
    var offset by remember { mutableStateOf(match.alignment?.takeIf { it.verified }?.offset ?: 0.0) }
    var scale by remember { mutableStateOf(match.alignment?.takeIf { it.verified }?.scale ?: 1.0) }
    var position by remember { mutableStateOf(0.0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pointA by remember { mutableStateOf<SyncPoint?>(null) }
    var pointB by remember { mutableStateOf<SyncPoint?>(null) }
    var twoPoint by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<SubtitleCue?>(null) }
    // The preview reads the same bytes as the analysis: the local file, or the Telegram copy in small windows.
    var input by remember { mutableStateOf<SubtitleMediaInput?>(null) }
    var opening by remember { mutableStateOf(true) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember { mutableStateOf(0) }
    LaunchedEffect(item.id) {
        input = withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { viewModel.openVideo(item) }.getOrNull() }
        opening = false
    }
    DisposableEffect(item.id) { onDispose { runCatching { player?.release() }; runCatching { input?.close() } } }
    LaunchedEffect(player) { while (true) { player?.let { runCatching { position = it.currentPosition / 1000.0 } }; delay(150) } }
    fun seek(ms: Int) { player?.seekTo(ms.coerceIn(0, maxOf(durationMs, 1)).toLong(), MediaPlayer.SEEK_CLOSEST) }
    fun adjust(delta: Double) { offset = clampOffset(offset + delta) }
    fun save(body: String) {
        val t = token ?: return
        scope.launch {
            runCatching { subtitleJson.decodeFromString<SyncResult>(viewModel.subtitleRequest("$subtitleId/sync", t, "PATCH", body)) }
                .onSuccess { offset = it.offset; scale = it.scale; message = "Saved. It will be used on your TV." }
                .onFailure { message = errorMessage(it.message) }
        }
    }

    val source = input
    if (source != null) {
        AndroidView(factory = { c ->
            SurfaceView(c).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        val mp = MediaPlayer()
                        runCatching {
                            source.attach(mp); mp.setDisplay(h)
                            mp.setOnPreparedListener { durationMs = it.duration; it.seekTo(1L, MediaPlayer.SEEK_CLOSEST); player = it }
                            mp.setOnErrorListener { _, _, _ -> player = null; message = "This video can't be previewed right now, but you can still adjust the timing."; true }
                            mp.prepareAsync()
                        }.onFailure { message = "This video can't be previewed right now, but you can still adjust the timing." }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { runCatching { player?.release() }; player = null }
                })
            }
        }, modifier = Modifier.fillMaxWidth().height(200.dp))
        if (durationMs > 0) {
            Slider(value = (position * 1000 / durationMs).toFloat().coerceIn(0f, 1f), onValueChange = { f -> position = f * durationMs / 1000.0; seek((f * durationMs).toInt()) }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_SEEK))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { player?.let { if (it.isPlaying) it.pause() else it.start() } }) { Text("Play / pause") }
            OutlinedButton(onClick = { seek(((player?.currentPosition ?: 0) - 10_000)) }) { Text("−10 s") }
            OutlinedButton(onClick = { seek(((player?.currentPosition ?: 0) + 10_000)) }) { Text("+10 s") }
        }
    } else if (opening) StatusNote("Opening your video…")
    else StatusNote("This video can't be opened on this phone right now, so it can't be previewed here. You can still shift the timing.")
    Surface(color = Color.Black, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Text(visibleCue(cues, position, offset, scale)?.text.orEmpty(), Modifier.padding(14.dp), color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
    Text("Video ${clock(position)} · Shift ${formatSyncOffset(offset)}", color = VlcMuted)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-1.0 to "−1 sec", -0.25 to "−250 ms", 0.25 to "+250 ms", 1.0 to "+1 sec").forEach { (d, label) -> OutlinedButton(onClick = { adjust(d) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) } }
    }
    Button(onClick = { save(subtitleJson.encodeToString(SyncRequest(offset = offset, scale = scale))) }, modifier = Modifier.fillMaxWidth()) { Text("Save timing") }

    TextButton(onClick = { twoPoint = !twoPoint }) { Text(if (twoPoint) "Hide drift fix" else "Still drifting? Fix with two points") }
    if (twoPoint) SectionCard("Two-point sync") {
        Text("Play to a line near the start, tap the line you are hearing, then do the same near the end.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        nearbyCues(cues, position, offset, scale).forEach { cue ->
            OutlinedButton(onClick = { picked = cue }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, if (picked == cue) VlcOrange else PhoneOutline)) { Text(cue.text.take(80)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = picked != null, onClick = { pointA = SyncPoint(picked!!, position); picked = null }) { Text(if (pointA == null) "Set point 1" else "Reset point 1") }
            Button(enabled = picked != null && pointA != null, onClick = { pointB = SyncPoint(picked!!, position); picked = null }) { Text(if (pointB == null) "Set point 2" else "Reset point 2") }
        }
        Text(listOfNotNull(pointA?.let { "Point 1 at ${clock(it.video)}" }, pointB?.let { "Point 2 at ${clock(it.video)}" }).joinToString(" · "), color = VlcMuted)
        val a = pointA?.let { it.cue to it.video }; val b = pointB?.let { it.cue to it.video }
        if (a != null && b != null && !canApplyTwoPoint(a, b)) Text("Choose points at least a minute apart, in order.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Button(enabled = canApplyTwoPoint(a, b), modifier = Modifier.fillMaxWidth(), onClick = {
            save(subtitleJson.encodeToString(SyncRequest(points = listOf(SyncPointDto(a!!.first.start, a.second), SyncPointDto(b!!.first.start, b.second)))))
        }) { Text("Apply") }
    }
    message?.let { StatusNote(it) }
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

@Serializable private data class SyncResult(val offset: Double, val scale: Double)
@Serializable private data class SyncPointDto(val subtitle: Double, val video: Double)
@Serializable private data class SyncRequest(val offset: Double? = null, val scale: Double? = null, val points: List<SyncPointDto>? = null)

private fun clock(seconds: Double): String { val s = seconds.toInt(); return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
