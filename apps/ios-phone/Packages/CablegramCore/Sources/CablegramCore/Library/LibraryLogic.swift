import Foundation

public enum StorageBadge: Sendable, Equatable { case local, cloud, both, saving, failed, unavailable, notOnPhone, telegram }

public func storageBadge(_ item: LibraryItem) -> StorageBadge {
    if item.transferStatus == transferSaving { return .saving }
    if item.transferStatus == transferFailed && !item.cloudObjectPresent { return .failed }
    if item.sourceKind == "telegram" { return item.sourceAvailable == false ? .unavailable : .telegram }
    // Saved to Telegram: still on the phone too (both), or only in Telegram once the phone copy was freed.
    if item.telegramCopy && item.sourceAvailable == false { return .telegram }
    if item.telegramCopy && !item.cloudObjectPresent { return .both }
    // Saved to the person's own R2 bucket: same two states.
    if item.ownCloudCopy && item.sourceAvailable == false { return .cloud }
    if item.ownCloudCopy && !item.cloudObjectPresent { return .both }
    if item.sourceAvailable == false {
        if item.cloudObjectPresent { return .cloud }
        return item.householdOnly ? .notOnPhone : .unavailable
    }
    if item.storageState == storageBoth || (item.copied && item.cloudObjectPresent) { return .both }
    if item.storageState == storageCloud || (!item.copied && item.cloudObjectPresent) { return .cloud }
    return .local
}

extension StorageBadge {
    /// String Catalog key of the poster badge (Android `storageBadgeLabel`).
    public var labelKey: String {
        switch self {
        case .local: "ui_on_phone_9557b8"
        case .cloud: "ui_cloud_b977b9"
        case .both: "ui_badge_phone_cloud"
        case .saving: "ui_saving_23e392"
        case .failed: "ui_badge_save_failed"
        case .unavailable: "ui_badge_unavailable"
        case .notOnPhone: "ui_badge_not_on_phone"
        case .telegram: "ui_cloud_telegram_798118"
        }
    }

    /// String Catalog key of the longer status line (Android `storageStatusLine`).
    public var statusLineKey: String {
        switch self {
        case .local: "ui_on_phone_9557b8"
        case .cloud: "ui_cloud_b977b9"
        case .both: "ui_available_on_phone_cloud_50eb85"
        case .saving: "ui_on_phone_saving_561d79"
        case .failed: "ui_cloud_save_failed_open_to_retry_a39bc6"
        case .unavailable: "ui_source_unavailable_title_kept_in_library_1c80a2"
        case .notOnPhone: "ui_in_your_household_library_file_not_on_this_phone_698618"
        case .telegram: "ui_cloud_telegram_798118"
        }
    }
}

/// A new, unmatched Telegram title (spec 004 T008) is marked for the owner once, after `graceMs`, and never again.
/// Nothing is looked up automatically: details come from Edit artwork.
public func needsTelegramMatchPrompt(matchStatus: String?, createdAt: Date?, now: Date, alreadyPrompted: Bool, graceMs: Int64 = 90_000) -> Bool {
    guard !alreadyPrompted, matchStatus == "unmatched", let createdAt else { return false }
    return Int64(now.timeIntervalSince(createdAt) * 1000) >= graceMs
}

public func watchFraction(_ item: LibraryItem) -> Float {
    guard let duration = item.durationSeconds, duration > 0 else { return 0 }
    return min(max(Float(item.positionSeconds) / Float(duration), 0), 1)
}

public func continueWatching(_ items: [LibraryItem]) -> [LibraryItem] {
    items.filter { $0.positionSeconds > 8 && watchFraction($0) < 0.92 }
        .sorted { ($0.lastWatchedAt ?? "") > ($1.lastWatchedAt ?? "") }
}

public func recentlyAdded(_ items: [LibraryItem]) -> [LibraryItem] {
    Array(items.sorted { $0.importedAt > $1.importedAt }.prefix(20))
}

public func movies(_ items: [LibraryItem]) -> [LibraryItem] { items.filter { $0.mediaType != "tv" } }
public func tvShows(_ items: [LibraryItem]) -> [LibraryItem] { items.filter { $0.mediaType == "tv" } }

/// "1:02:03" or "12:34"; nil when there is no usable duration.
public func formatDuration(_ seconds: Int?) -> String? {
    guard let seconds, seconds > 0 else { return nil }
    let h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
}

