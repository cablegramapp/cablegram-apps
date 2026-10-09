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
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.Box
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    data object Setup : SubtitleStage
    data object Searching : SubtitleStage
    data class Results(val discovery: SubtitleDiscovery) : SubtitleStage
    data class Adjust(val discovery: SubtitleDiscovery, val match: SubtitleMatch, val subtitleId: String) : SubtitleStage
    data class Failed(val message: String) : SubtitleStage
}

/** Phone-side driver for Find Subtitles: local analysis → one server call → selection → corrections. */
@Composable
internal fun SubtitleFlow(viewModel: PhoneViewModel, item: LibraryItem, onClose: () -> Unit) {
    val revision = viewModel.subtitleCredentialRevision
    val engine = remember(revision) { viewModel.newSubtitleSession() }
    val scope = rememberCoroutineScope()
    val openingRevision = remember { revision }
    var stage by remember { mutableStateOf<SubtitleStage>(SubtitleStage.Searching) }
    var preferences by remember { mutableStateOf(SubtitlePreferences()) }
    var progress by remember { mutableStateOf("Looking at your video…") }
    var job by remember { mutableStateOf<Job?>(null) }
    var requestId by remember { mutableStateOf<String?>(null) }
    var requestedLanguage by remember { mutableStateOf<String?>(null) }
    // The video is read once per visit; a language change reuses what was learned from it.
    var analysis by remember { mutableStateOf<Pair<SubtitleTechnical?, SubtitleFingerprint?>?>(null) }
    var analysisNote by remember { mutableStateOf<String?>(null) }
    // Everything fetched during this visit stays here: switching language is a local filter, not a new request.
    var others by remember { mutableStateOf<SubtitleDiscovery?>(null) }
    var loadingOthers by remember { mutableStateOf(false) }
    var othersNote by remember { mutableStateOf<String?>(null) }
    // The audio check can take a minute on a long film or over Telegram, so the person can skip it at any point.
    var listening by remember { mutableStateOf<Deferred<SubtitleFingerprint?>?>(null) }
    var skipped by remember { mutableStateOf(false) }
    var activeInput by remember { mutableStateOf<SubtitleMediaInput?>(null) }
    var analysing by remember { mutableStateOf(false) }
    var fetchedMb by remember { mutableStateOf(0.0) }
    val token = viewModel.accountTokenOrNull()

    fun cancelSearch() {
        job?.cancel(); activeInput?.close()

    }

    suspend fun ensureAnalysis(): Pair<SubtitleTechnical?, SubtitleFingerprint?> = analysis ?: withContext(kotlinx.coroutines.Dispatchers.IO) {
        analysing = true
        val ticker = scope.launch { while (true) { fetchedMb = (activeInput?.bytesFetched ?: 0L) / 1_048_576.0; delay(500) } }
        try {
        progress = if (item.sourceKind == "telegram") "Opening your video from Telegram…" else "Looking at your video…"
        val media = runCatching { viewModel.openVideo(item) }
        if (media.isFailure) {
            val reason = media.exceptionOrNull()
            analysisNote = (reason as? VideoUnavailable)?.message ?: "Your video couldn't be opened, so matches can't be checked against its audio."
            null to null
        } else media.getOrThrow().use {
            activeInput = it
            // Reading a Telegram video is data the person pays for: cap the whole check and each section of it.
            it.limitAnalysis(totalBytes = 160L * 1024 * 1024, sectionBytes = 64L * 1024 * 1024)
            val tech = runCatching { LocalSubtitleAnalysis.probe(it) }.getOrNull()
            val unsupported = LocalSubtitleAnalysis.unsupportedAudio(it)
            progress = "Listening for dialogue…"
            val print = if (unsupported != null || tech?.duration == null) null else coroutineScope {
                val task = async { runCatching { LocalSubtitleAnalysis.fingerprint(it, tech.duration, onZone = { done, total -> progress = "Listening for dialogue… (${done + 1} of $total)" }) }.getOrNull() }
                listening = task
                try { task.await() } catch (e: CancellationException) { if (skipped) null else throw e } finally { listening = null; activeInput = null }
            }
            analysisNote = when {
                skipped -> "You skipped the audio check, so matches aren't verified. Use the preview to line the subtitles up by ear or by eye."
                unsupported != null -> unsupportedAudioNote(unsupported)
                tech?.duration == null -> "This video's format couldn't be read, so matches can't be checked against its audio."
                print == null && it.budgetHit -> "Stopped early to limit how much of this video is downloaded from Telegram, so matches aren't verified. Skip the check next time, or use the preview to line them up."
                print == null -> "Not enough clear dialogue could be heard in your video, so matches can't be checked against its audio."
                else -> null
            }
            tech to print
        }
        } finally { analysing = false; ticker.cancel() }
    }.also { analysis = it }

    /** The one search of this visit: the person's preferred languages. */
    fun search() {
        val t = token ?: run { stage = SubtitleStage.Failed(errorMessage(null)); return }
        if (!engine.configured) { stage = SubtitleStage.Setup; return }
        cancelSearch(); requestedLanguage = null; others = null; othersNote = null; stage = SubtitleStage.Searching
        val id = UUID.randomUUID().toString(); requestId = id
        job = scope.launch {
            try {
                val (technical, fingerprint) = ensureAnalysis()
                progress = "Searching for subtitles…"
                val context = subtitleJson.decodeFromString<SubtitleSourceContext>(viewModel.subtitleRequest("source-context", t, "POST", subtitleJson.encodeToString(SourceContextRequest(item.id))))
                var discovery = engine.discover(context, preferences, technical, fingerprint)
                val best = discovery.candidates.firstOrNull { it.cues != null && it.error == null }
                if (preferences.autoSelect && best?.alignment?.verified == true && best.score >= 90 && !best.forced && best.alignment.correlation >= .8 && best.alignment.samples >= 4) {
                    val saved = subtitleJson.decodeFromString<SubtitleSelection>(viewModel.subtitleRequest("selected-track", t, "POST", subtitleJson.encodeToString(engine.selected(best, true))))
                    discovery = discovery.copy(selected = saved)
                }
                if (discovery.selected != null) viewModel.refreshSubtitleStatus(item)
                stage = SubtitleStage.Results(discovery)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { stage = SubtitleStage.Failed(errorMessage(e.message)) }
        }
    }

    /** A preferred language is already in the first results. The first other language fetches every other one at once. */
    fun selectLanguage(code: String?) {
        requestedLanguage = code; othersNote = null
        if (code == null || code in preferences.languages || others != null || loadingOthers) return
        val t = token ?: return
        loadingOthers = true
        scope.launch {
            try {
                val (technical, fingerprint) = ensureAnalysis()
                val languages = listOf(code) + moreLanguages.filter { it != code && it !in preferences.languages }
                val context = subtitleJson.decodeFromString<SubtitleSourceContext>(viewModel.subtitleRequest("source-context", t, "POST", subtitleJson.encodeToString(SourceContextRequest(item.id))))
                others = engine.discover(context, preferences.copy(languages = languages, autoSelect = false), technical, fingerprint)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { othersNote = errorMessage(e.message) }
            finally { loadingOthers = false }
        }
    }

    DisposableEffect(engine) { onDispose { job?.cancel(); activeInput?.close(); engine.close() } }
    LaunchedEffect(item.id, revision) {
        job?.cancel(); activeInput?.close(); analysis = null; others = null; requestId = null
        token?.let { t -> runCatching { subtitleJson.decodeFromString<SubtitlePreferences>(viewModel.subtitleRequest("preferences", t)) }.getOrNull()?.let { preferences = it } }
        if (revision == openingRevision) search() else stage = SubtitleStage.Setup
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
        if (!adjusting && stage != SubtitleStage.Setup) TextButton(onClick = { cancelSearch(); stage = SubtitleStage.Setup }) { Text("Subtitle provider accounts") }
        when (val s = stage) {
            SubtitleStage.Setup -> SubtitleProviderSetup(viewModel, onDone = { search() })
            SubtitleStage.Searching -> {
                Row(Modifier.maestro(MaestroIds.SUBTITLES_SEARCHING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), color = VlcOrange); Text(progress, color = Color.White)
                }
                if (fetchedMb > 0) Text("Fetched ${"%.1f".format(fetchedMb)} MB from Telegram so far", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                Text("Video and timing patterns stay on this phone. Searches go directly to your providers using your personal quota.", color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                if (analysing) OutlinedButton(onClick = { skipped = true; activeInput?.close(); listening?.cancel() }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_SKIP)) { Text("Skip — I'll line it up myself") }
                OutlinedButton(onClick = { cancelSearch(); stage = SubtitleStage.Failed(errorMessage("cancelled")) }) { Text("Cancel") }
            }
            is SubtitleStage.Failed -> {
                StatusNote(s.message)
                Button(onClick = { search() }, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            is SubtitleStage.Results -> ResultsContent(viewModel, engine, item, s.discovery, others, loadingOthers, othersNote, analysisNote, preferences, requestedLanguage,
                onSelectLanguage = ::selectLanguage,
                onPreferences = { updated -> preferences = updated; token?.let { t -> scope.launch { runCatching { viewModel.subtitleRequest("preferences", t, "PUT", subtitleJson.encodeToString(updated)) } } } },
                onSelected = { match, id -> viewModel.refreshSubtitleStatus(item); stage = SubtitleStage.Adjust(s.discovery, match, id) },
                onError = { stage = SubtitleStage.Failed(it) })
            is SubtitleStage.Adjust -> AdjustContent(viewModel, item, s.match, s.subtitleId, onDone = { viewModel.refreshSubtitleStatus(item); onClose() })
        }
    }
}

@Composable
private fun ResultsContent(
    viewModel: PhoneViewModel, engine: LocalSubtitleDiscovery, item: LibraryItem, discovery: SubtitleDiscovery, otherLanguages: SubtitleDiscovery?, loadingOthers: Boolean, othersNote: String?,
    analysisNote: String?, preferences: SubtitlePreferences, language: String?,
    onSelectLanguage: (String?) -> Unit, onPreferences: (SubtitlePreferences) -> Unit,
    onSelected: (SubtitleMatch, String) -> Unit, onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val token = viewModel.accountTokenOrNull()
    // Both searches of this visit, each candidate remembering which one it came from (needed to preview or select it).
    val pool = (discovery.candidates.map { it to discovery.id } + otherLanguages?.candidates.orEmpty().map { it to otherLanguages!!.id }).distinctBy { it.first.id }
    val origin = pool.associate { it.first.id to it.second }
    val inView = pool.map { it.first }.filter { if (language == null) it.language in preferences.languages else it.language == language }
    val usable = inView.filter { it.cues != null && it.error == null && it.confidence != "Poor Match" }
    // Candidates not checked up front are checked against the video when the person opens them.
    val more = inView.filter { it !in usable && it.error == null && it.confidence != "Poor Match" }
    val shown = if (language != null && language !in preferences.languages && otherLanguages != null) otherLanguages else discovery
    var showOthers by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var settings by remember { mutableStateOf(false) }

    fun choose(match: SubtitleMatch) {
        // One subtitle at a time: a second tap while one is saving would race it to the Adjust step.
        if (busyId != null) return
        val t = token ?: return onError(errorMessage(null)); busyId = match.id
        scope.launch {
            try {
                val prepared = if (match.cues != null) match else engine.prepare(match)
                val saved = subtitleJson.decodeFromString<SubtitleSelection>(viewModel.subtitleRequest("selected-track", t, "POST", subtitleJson.encodeToString(engine.selected(prepared))))
                onSelected(prepared, saved.id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { busyId = null; onError(errorMessage(e.message)) }
        }
    }

    if (loadingOthers) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = VlcOrange); Text("Looking for ${language?.let(::languageName) ?: "other"} subtitles…", color = Color.White)
    }
    othersNote?.let { StatusNote(it) }
    if (!loadingOthers && othersNote == null && language != null && inView.isEmpty()) StatusNote("No ${languageName(language)} subtitles were found for this title.")
    else if (language == null || language in preferences.languages) discoveryNotice(shown)?.takeUnless { analysisNote != null && shown.status == "no_confident_match" }?.let { StatusNote(it) }
    analysisNote?.let { StatusNote(it) }
    if (language == null) discovery.selected?.let { StatusNote("${languageName(it.language)} subtitles were selected automatically.") }
    val (best, others) = splitOptions(usable, more)
    if (best != null) {
        OptionCard(best, primary = true, busy = busyId == best.id, enabled = busyId == null, details = details, canCheck = analysisNote == null, onUse = { choose(best) })
        TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Details") }
    }
    if (others.isNotEmpty()) {
        TextButton(onClick = { showOthers = !showOthers }, modifier = Modifier.maestro(MaestroIds.SUBTITLES_OTHERS)) { Text("Other matches (${others.size})") }
        if (showOthers) others.forEach { OptionCard(it, primary = false, busy = busyId == it.id, enabled = busyId == null, details = details, canCheck = analysisNote == null, onUse = { choose(it) }) }
    }
    SectionCard("Another language") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moreLanguages.forEach { code -> FilterChip(selected = language == code, enabled = !loadingOthers, onClick = { onSelectLanguage(code) }, label = { Text(languageName(code)) }) }
        }
        if (language != null) TextButton(onClick = { onSelectLanguage(null) }) { Text("Use my preferred languages") }
    }
    TextButton(onClick = { settings = !settings }) { Text(if (settings) "Hide subtitle preferences" else "Subtitle preferences") }
    if (settings) PreferencesCard(preferences, onPreferences)
}

@Serializable private data class SourceContextRequest(val identity: String)

@Composable
private fun OptionCard(match: SubtitleMatch, primary: Boolean, busy: Boolean, enabled: Boolean = true, details: Boolean, canCheck: Boolean, onUse: () -> Unit) {
    val h = headline(match, canCheck)
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
            if (primary) Button(onClick = onUse, enabled = enabled, modifier = modifier.maestro(MaestroIds.SUBTITLES_USE)) { Text(if (busy) "Saving…" else "Use Subtitle") }
            else OutlinedButton(onClick = onUse, enabled = enabled, modifier = modifier) { Text(if (busy) "Saving…" else "Use Subtitle") }
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
internal fun ColumnScope.AdjustContent(viewModel: PhoneViewModel, item: LibraryItem, match: SubtitleMatch, subtitleId: String, onDone: () -> Unit) {
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val token = viewModel.accountTokenOrNull()
    val cues = match.cues.orEmpty()
    var offset by remember { mutableStateOf(initialShift(match).first) }
    var scale by remember { mutableStateOf(initialShift(match).second) }
    var position by remember { mutableStateOf(0.0) }
    var message by remember { mutableStateOf<String?>(null) }
    var pointA by remember { mutableStateOf<SyncPoint?>(null) }
    var pointB by remember { mutableStateOf<SyncPoint?>(null) }
    var twoPoint by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<SubtitleCue?>(null) }
    // The preview reads the same bytes as the analysis: the local file, or the Telegram copy in small windows.
    var input by remember { mutableStateOf<SubtitleMediaInput?>(null) }
    var opening by remember { mutableStateOf(true) }
    var openError by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var durationMs by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var fetchedBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(item.id) {
        // Not cancellable: an input opened after the screen closed must still be closed, not dropped.
        val opened = withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { runCatching { viewModel.openVideo(item) } }
        if (!isActive) { opened.getOrNull()?.close(); return@LaunchedEffect }
        opened.fold({ input = it }, { openError = (it as? VideoUnavailable)?.message })
        opening = false
    }
    // MediaPlayer calls can wait on the data source (a slow Telegram read), so none of them may run on the UI thread.
    val control = remember { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "subtitle-preview").apply { isDaemon = true } } }
    // Once the screen is gone the executor is shut down; a late surface callback still has to release its player.
    fun offMain(task: () -> Unit) { runCatching { control.execute(task) }.onFailure { Thread(task, "subtitle-preview-release").start() } }
    fun withPlayer(block: (MediaPlayer) -> Unit) { player?.let { p -> offMain { runCatching { block(p) } } } }
    // The player of the current surface from creation on, prepared or not, so every path can release it.
    val created = remember { java.util.concurrent.atomic.AtomicReference<MediaPlayer?>(null) }
    fun releasePlayer() { val p = created.getAndSet(null); player = null; if (p != null) offMain { runCatching { p.release() } } }
    DisposableEffect(item.id) { onDispose { releasePlayer(); val i = input; offMain { runCatching { i?.close() }; control.shutdown() } } }
    var audioNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(input) { input?.let { i -> audioNote = withContext(kotlinx.coroutines.Dispatchers.IO) { LocalSubtitleAnalysis.unsupportedAudio(i) }?.let(::unsupportedAudioNote) } }
    LaunchedEffect(player) { while (true) { val p = player; if (p != null) withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { p.currentPosition to p.isPlaying } }.getOrNull()?.let { position = it.first / 1000.0; playing = it.second }; delay(150) } }
    LaunchedEffect(input) { while (true) { fetchedBytes = input?.bytesFetched ?: 0L; delay(500) } }
    // A video that never starts (a slow Telegram read) must not leave a black box with no explanation.
    LaunchedEffect(input) { if (input?.remote == true) { delay(45_000); if (player == null && message == null) message = "This video is taking too long to open from Telegram, so it can't be previewed right now. You can still shift the timing." } }
    fun seek(ms: Int) { seeking = true; withPlayer { it.seekTo(ms.coerceIn(0, maxOf(durationMs, 1)).toLong(), MediaPlayer.SEEK_CLOSEST) } }
    // Set while a TV is playing this title with the timing it loaded (so later shifts are sent to it as a difference).
    var tvBase by remember { mutableStateOf<Double?>(null) }
    fun adjust(delta: Double) {
        offset = clampOffset(offset + delta)
        tvBase?.let { base -> viewModel.nudgeSubtitleOnTv(Math.round((offset - base) * 1000)) }
    }
    suspend fun persist(body: String): Boolean {
        val t = token ?: return false
        return runCatching { subtitleJson.decodeFromString<SyncResult>(viewModel.subtitleRequest("$subtitleId/sync", t, "PATCH", body)) }
            .onSuccess { offset = it.offset; scale = it.scale; message = "Saved. It will be used on your TV." }
            .onFailure { message = errorMessage(it.message) }.isSuccess
    }
    // The save takes a round trip: say so, and don't let a second tap send it again.
    var saving by remember { mutableStateOf(false) }
    fun save(body: String) {
        if (saving) return
        saving = true; message = null
        scope.launch { try { persist(body) } finally { saving = false } }
    }

    val source = input
    audioNote?.let { StatusNote(it) }
    if (source != null) {
        Box(Modifier.fillMaxWidth().height(200.dp)) {
        AndroidView(factory = { c ->
            SurfaceView(c).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        val mp = MediaPlayer()
                        created.getAndSet(mp)?.let { old -> offMain { runCatching { old.release() } } }
                        runCatching {
                            source.attach(mp); mp.setDisplay(h)
                            mp.setOnPreparedListener {
                                durationMs = it.duration; it.seekTo(1L, MediaPlayer.SEEK_CLOSEST)
                                player = it
                                // Reads the file header, so never on the UI thread.
                                offMain { runCatching { LocalSubtitleAnalysis.selectPlayableAudio(it, source) } }
                            }
                            mp.setOnSeekCompleteListener { seeking = false }
                            mp.setOnInfoListener { _, what, _ ->
                                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) buffering = true
                                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) buffering = false
                                false
                            }
                            mp.setOnErrorListener { _, _, _ -> releasePlayer(); message = "This video can't be previewed right now, but you can still adjust the timing."; true }
                            mp.prepareAsync()
                        }.onFailure { releasePlayer(); message = "This video can't be previewed right now, but you can still adjust the timing." }
                    }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) = releasePlayer()
                })
            }
        }, modifier = Modifier.fillMaxSize())
        // Never leave a bare black box: say what is happening, and how much has arrived when it comes over the network.
        if (player == null || buffering || seeking) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.width(32.dp).height(32.dp), color = VlcOrange)
            Text(
                when {
                    player == null && source.remote -> "Fetching your video from Telegram…"
                    player == null -> "Opening your video…"
                    seeking -> "Jumping…"
                    else -> "Loading…"
                } + if (source.remote && fetchedBytes > 0) " · ${"%.1f".format(fetchedBytes / 1_048_576.0)} MB" else "",
                color = Color.White, style = MaterialTheme.typography.bodySmall,
            )
        }
        }
        if (durationMs > 0) {
            Slider(value = (position * 1000 / durationMs).toFloat().coerceIn(0f, 1f), onValueChange = { f -> position = f * durationMs / 1000.0; seek((f * durationMs).toInt()) }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_SEEK))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { playing = !playing; withPlayer { if (it.isPlaying) it.pause() else it.start() } }, modifier = Modifier.maestro(MaestroIds.SUBTITLES_PLAY)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (playing) "Pause" else "Play")
            }
            OutlinedButton(onClick = { seek((position * 1000).toInt() - 10_000) }) { Text("−10 s") }
            OutlinedButton(onClick = { seek((position * 1000).toInt() + 10_000) }) { Text("+10 s") }
        }
    } else if (opening) StatusNote("Opening your video…")
    else StatusNote((openError ?: "This video can't be opened right now, so it can't be previewed.") + " You can still shift the timing.")
    Surface(color = Color.Black, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Text(visibleCue(cues, position, offset, scale)?.text.orEmpty(), Modifier.padding(14.dp), color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
    Text("Video ${clock(position)} · Shift ${formatSyncOffset(offset)}", color = VlcMuted)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-1.0 to "−1 sec", -0.25 to "−250 ms", 0.25 to "+250 ms", 1.0 to "+1 sec").forEach { (d, label) -> OutlinedButton(onClick = { adjust(d) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) } }
    }
    Button(onClick = { save(subtitleJson.encodeToString(SyncRequest(offset = offset, scale = scale))) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "Saving…" else "Save timing") }
    message?.let { StatusNote(it) }

    if (viewModel.paired) SectionCard("Line it up on your TV") {
        Text(
            (if (audioNote != null) "Your TV can play this with sound. " else "Want to hear it? ") +
                "Start it there, then use the shift buttons above: the subtitle moves on the TV straight away.",
            color = VlcMuted, style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { scope.launch { if (persist(subtitleJson.encodeToString(SyncRequest(offset = offset, scale = scale)))) { tvBase = offset; viewModel.playOnTv(item) } } }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_ON_TV)) {
            Text(if (tvBase == null) "Play on TV and adjust there" else "Restart on TV")
        }
        tvBase?.let { base -> Text("Live on your TV · ${formatSyncOffset(offset - base)} from what it loaded. Tap Save timing when it matches.", color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
    }
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
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    // Room for the "playing on TV" bar that floats over the bottom of the screen.
    Spacer(Modifier.height(88.dp))
    }
}

@Serializable private data class SyncResult(val offset: Double, val scale: Double)
@Serializable private data class SyncPointDto(val subtitle: Double, val video: Double)
@Serializable private data class SyncRequest(val offset: Double? = null, val scale: Double? = null, val points: List<SyncPointDto>? = null)

private fun clock(seconds: Double): String { val s = seconds.toInt(); return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
