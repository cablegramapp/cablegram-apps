package app.cablegram.phone

import java.util.UUID

/** Pure draft commit: no lookup, artwork change, or source rename. */
fun commitManualMetadata(item: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?): LibraryItem {
    require(title.isNotBlank() && title.trim().length <= 500)
    require(year == null || year in 1..9999)
    require(mediaType in setOf("movie", "tv"))
    require(overview.orEmpty().trim().length <= 10000)
    val summary = overview?.trim()?.takeIf { it.isNotEmpty() }
    val changed = buildSet {
        if (title.trim() != item.title) add("title")
        if (year != item.year) add("year")
        if (mediaType != item.mediaType) add("mediaType")
        if (summary != item.overview) add("overview")
    }
    if (changed.isEmpty() && !item.metadataConflict) return item
    return item.copy(
        title = title.trim(), year = year, mediaType = mediaType, overview = summary,
        userMetadataFields = item.userMetadataFields + changed,
        pendingMetadataFields = item.pendingMetadataFields + changed,
        metadataEditId = UUID.randomUUID().toString(), metadataConflict = false,
    )
}

/** An open editor owns only its changed fields, and keeps the revision it originally displayed. */
fun commitManualMetadataDraft(current: LibraryItem, base: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?): LibraryItem {
    require(current.id == base.id)
    val draft = commitManualMetadata(base, title, year, mediaType, overview)
    val changed = buildSet {
        if (draft.title != base.title) add("title")
        if (draft.year != base.year) add("year")
        if (draft.overview != base.overview) add("overview")
        if (draft.mediaType != base.mediaType) add("mediaType")
    }
    if (changed.isEmpty() && !current.metadataConflict) return current
    return current.copy(
        title = if ("title" in changed) draft.title else current.title,
        year = if ("year" in changed) draft.year else current.year,
        overview = if ("overview" in changed) draft.overview else current.overview,
        mediaType = if ("mediaType" in changed) draft.mediaType else current.mediaType,
        userMetadataFields = current.userMetadataFields + changed,
        pendingMetadataFields = current.pendingMetadataFields + changed,
        metadataRevision = base.metadataRevision,
        metadataEditId = UUID.randomUUID().toString(), metadataConflict = false,
    )
}

/** A refresh uses shared values except where this phone still has an unsent edit. */
fun mergeManualMetadata(item: LibraryItem, remote: RemoteCatalogItem): LibraryItem {
    val protected = item.pendingMetadataFields + if (remote.userMetadataFields.isEmpty()) item.userMetadataFields else emptySet()
    val owned = remote.userMetadataFields.map { if (it == "media_type") "mediaType" else it }.toSet()
    return item.copy(
        catalogItemId = remote.id,
        title = if ("title" in protected) item.title else remote.title?.takeIf { it.isNotBlank() } ?: item.title,
        year = if ("year" in protected) item.year else if ("year" in owned) remote.year else remote.year ?: item.year,
        overview = if ("overview" in protected) item.overview else if ("overview" in owned) remote.overview else remote.overview ?: item.overview,
        mediaType = if ("mediaType" in protected) item.mediaType else remote.mediaType ?: item.mediaType,
        userMetadataFields = item.userMetadataFields + owned,
        // Keep the revision the draft was based on until acknowledged or explicitly reviewed.
        metadataRevision = if (item.pendingMetadataFields.isEmpty()) remote.metadataRevision else item.metadataRevision,
    )
}

fun acknowledgeManualMetadata(item: LibraryItem, sent: LibraryItem, remote: RemoteCatalogItem): LibraryItem {
    val acknowledged = item.copy(
        metadataRevision = remote.metadataRevision,
        pendingMetadataFields = if (item.metadataEditId == sent.metadataEditId) emptySet() else item.pendingMetadataFields,
    )
    return mergeManualMetadata(acknowledged, remote)
}

sealed interface MetadataSyncResult {
    data class Saved(val item: RemoteCatalogItem) : MetadataSyncResult
    data class Conflict(val item: RemoteCatalogItem) : MetadataSyncResult
    data object Failed : MetadataSyncResult
}
