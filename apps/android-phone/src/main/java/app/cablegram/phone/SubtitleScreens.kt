package app.cablegram.phone

import android.graphics.SurfaceTexture
import android.view.TextureView
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
import androidx.compose.runtime.collectAsState
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val revision = viewModel.subtitleCredentialRevision
    val engine = remember(revision) { viewModel.newSubtitleSession() }
    val scope = rememberCoroutineScope()
    val openingRevision = remember { revision }
    var stage by remember { mutableStateOf<SubtitleStage>(SubtitleStage.Searching) }
    var preferences by remember { mutableStateOf(SubtitlePreferences()) }
    var progress by remember { mutableStateOf(ui.getString(R.string.ui_looking_at_your_video_15f7c9)) }
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
        progress = if (item.sourceKind == "telegram") ui.getString(R.string.ui_opening_your_video_from_telegram_0372e4) else ui.getString(R.string.ui_looking_at_your_video_15f7c9)
        val media = runCatching { viewModel.openVideo(item) }
        if (media.isFailure) {
            val reason = media.exceptionOrNull()
            analysisNote = (reason as? VideoUnavailable)?.message ?: ui.getString(R.string.ui_your_video_couldn_t_be_opened_so_matches_can_t_be_chec_0a5097)
            null to null
        } else media.getOrThrow().use {
            activeInput = it
            // Reading a Telegram video is data the person pays for: cap the whole check and each section of it.
            it.limitAnalysis(totalBytes = 160L * 1024 * 1024, sectionBytes = 64L * 1024 * 1024)
            val tech = runCatching { LocalSubtitleAnalysis.probe(it) }.getOrNull()
            val speechSync = BuildConfig.SUBTITLE_SPEECH_SYNC
            val unsupported = if (speechSync) LocalSubtitleAnalysis.unsupportedAudio(it) else null
            if (speechSync) progress = ui.getString(R.string.ui_listening_for_dialogue_15adbe)
            var unseekable = false
            val print = if (!speechSync || unsupported != null || tech?.duration == null) null else coroutineScope {
                val task = async { runCatching { LocalSubtitleAnalysis.fingerprint(it, tech.duration, onZone = { done, total -> progress = ui.getString(R.string.ui_listening_for_dialogue_of_c5b5bb, (done + 1).toString(), (total).toString()) }, onUnseekable = { unseekable = true }) }.getOrNull() }
                listening = task
                try { task.await() } catch (e: CancellationException) { if (skipped) null else throw e } finally { listening = null; activeInput = null }
            }
            analysisNote = when {
                !speechSync -> null
                skipped -> ui.getString(R.string.ui_you_skipped_the_audio_check_so_matches_aren_t_verified_d4b86d)
                unsupported != null -> unsupportedAudioNote(unsupported, resources = ui)
                tech?.duration == null -> ui.getString(R.string.ui_this_video_s_format_couldn_t_be_read_so_matches_can_t__eac0fe)
                print == null && unseekable -> ui.getString(R.string.ui_this_video_file_can_t_jump_to_different_points_quickly_99ad99)
                print == null && it.budgetHit -> ui.getString(R.string.ui_stopped_early_to_limit_how_much_of_this_video_is_downl_32edd7)
                print == null -> ui.getString(R.string.ui_not_enough_clear_dialogue_could_be_heard_in_your_video_627620)
                else -> null
            }
            tech to print
        }
        } finally { analysing = false; ticker.cancel() }
    }.also { analysis = it }

    /** The one search of this visit: the person's preferred languages. */
    fun search() {
        val t = token ?: run { stage = SubtitleStage.Failed(errorMessage(null, resources = ui)); return }
        if (!engine.configured) { stage = SubtitleStage.Setup; return }
        cancelSearch(); requestedLanguage = null; others = null; othersNote = null; stage = SubtitleStage.Searching
        val id = UUID.randomUUID().toString(); requestId = id
        job = scope.launch {
            try {
                val (technical, fingerprint) = ensureAnalysis()
                progress = ui.getString(R.string.ui_searching_for_subtitles_5b7af2)
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
            } catch (e: Exception) { stage = SubtitleStage.Failed(errorMessage(e.message, resources = ui)) }
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
            } catch (e: Exception) { othersNote = errorMessage(e.message, resources = ui) }
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
            TextButton(onClick = close, modifier = Modifier.maestro(MaestroIds.SUBTITLES_BACK)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null); Spacer(Modifier.width(8.dp)); Text(ui.getString(R.string.ui_back_76900f)) }
        }
        if (!adjusting) {
            Text(ui.getString(R.string.ui_subtitles_0ee695), color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Text(item.title, color = VlcMuted)
        }
        if (!adjusting && stage != SubtitleStage.Setup) TextButton(onClick = { cancelSearch(); stage = SubtitleStage.Setup }) { Text(ui.getString(R.string.ui_subtitle_accounts_9602a0)) }
        when (val s = stage) {
            SubtitleStage.Setup -> SubtitleProviderSetup(viewModel, onDone = { search() })
            SubtitleStage.Searching -> {
                Row(Modifier.maestro(MaestroIds.SUBTITLES_SEARCHING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.width(28.dp).height(28.dp), color = VlcOrange); Text(progress, color = Color.White)
                }
                if (fetchedMb > 0) Text(ui.getString(R.string.ui_fetched_mb_from_telegram_so_far_827c43, "%.1f".format(fetchedMb)), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                Text(ui.getString(R.string.ui_video_and_timing_patterns_stay_on_this_phone_searches__db1095), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
                if (analysing && BuildConfig.SUBTITLE_SPEECH_SYNC) OutlinedButton(onClick = { skipped = true; activeInput?.close(); listening?.cancel() }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_SKIP)) { Text(ui.getString(R.string.ui_skip_i_ll_line_it_up_myself_ce9dae)) }
                OutlinedButton(onClick = { cancelSearch(); stage = SubtitleStage.Failed(errorMessage("cancelled", resources = ui)) }) { Text(ui.getString(R.string.ui_cancel_19766e)) }
            }
            is SubtitleStage.Failed -> {
                StatusNote(s.message)
                Button(onClick = { search() }, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_try_again_d8b839)) }
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
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
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
        val t = token ?: return onError(errorMessage(null, resources = ui)); busyId = match.id
        scope.launch {
            try {
                val prepared = if (match.cues != null) match else engine.prepare(match)
                val saved = subtitleJson.decodeFromString<SubtitleSelection>(viewModel.subtitleRequest("selected-track", t, "POST", subtitleJson.encodeToString(engine.selected(prepared))))
                onSelected(prepared, saved.id)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { busyId = null; onError(errorMessage(e.message, resources = ui)) }
        }
    }

    if (loadingOthers) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), color = VlcOrange); Text(ui.getString(R.string.ui_looking_for_subtitles_81ebff, language?.let { languageName(it, ui) } ?: ui.getString(R.string.ui_other_language)), color = Color.White)
    }
    othersNote?.let { StatusNote(it) }
    if (!loadingOthers && othersNote == null && language != null && inView.isEmpty()) StatusNote(ui.getString(R.string.ui_no_subtitles_were_found_for_this_title_aa5d5c, languageName(language, resources = ui)))
    else if (language == null || language in preferences.languages) discoveryNotice(shown, resources = ui)?.takeUnless { analysisNote != null && shown.status == "no_confident_match" }?.let { StatusNote(it) }
    analysisNote?.let { StatusNote(it) }
    if (language == null) discovery.selected?.let { StatusNote(ui.getString(R.string.ui_subtitles_were_selected_automatically_8b4712, languageName(it.language, resources = ui))) }
    val (best, others) = splitOptions(usable, more)
    if (best != null) {
        OptionCard(best, primary = true, busy = busyId == best.id, enabled = busyId == null, details = details, canCheck = BuildConfig.SUBTITLE_SPEECH_SYNC && analysisNote == null, onUse = { choose(best) })
        TextButton(onClick = { details = !details }) { Text(if (details) ui.getString(R.string.ui_hide_details_c9722a) else ui.getString(R.string.ui_details_45989d)) }
    }
    if (others.isNotEmpty()) {
        TextButton(onClick = { showOthers = !showOthers }, modifier = Modifier.maestro(MaestroIds.SUBTITLES_OTHERS)) { Text(ui.getString(R.string.ui_other_matches_e79832, (others.size).toString())) }
        if (showOthers) others.forEach { OptionCard(it, primary = false, busy = busyId == it.id, enabled = busyId == null, details = details, canCheck = BuildConfig.SUBTITLE_SPEECH_SYNC && analysisNote == null, onUse = { choose(it) }) }
    }
    SectionCard(ui.getString(R.string.ui_another_language_e901fd)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moreLanguages.forEach { code -> FilterChip(selected = language == code, enabled = !loadingOthers, onClick = { onSelectLanguage(code) }, label = { Text(languageName(code, resources = ui)) }) }
        }
        if (language != null) TextButton(onClick = { onSelectLanguage(null) }) { Text(ui.getString(R.string.ui_use_my_preferred_languages_c40b32)) }
    }
    TextButton(onClick = { settings = !settings }) { Text(if (settings) ui.getString(R.string.ui_hide_subtitle_preferences_245035) else ui.getString(R.string.ui_subtitle_preferences_e56581)) }
    if (settings) PreferencesCard(preferences, onPreferences)
}