/// The time left in a title as "H:MM:SS"; the app wraps it in the "%1$s left" string.
public func remainingDuration(_ item: LibraryItem) -> String? {
    guard let duration = item.durationSeconds else { return nil }
    return formatDuration(max(duration - item.positionSeconds, 0))
}

public func formatBytes(_ bytes: Int64) -> String {
    if bytes < 1024 { return "\(bytes) B" }
    let kb = Double(bytes) / 1024
    if kb < 1024 { return String(format: "%.1f KB", kb) }
    let mb = kb / 1024
    if mb < 1024 { return String(format: "%.1f MB", mb) }
    return String(format: "%.1f GB", mb / 1024)
}

public func formatRate(_ bytesPerSec: Int64) -> String { bytesPerSec <= 0 ? "" : "\(formatBytes(bytesPerSec))/s" }

/// Seconds left for a transfer, or nil when the rate is unknown; the app picks "sec" or "min" wording.
public enum TransferEta: Equatable, Sendable { case seconds(Int), minutes(Int) }

public func transferEta(remainingBytes: Int64, bytesPerSec: Int64) -> TransferEta? {
    guard bytesPerSec > 0 else { return nil }
    let seconds = max(Int(remainingBytes / bytesPerSec), 1)
    return seconds < 60 ? .seconds(seconds) : .minutes((seconds + 59) / 60)
}

public func transferFraction(_ item: LibraryItem) -> Float {
    guard let total = item.uploadTotal ?? item.fileSizeBytes, total > 0 else { return 0 }
    return min(max(Float(item.uploadBytes ?? 0) / Float(total), 0), 1)
}

public func searchLibrary(_ items: [LibraryItem], query: String) -> [LibraryItem] {
    let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    if needle.isEmpty { return items }
    return items.filter { item in
        item.title.lowercased().contains(needle) || item.filename.lowercased().contains(needle) ||
            item.year.map(String.init) == needle || item.genres.contains { $0.lowercased().contains(needle) }
    }
}

public func libraryMediaBytes(_ items: [LibraryItem]) -> Int64 {
    items.filter(\.copied).reduce(0) { $0 + ($1.fileSizeBytes ?? 0) }
}

/// Bytes copied to the household's own storage (Drive or R2).
public func cloudMediaBytes(_ items: [LibraryItem]) -> Int64 {
    items.filter(\.ownCloudCopy).reduce(0) { $0 + ($1.fileSizeBytes ?? 0) }
}

public func cloudAvailableBytes(_ items: [LibraryItem], cap: Int64 = cablegramCloudCapBytes, unlimited: Bool = false) -> Int64 {
    unlimited ? Int64.max / 4 : max(cap - cloudMediaBytes(items), 0)
}

/// Free up space: only once a verified copy exists off this phone (Telegram after Save to Telegram, or the household's own
/// storage, which the control plane verifies by size before it records the copy). A copy kept inside the app on this phone
/// does not count (CAB-38).
public func canRemoveLocalCopy(_ item: LibraryItem) -> Bool {
    item.sourceAvailable != false && (item.telegramCopy || item.ownCloudCopy) && item.copied && item.transferStatus != transferSaving
}

/// The phone still has this title's video: stored inside the app, or readable at its original place on the phone.
public func phoneStillHoldsVideo(_ item: LibraryItem) -> Bool {
    item.copied || (item.sourceUri != nil && item.sourceAvailable != false)
}

/// Remove just the copy in the household's own storage (spec 006). Only while this phone still holds the video.
public func canRemoveOwnCloudCopy(_ item: LibraryItem) -> Bool {
    item.ownCloudCopy && item.ownCloudSourceId != nil && phoneStillHoldsVideo(item) && item.transferStatus != transferSaving
}

/// Save to Telegram (spec 004 T011). Private titles never go to Telegram.
public func canSaveToTelegram(_ item: LibraryItem) -> Bool {
    item.sourceKind == "phone_local" && !item.telegramCopy && !item.isPrivate && !item.householdOnly &&
        item.sourceAvailable != false && item.transferStatus != transferSaving
}

/// Why a file can't be saved to Telegram before any upload starts, or nil when it fits (2 GB, or 4 GB with Premium).
/// The wording is English-only on Android too.
public func telegramUploadRefusal(sizeBytes: Int64, limitBytes: Int64) -> String? {
    func gb(_ bytes: Int64) -> String { String(format: "%.1f", locale: Locale(identifier: "en_US"), Double(bytes) / (1024.0 * 1024 * 1024)) }
    if sizeBytes <= 0 { return "Cablegram can't tell how big this video is." }
    if sizeBytes > limitBytes {
        return "This video is \(gb(sizeBytes)) GB. Telegram accepts files up to \(gb(limitBytes)) GB on your account"
            + (limitBytes < 3_000 * 1024 * 1024 ? " (4 GB with Telegram Premium)." : ".")
    }
    return nil
}

