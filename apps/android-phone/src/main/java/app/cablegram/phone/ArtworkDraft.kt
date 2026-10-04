package app.cablegram.phone

/** Only explicit cover choices change artwork. Opening an editor keeps the current cover. */
sealed interface CoverChoice {
    data object Keep : CoverChoice
    data class Frame(val path: String) : CoverChoice
    data class Catalog(val path: String, val url: String) : CoverChoice
}

data class ArtworkDraft(
    val base: LibraryItem,
    val session: Long,
    val title: String = base.title,
    val year: String = base.year?.toString().orEmpty(),
    val mediaType: String = base.mediaType,
    val overview: String = base.overview.orEmpty(),
    val cover: CoverChoice = CoverChoice.Keep,
    val query: String = base.title,
    val frames: List<String> = emptyList(),
    val loadingFrames: Boolean = true,
    val searching: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val candidate: CatalogMetadata? = null,
    val catalogPath: String? = null,
    val useCatalogDetails: Boolean = false,
    val acceptedMatch: CatalogMetadata? = null,
    val season: String = base.seasonNumber?.toString().orEmpty(),
    val episode: String = base.episodeNumber?.toString().orEmpty(),
    val keepBoth: Boolean = false,
    val duplicate: Boolean = false,
    val discardRequested: Boolean = false,
) {
    val changed: Boolean get() = title.trim() != base.title || year.toIntOrNull() != base.year ||
        mediaType != base.mediaType || overview.trim().ifBlank { null } != base.overview?.trim()?.ifBlank { null } ||
        cover != CoverChoice.Keep || useCatalogDetails ||
        season.toIntOrNull() != base.seasonNumber || episode.toIntOrNull() != base.episodeNumber
    val valid: Boolean get() = title.isNotBlank() && title.trim().length <= 500 &&
        (year.isBlank() || year.toIntOrNull() in 1..9999) &&
        (mediaType != "tv" || !useCatalogDetails ||
            (season.toIntOrNull()?.let { it >= 0 } == true && episode.toIntOrNull()?.let { it > 0 } == true)) &&
        (!duplicate || keepBoth)
}

/** Merge against the current item, keeping concurrent changes to untouched fields. */
fun commitArtworkDraft(current: LibraryItem, draft: ArtworkDraft): LibraryItem {
    val normalizedBase = draft.base.copy(overview = draft.base.overview?.trim()?.ifBlank { null })
    val edited = commitManualMetadataDraft(current, normalizedBase, draft.title, draft.year.toIntOrNull(), draft.mediaType, draft.overview)
    if (!draft.useCatalogDetails) return edited
    val match = requireNotNull(draft.acceptedMatch)
    return edited.copy(
        tmdbId = match.tmdbId,
        catalogIdentityUserSelected = true,
        genres = match.genres,
        seasonNumber = if (draft.mediaType == "tv") draft.season.toIntOrNull() else null,
        episodeNumber = if (draft.mediaType == "tv") draft.episode.toIntOrNull() else null,
    )
}

/** Structured episode identity survives normalized display titles. */
fun hasDuplicateEpisode(items: List<LibraryItem>, draft: ArtworkDraft): Boolean {
    val match = draft.acceptedMatch ?: return false
    val season = draft.season.toIntOrNull() ?: return false
    val episode = draft.episode.toIntOrNull() ?: return false
    return draft.useCatalogDetails && draft.mediaType == "tv" && items.any {
        it.id != draft.base.id && it.mediaType == "tv" &&
            (if (match.tmdbId != null) it.tmdbId == match.tmdbId else it.title.equals(match.title, true)) &&
            it.seasonNumber == season && it.episodeNumber == episode
    }
}