@Serializable private data class SourceContextRequest(val identity: String)

@Composable
private fun OptionCard(match: SubtitleMatch, primary: Boolean, busy: Boolean, enabled: Boolean = true, details: Boolean, canCheck: Boolean, onUse: () -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val h = headline(match, canCheck, speechSync = BuildConfig.SUBTITLE_SPEECH_SYNC, resources = ui)
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
            if (primary) Button(onClick = onUse, enabled = enabled, modifier = modifier.maestro(MaestroIds.SUBTITLES_USE)) { Text(if (busy) ui.getString(R.string.ui_saving_23e392) else ui.getString(R.string.ui_use_subtitle_0f7916)) }
            else OutlinedButton(onClick = onUse, enabled = enabled, modifier = modifier) { Text(if (busy) ui.getString(R.string.ui_saving_23e392) else ui.getString(R.string.ui_use_subtitle_0f7916)) }
        }
    }
}

@Composable
private fun PreferencesCard(p: SubtitlePreferences, onChange: (SubtitlePreferences) -> Unit) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    SectionCard(ui.getString(R.string.ui_preferred_languages_22af38)) {
        p.languages.forEachIndexed { index, code ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.getString(R.string.ui_message_22cc02, index + 1, languageName(code, resources = ui)), color = Color.White, modifier = Modifier.weight(1f))
                TextButton(enabled = index > 0, onClick = { onChange(p.copy(languages = p.languages.toMutableList().also { it.add(index - 1, it.removeAt(index)) })) }) { Text(ui.getString(R.string.ui_up_55490a)) }
                TextButton(enabled = p.languages.size > 1, onClick = { onChange(p.copy(languages = p.languages - code)) }) { Text(ui.getString(R.string.ui_remove_c3812f)) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            moreLanguages.filter { it !in p.languages }.take(8).forEach { code -> FilterChip(selected = false, onClick = { if (p.languages.size < 8) onChange(p.copy(languages = p.languages + code)) }, label = { Text(ui.getString(R.string.ui_message_8eab74, languageName(code, resources = ui))) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("normal" to ui.getString(R.string.ui_normal_a7248e), "prefer" to ui.getString(R.string.ui_hearing_impaired_81067a), "any" to ui.getString(R.string.ui_either_b5969c)).forEach { (value, label) -> FilterChip(selected = p.sdh == value, onClick = { onChange(p.copy(sdh = value)) }, label = { Text(label) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(ui.getString(R.string.ui_include_forced_subtitles_5c1992), color = Color.White, modifier = Modifier.weight(1f)); Switch(p.includeForced, { onChange(p.copy(includeForced = it)) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text(ui.getString(R.string.ui_automatically_use_verified_matches_3642f5), color = Color.White, modifier = Modifier.weight(1f)); Switch(p.autoSelect, { onChange(p.copy(autoSelect = it)) }) }
    }
}

private data class SyncPoint(val cue: SubtitleCue, val video: Double)

@Composable
internal fun ColumnScope.AdjustContent(
    viewModel: PhoneViewModel, item: LibraryItem, match: SubtitleMatch, subtitleId: String,
    /** The shift and scale to start from: a saved subtitle reopens with the timing it plays with now. */
    start: Pair<Double, Double> = initialShift(match),
    onDone: () -> Unit,
) {
    val ui = androidx.compose.ui.platform.LocalContext.current.resources
    val scope = rememberCoroutineScope(); val context = LocalContext.current
    val token = viewModel.accountTokenOrNull()
    val cues = match.cues.orEmpty()
    var offset by remember { mutableStateOf(start.first) }
    var scale by remember { mutableStateOf(start.second) }
    var message by remember { mutableStateOf<String?>(null) }
    var pointA by remember { mutableStateOf<SyncPoint?>(null) }
    var pointB by remember { mutableStateOf<SyncPoint?>(null) }
    var twoPoint by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<SubtitleCue?>(null) }
    // The preview reads the same bytes as the analysis: the local file, or the Telegram copy in small windows.
    var input by remember { mutableStateOf<SubtitleMediaInput?>(null) }
    var opening by remember { mutableStateOf(true) }
    var openError by remember { mutableStateOf<String?>(null) }
    val preview = remember(input) { input?.let(::SubtitlePreviewPlayer) }
    val emptyPreview = remember { kotlinx.coroutines.flow.MutableStateFlow(SubtitlePreviewState()) }
    val playback by (preview?.state ?: emptyPreview).collectAsState()
    val position = playback.positionMs / 1000.0
    val durationMs = playback.durationMs
    val playing = playback.playing
    val buffering = playback.buffering
    val seeking = playback.seeking
    DisposableEffect(preview) { onDispose { preview?.close() } }
    var fetchedBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(item.id) {
        // Not cancellable: an input opened after the screen closed must still be closed, not dropped.
        val opened = withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) { runCatching { viewModel.openVideo(item) } }
        if (!isActive) { opened.getOrNull()?.close(); return@LaunchedEffect }
        opened.fold({ input = it }, { openError = (it as? VideoUnavailable)?.message })
        opening = false
    }
    var audioNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(input) { input?.let { i -> audioNote = withContext(kotlinx.coroutines.Dispatchers.IO) { LocalSubtitleAnalysis.unsupportedAudio(i) }?.let { unsupportedAudioNote(it, ui) } } }
    LaunchedEffect(input) { while (true) { fetchedBytes = input?.bytesFetched ?: 0L; delay(500) } }
    // A video that never starts (a slow Telegram read) must not leave a black box with no explanation.
    LaunchedEffect(input) { if (input?.remote == true) { delay(45_000); if (!playback.ready && playback.error == null && message == null) message = ui.getString(R.string.ui_this_video_is_taking_too_long_to_open_from_telegram_so_c2df43) } }
    fun seek(ms: Int) { preview?.seek(ms) }
    // Set while a TV is playing this title with the timing it loaded (so later shifts are sent to it as a difference).
    var tvBase by remember { mutableStateOf<Double?>(null) }
    fun adjust(delta: Double) {
        offset = clampOffset(offset + delta)
        tvBase?.let { base -> viewModel.nudgeSubtitleOnTv(Math.round((offset - base) * 1000)) }
    }
    suspend fun persist(body: String): Boolean {
        val t = token ?: return false
        return runCatching { subtitleJson.decodeFromString<SyncResult>(viewModel.subtitleRequest("$subtitleId/sync", t, "PATCH", body)) }
            .onSuccess { offset = it.offset; scale = it.scale; message = ui.getString(R.string.ui_saved_it_will_be_used_on_your_tv_96182e) }
            .onFailure { message = errorMessage(it.message, resources = ui) }.isSuccess
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
    playback.error?.let { StatusNote(it) }
    if (source != null) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(Color.Black)) {
        AndroidView(factory = { c ->
            // TextureView renders inside the dialog, rather than punching a separate surface through it.
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { preview?.attach(texture) }
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        preview?.detach(texture)
                        return false // The player releases the texture after releasing its decoder.
                    }
                }
            }
        }, update = { view ->
            val w = playback.videoWidth
            val h = playback.videoHeight
            if (w > 0 && h > 0 && view.width > 0 && view.height > 0) {
                val fit = minOf(view.width.toFloat() / w, view.height.toFloat() / h)
                view.setTransform(android.graphics.Matrix().apply {
                    setScale(w * fit / view.width, h * fit / view.height, view.width / 2f, view.height / 2f)
                })
            }
        }, modifier = Modifier.fillMaxSize())
        // Never leave a bare black box: say what is happening, and how much has arrived when it comes over the network.
        if (playback.error == null && (!playback.ready || buffering || seeking)) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.width(32.dp).height(32.dp), color = VlcOrange)
            Text(
                when {
                    !playback.ready && source.remote -> ui.getString(R.string.ui_fetching_your_video_from_telegram_c2795c)
                    !playback.ready -> ui.getString(R.string.ui_opening_your_video_3e392b)
                    seeking -> ui.getString(R.string.ui_jumping_ee72fc)
                    else -> ui.getString(R.string.ui_loading_ba3bbb)
                } + if (source.remote && fetchedBytes > 0) ui.getString(R.string.ui_mb_5e41af, "%.1f".format(fetchedBytes / 1_048_576.0)) else "",
                color = Color.White, style = MaterialTheme.typography.bodySmall,
            )
        }
        }
        if (durationMs > 0) {
            Slider(value = (position * 1000 / durationMs).toFloat().coerceIn(0f, 1f), onValueChange = { f -> seek((f * durationMs).toInt()) }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_SEEK))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { preview?.togglePlayback() }, enabled = playback.ready, modifier = Modifier.maestro(MaestroIds.SUBTITLES_PLAY)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (playing) ui.getString(R.string.ui_pause_858e4b) else ui.getString(R.string.ui_play_436e61))
            }
            OutlinedButton(onClick = { seek((position * 1000).toInt() - 10_000) }, enabled = playback.ready) { Text("−10 s") }
            OutlinedButton(onClick = { seek((position * 1000).toInt() + 10_000) }, enabled = playback.ready) { Text("+10 s") }
        }
    } else if (opening) StatusNote(ui.getString(R.string.ui_opening_your_video_3e392b))
    else StatusNote((openError ?: ui.getString(R.string.ui_this_video_can_t_be_opened_right_now_so_it_can_t_be_pr_395068)) + ui.getString(R.string.ui_you_can_still_shift_the_timing_684cd9))
    Surface(color = Color.Black, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
        Text(visibleCue(cues, position, offset, scale)?.text.orEmpty(), Modifier.padding(14.dp), color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
    Text(ui.getString(R.string.ui_video_shift_ca726e, clock(position), formatSyncOffset(offset)), color = VlcMuted)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(-1.0 to "−1 sec", -0.25 to "−250 ms", 0.25 to "+250 ms", 1.0 to "+1 sec").forEach { (d, label) -> OutlinedButton(onClick = { adjust(d) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(label) } }
    }
    Button(onClick = { save(subtitleJson.encodeToString(SyncRequest(offset = offset, scale = scale))) }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) ui.getString(R.string.ui_saving_23e392) else ui.getString(R.string.ui_save_timing_842f19)) }
    message?.let { StatusNote(it) }

    if (viewModel.paired) SectionCard(ui.getString(R.string.ui_line_it_up_on_your_tv_bedcd2)) {
        Text(
            (if (audioNote != null) ui.getString(R.string.ui_your_tv_can_play_this_with_sound_fda245) else ui.getString(R.string.ui_want_to_hear_it_8c3862)) +
                ui.getString(R.string.ui_start_it_there_then_use_the_shift_buttons_above_the_su_b40115),
            color = VlcMuted, style = MaterialTheme.typography.bodySmall,
        )
        Button(onClick = { scope.launch { if (persist(subtitleJson.encodeToString(SyncRequest(offset = offset, scale = scale)))) { tvBase = offset; viewModel.playOnTv(item) } } }, modifier = Modifier.fillMaxWidth().maestro(MaestroIds.SUBTITLES_ON_TV)) {
            Text(if (tvBase == null) ui.getString(R.string.ui_play_on_tv_and_adjust_there_43cf9e) else ui.getString(R.string.ui_restart_on_tv_32b64b))
        }
        tvBase?.let { base -> Text(ui.getString(R.string.ui_live_on_your_tv_from_what_it_loaded_tap_save_timing_wh_600c3a, formatSyncOffset(offset - base)), color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
    }
    TextButton(onClick = { twoPoint = !twoPoint }) { Text(if (twoPoint) ui.getString(R.string.ui_hide_drift_fix_cee941) else ui.getString(R.string.ui_still_drifting_fix_with_two_points_a620b2)) }
    if (twoPoint) SectionCard(ui.getString(R.string.ui_two_point_sync_ec7d52)) {
        Text(ui.getString(R.string.ui_play_to_a_line_near_the_start_tap_the_line_you_are_hea_1a15b0), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        nearbyCues(cues, position, offset, scale).forEach { cue ->
            OutlinedButton(onClick = { picked = cue }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, if (picked == cue) VlcOrange else PhoneOutline)) { Text(cue.text.take(80)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = picked != null, onClick = { pointA = SyncPoint(picked!!, position); picked = null }) { Text(if (pointA == null) ui.getString(R.string.ui_set_point_1_ba1872) else ui.getString(R.string.ui_reset_point_1_51c411)) }
            Button(enabled = picked != null && pointA != null, onClick = { pointB = SyncPoint(picked!!, position); picked = null }) { Text(if (pointB == null) ui.getString(R.string.ui_set_point_2_eb0411) else ui.getString(R.string.ui_reset_point_2_a016cf)) }
        }
        Text(listOfNotNull(pointA?.let { ui.getString(R.string.ui_point_1_at_891c8a, clock(it.video)) }, pointB?.let { ui.getString(R.string.ui_point_2_at_45bead, clock(it.video)) }).joinToString(" · "), color = VlcMuted)
        val a = pointA?.let { it.cue to it.video }; val b = pointB?.let { it.cue to it.video }
        if (a != null && b != null && !canApplyTwoPoint(a, b)) Text(ui.getString(R.string.ui_choose_points_at_least_a_minute_apart_in_order_421ffd), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        Button(enabled = canApplyTwoPoint(a, b), modifier = Modifier.fillMaxWidth(), onClick = {
            save(subtitleJson.encodeToString(SyncRequest(points = listOf(SyncPointDto(a!!.first.start, a.second), SyncPointDto(b!!.first.start, b.second)))))
        }) { Text(ui.getString(R.string.ui_apply_31e392)) }
    }
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(ui.getString(R.string.ui_done_11a676)) }
    // Room for the "playing on TV" bar that floats over the bottom of the screen.
    Spacer(Modifier.height(88.dp))
    }
}

@Serializable private data class SyncResult(val offset: Double, val scale: Double)
@Serializable private data class SyncPointDto(val subtitle: Double, val video: Double)
@Serializable private data class SyncRequest(val offset: Double? = null, val scale: Double? = null, val points: List<SyncPointDto>? = null)

private fun clock(seconds: Double): String { val s = seconds.toInt(); return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) }
