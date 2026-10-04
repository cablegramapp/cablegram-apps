package app.cablegram.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns a single editor session; late responses never publish into another item or query. */
class ArtworkEditorController(
    private val store: LibraryStore,
    private val catalog: () -> CatalogClient,
    private val token: () -> String?,
    private val scope: CoroutineScope,
    private val onSaved: (LibraryItem) -> Unit,
) {
    var draft by mutableStateOf<ArtworkDraft?>(null)
        private set
    private var generation = 0L
    private var lookupRevision = 0L
    private var searchJob: Job? = null
    private val sessionJobs = mutableListOf<Job>()

    fun open(item: LibraryItem) {
        close()
        val session = ++generation
        draft = ArtworkDraft(item, session)
        scope.launch {
            val frames = withContext(Dispatchers.IO) { store.extractPreviewFrames(item, sessionTag = session.toString()) }
            if (draft?.session == session) draft = draft?.copy(frames = frames, loadingFrames = false)
        }.also { sessionJobs.add(it) }
    }

    fun update(transform: (ArtworkDraft) -> ArtworkDraft) {
        val current = draft ?: return
        if (current.saving) return
        val changed = transform(current)
        draft = changed.copy(duplicate = hasDuplicateEpisode(store.list(), changed))
    }

    fun updateQuery(value: String) {
        searchJob?.cancel()
        lookupRevision++
        // Previous selected details/cover remain a valid draft; only the search result is invalidated.
        update { it.copy(query = value.take(500), searching = false, candidate = null, catalogPath = null, message = null) }
    }

    fun requestClose() {
        val current = draft ?: return
        if (current.saving) return
        if (current.changed) update { it.copy(discardRequested = true) } else close()
    }

    fun close() {
        val session = draft?.session
        val jobs = sessionJobs.toList()
        sessionJobs.clear()
        jobs.forEach { it.cancel() }
        // Wait for blocking extraction/download work before removing its temporary bytes.
        if (session != null) scope.launch(Dispatchers.IO) {
            jobs.forEach { it.join() }
            store.discardArtworkPreviews(session)
        }
        generation++
        lookupRevision++
        draft = null
    }

    fun search() {
        val current = draft ?: return
        if (current.query.isBlank() || current.saving) return
        searchJob?.cancel()
        val revision = ++lookupRevision
        val session = current.session
        fun active() = draft?.session == session && lookupRevision == revision
        update { it.copy(searching = true, message = null, candidate = null, catalogPath = null) }
        searchJob = scope.launch {
            try {
                val api = catalog()
                val resolved = api.resolveTitle(current.query.trim(), token(), strict = true)
                if (!active()) return@launch
                val match = resolved.metadata
                if (!resolved.found || match == null || match.title.isBlank() || match.title.equals("Unknown", true)) {
                    update { it.copy(searching = false, message = "No matching movie or series found. Try another title or keep your current cover.") }
                    return@launch
                }
                val preview = api.enrich(match.title, token(), null, mediaType = match.mediaType,
                    year = match.year, imdbId = match.imdbId, strict = true)
                if (!active()) return@launch
                if (preview == null || preview.matchStatus != "matched") {
                    update { it.copy(searching = false, message = "No catalog details found for that match. Try another title.") }
                    return@launch
                }
                val image = preview.posterUrl?.let { api.downloadBytes(it) }
                val path = if (image != null) withContext(Dispatchers.IO) { store.stageArtwork(session, revision, image) } else null
                if (!active()) return@launch
                update { it.copy(searching = false, candidate = preview, catalogPath = path,
                    message = if (path == null) "The cover couldn't be loaded. Search again to retry, or keep your current cover." else null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (!active()) return@launch
                val message = when {
                    error is CatalogLookupException && error.status == 401 -> "Sign in again to search for covers. Your edits are kept."
                    error is java.io.IOException -> "Couldn't connect. You can still edit details and choose a video frame."
                    else -> "Couldn't search right now. Try again. Your edits are kept."
                }
                update { it.copy(searching = false, message = message) }
            }
        }.also { sessionJobs.add(it) }
    }

    fun useDetails() {
        val current = draft ?: return
        val match = current.candidate ?: return
        val episodes = store.list().filter { it.id != current.base.id && it.mediaType == "tv" &&
            (if (match.tmdbId != null) it.tmdbId == match.tmdbId else it.title.equals(match.title, true)) }
        val last = episodes.filter { it.seasonNumber != null && it.episodeNumber != null }
            .maxWithOrNull(compareBy<LibraryItem> { it.seasonNumber }.thenBy { it.episodeNumber })
        val fromFile = Regex("(?i)S(\\d{1,2})E(\\d{1,3})").find(current.base.filename)
        update { it.copy(title = match.title, year = match.year?.toString().orEmpty(),
            mediaType = match.mediaType, overview = match.overview.orEmpty(), useCatalogDetails = true, acceptedMatch = match,
            season = fromFile?.groupValues?.get(1)?.toIntOrNull()?.toString() ?: current.base.seasonNumber?.toString() ?: last?.seasonNumber?.toString().orEmpty(),
            episode = fromFile?.groupValues?.get(2)?.toIntOrNull()?.toString() ?: current.base.episodeNumber?.toString() ?: last?.episodeNumber?.plus(1)?.toString().orEmpty(),
            message = if (match.mediaType == "tv") "Check season and episode before saving. Season 0 is for specials." else "Catalog details are in your draft. Your cover stays unchanged until you choose one.") }
    }

    fun save() {
        val current = draft ?: return
        if (!current.changed || !current.valid || current.saving || current.searching) return
        searchJob?.cancel()
        lookupRevision++
        draft = current.copy(saving = true, message = null)
        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { store.saveArtworkDraft(current) }
                if (draft?.session == current.session) {
                    close()
                    onSaved(saved)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (draft?.session == current.session) draft = current.copy(message = "Changes couldn't be saved. Try again.")
            }
        }
    }
}