/// Web titles are not saved: Cablegram no longer keeps copies of web videos on its server (CAB-27).
public func canSaveToCloud(_ item: LibraryItem) -> Bool {
    item.sourceKind != "telegram" && item.sourceKind != "web" && item.sourceAvailable != false &&
        !item.cloudObjectPresent && !item.ownCloudCopy && item.transferStatus != transferSaving
}

public func freeUpCandidates(_ items: [LibraryItem]) -> [LibraryItem] {
    items.filter(canRemoveLocalCopy).sorted { ($0.fileSizeBytes ?? 0) > ($1.fileSizeBytes ?? 0) }
}

public func freeUpBytes(_ items: [LibraryItem]) -> Int64 { freeUpCandidates(items).reduce(0) { $0 + ($1.fileSizeBytes ?? 0) } }

public func itemMatchesCollection(_ item: LibraryItem, collectionId: String) -> Bool { item.collectionIds.contains(collectionId) }

public func cloudFiles(_ items: [LibraryItem]) -> [LibraryItem] {
    items.filter(\.ownCloudCopy).sorted { ($0.fileSizeBytes ?? 0) > ($1.fileSizeBytes ?? 0) }
}

public enum ImportMetadataAction: Sendable { case promptForCorrection, ready }

public func importMetadataAction(_ item: LibraryItem) -> ImportMetadataAction { .ready }

/// A readable filename may skip title correction only after the local probe produced artwork.
public func needsArtworkChoice(_ item: LibraryItem) -> Bool {
    item.artworkOrigin == artworkPlaceholder && (item.posterPath ?? "").isEmpty && (item.posterUrl ?? "").isEmpty
}

public func catalogMayReplaceArtwork(_ item: LibraryItem) -> Bool { !item.artworkUserSelected }

/// The server's copy of a cover a phone uploaded; the phone that shared it keeps drawing its own file.
public func isHouseholdPosterUrl(_ url: String?) -> Bool {
    guard let url else { return false }
    return url.range(of: #"/api/catalog/items/[^/]+/poster$"#, options: .regularExpression) != nil
}

/// CAB-29: only a cover made on this phone (a video frame or the person's image) can be saved to the household.
public func canSaveArtworkToHousehold(_ item: LibraryItem) -> Bool {
    !item.isPrivate && !(item.posterPath ?? "").isEmpty && ((item.posterUrl ?? "").isEmpty || isHouseholdPosterUrl(item.posterUrl))
}

public func artworkSavedToHousehold(_ item: LibraryItem) -> Bool {
    item.householdArtworkVersion == item.posterVersion && canSaveArtworkToHousehold(item)
}

public enum HouseholdArtworkStep: Sendable { case none, upload, remove }

/// Frames stay on the phone unless the person saved exactly this cover; anything else uploaded is removed.
public func householdArtworkStep(_ item: LibraryItem) -> HouseholdArtworkStep {
    if artworkSavedToHousehold(item) {
        return item.householdArtworkUploadedVersion == item.posterVersion ? .none : .upload
    }
    return item.householdArtworkUploadedVersion != nil ? .remove : .none
}

/// Times (microseconds) for the preview frames of the artwork chooser, jittered inside the middle of the video.
public func previewFrameTimesUs<R: RandomNumberGenerator>(durationMs: Int64, count: Int = 4, using random: inout R) -> [Int64] {
    let n = min(max(count, 1), 8)
    if durationMs <= 0 { return (0..<n).map { 1_000_000 * Int64($0 + 1) } }
    let durationUs = durationMs * 1_000
    let start = max(durationUs * 12 / 100, 500_000)
    let end = max(min(durationUs * 88 / 100, durationUs - 250_000), start + 1)
    let span = max(end - start, 1)
    let slot = Double(span) / Double(n)
    return (0..<n).map { index in
        let center = Double(start) + slot * (Double(index) + 0.5)
        let jitter = (Double.random(in: 0..<1, using: &random) - 0.5) * slot * 0.6
        return min(max(Int64(center + jitter), start), end)
    }
}
