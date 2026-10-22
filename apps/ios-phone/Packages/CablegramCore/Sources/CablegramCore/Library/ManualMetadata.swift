import Foundation

public struct MetadataValidationError: Error, Equatable {
    public let reason: String
    public init(reason: String) { self.reason = reason }
}

private func require(_ condition: Bool, _ reason: String) throws {
    if !condition { throw MetadataValidationError(reason: reason) }
}

private func nonEmpty(_ text: String?) -> String? {
    let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed?.isEmpty == false ? trimmed : nil
}

/// Pure draft commit: no lookup, artwork change, or source rename.
public func commitManualMetadata(_ item: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?) throws -> LibraryItem {
    let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
    try require(!trimmedTitle.isEmpty && trimmedTitle.count <= 500, "title")
    try require(year == nil || (1...9999).contains(year!), "year")
    try require(["movie", "tv"].contains(mediaType), "mediaType")
    try require((overview ?? "").trimmingCharacters(in: .whitespacesAndNewlines).count <= 10_000, "overview")
    let summary = nonEmpty(overview)
    var changed = Set<String>()
    if trimmedTitle != item.title { changed.insert("title") }
    if year != item.year { changed.insert("year") }
    if mediaType != item.mediaType { changed.insert("mediaType") }
    if summary != item.overview { changed.insert("overview") }
    if changed.isEmpty && !item.metadataConflict { return item }
    var result = item
    result.title = trimmedTitle
    result.year = year
    result.mediaType = mediaType
    result.overview = summary
    result.userMetadataFields.formUnion(changed)
    result.pendingMetadataFields.formUnion(changed)
    result.metadataEditId = UUID().uuidString.lowercased()
    result.metadataConflict = false
    return result
}

/// An open editor owns only its changed fields, and keeps the revision it originally displayed.
public func commitManualMetadataDraft(current: LibraryItem, base: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?) throws -> LibraryItem {
    try require(current.id == base.id, "id")
    let draft = try commitManualMetadata(base, title: title, year: year, mediaType: mediaType, overview: overview)
    var changed = Set<String>()
    if draft.title != base.title { changed.insert("title") }
    if draft.year != base.year { changed.insert("year") }
    if draft.overview != base.overview { changed.insert("overview") }
    if draft.mediaType != base.mediaType { changed.insert("mediaType") }
    if changed.isEmpty && !current.metadataConflict { return current }
    var result = current
    if changed.contains("title") { result.title = draft.title }
    if changed.contains("year") { result.year = draft.year }
    if changed.contains("overview") { result.overview = draft.overview }
    if changed.contains("mediaType") { result.mediaType = draft.mediaType }
    result.userMetadataFields.formUnion(changed)
    result.pendingMetadataFields.formUnion(changed)
    result.metadataRevision = base.metadataRevision
    result.metadataEditId = UUID().uuidString.lowercased()
    result.metadataConflict = false
    return result
}

/// A refresh uses shared values except where this phone still has an unsent edit.
public func mergeManualMetadata(_ item: LibraryItem, remote: RemoteCatalogItem) -> LibraryItem {
    let protected = item.pendingMetadataFields.union(remote.userMetadataFields.isEmpty ? item.userMetadataFields : [])
    let owned = Set(remote.userMetadataFields.map { $0 == "media_type" ? "mediaType" : $0 })
    var result = item
    result.catalogItemId = remote.id
    if !protected.contains("title") { result.title = nonEmpty(remote.title) == nil ? item.title : remote.title! }
    if !protected.contains("year") { result.year = owned.contains("year") ? remote.year : (remote.year ?? item.year) }
    if !protected.contains("overview") { result.overview = owned.contains("overview") ? remote.overview : (remote.overview ?? item.overview) }
    if !protected.contains("mediaType") { result.mediaType = remote.mediaType ?? item.mediaType }
    result.userMetadataFields = item.userMetadataFields.union(owned)
    // Keep the revision the draft was based on until acknowledged or explicitly reviewed.
    result.metadataRevision = item.pendingMetadataFields.isEmpty ? remote.metadataRevision : item.metadataRevision
    return result
}

public func acknowledgeManualMetadata(_ item: LibraryItem, sent: LibraryItem, remote: RemoteCatalogItem) -> LibraryItem {
    var acknowledged = item
    acknowledged.metadataRevision = remote.metadataRevision
    if item.metadataEditId == sent.metadataEditId { acknowledged.pendingMetadataFields = [] }
    return mergeManualMetadata(acknowledged, remote: remote)
}

public enum MetadataSyncResult: Sendable {
    case saved(RemoteCatalogItem)
    case conflict(RemoteCatalogItem)
    case failed
}
