package app.cablegram.phone

enum class StorageBadge { Local, Cloud, Both, Saving, Failed, Unavailable, NotOnPhone, Telegram }

fun storageBadge(item: LibraryItem): StorageBadge = when {
    item.transferStatus == TRANSFER_SAVING -> StorageBadge.Saving
    item.transferStatus == TRANSFER_FAILED && !item.cloudObjectPresent -> StorageBadge.Failed
    item.sourceKind == "telegram" -> if (item.sourceAvailable == false) StorageBadge.Unavailable else StorageBadge.Telegram
    // Saved to Telegram: still on the phone too (Both), or only in Telegram once the phone copy was freed.
    item.telegramCopy && item.sourceAvailable == false -> StorageBadge.Telegram
    item.telegramCopy && !item.cloudObjectPresent -> StorageBadge.Both
    item.sourceAvailable == false -> when {
        item.cloudObjectPresent -> StorageBadge.Cloud
        item.householdOnly -> StorageBadge.NotOnPhone
        else -> StorageBadge.Unavailable
    }
    item.storageState == STORAGE_BOTH || (item.copied && item.cloudObjectPresent) -> StorageBadge.Both
    item.storageState == STORAGE_CLOUD || (!item.copied && item.cloudObjectPresent) -> StorageBadge.Cloud
    else -> StorageBadge.Local
}

fun storageBadgeLabel(badge: StorageBadge): String = when (badge) {
    StorageBadge.Local -> "On phone"
    StorageBadge.Cloud -> "Cloud"
    StorageBadge.Both -> "Phone & cloud"
    StorageBadge.Saving -> "Saving…"
    StorageBadge.Failed -> "Save failed"
    StorageBadge.Unavailable -> "Unavailable"
    StorageBadge.NotOnPhone -> "Not on this phone"
    StorageBadge.Telegram -> "Cloud · Telegram"
}

fun storageStatusLine(item: LibraryItem): String = when (storageBadge(item)) {
    StorageBadge.Local -> "On phone"
    StorageBadge.Cloud -> "Cloud"
    StorageBadge.Both -> "Available on phone & cloud"
    StorageBadge.Saving -> "On phone · Saving…"
    StorageBadge.Failed -> "Cloud save failed · Open to retry"
    StorageBadge.Unavailable -> "Source unavailable · Title kept in library"
    StorageBadge.NotOnPhone -> "In your household library · File not on this phone"
    StorageBadge.Telegram -> "Cloud · Telegram"
}

/**
 * A new Telegram title the server could not match on its own (spec 004 T008) is offered to the owner
 * once. The server tries automatically right after registration, so a title is only offered after
 * [graceMs] has passed, and never again once asked.
 */
fun needsTelegramMatchPrompt(matchStatus: String?, createdAt: java.time.Instant?, now: java.time.Instant, alreadyPrompted: Boolean, graceMs: Long = 90_000): Boolean =
    !alreadyPrompted && matchStatus == "unmatched" && createdAt != null &&
        now.toEpochMilli() - createdAt.toEpochMilli() >= graceMs

fun continueWatching(items: List<LibraryItem>): List<LibraryItem> =
    items.filter { it.positionSeconds > 8 && watchFraction(item = it) < 0.92f }
        .sortedByDescending { it.lastWatchedAt.orEmpty() }

fun recentlyAdded(items: List<LibraryItem>): List<LibraryItem> =
    items.sortedByDescending { it.importedAt }.take(20)

fun movies(items: List<LibraryItem>): List<LibraryItem> =
    items.filter { it.mediaType != "tv" }

fun tvShows(items: List<LibraryItem>): List<LibraryItem> =
    items.filter { it.mediaType == "tv" }

fun watchFraction(item: LibraryItem): Float {
    val duration = item.durationSeconds ?: return 0f
    if (duration <= 0) return 0f
    return (item.positionSeconds.toFloat() / duration).coerceIn(0f, 1f)
}

fun remainingLabel(item: LibraryItem): String {
    val duration = item.durationSeconds ?: return ""
    val left = (duration - item.positionSeconds).coerceAtLeast(0)
    return formatDuration(left)?.let { "$it left" }.orEmpty()
}

fun formatDuration(seconds: Int?): String? {
    if (seconds == null || seconds <= 0) return null
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

fun formatRate(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    return "${formatBytes(bytesPerSec)}/s"
}

fun etaLabel(remainingBytes: Long, bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val seconds = (remainingBytes / bytesPerSec).toInt().coerceAtLeast(1)
    return if (seconds < 60) "~$seconds sec remaining" else "~${(seconds + 59) / 60} min remaining"
}

fun transferFraction(item: LibraryItem): Float {
    val total = item.uploadTotal ?: item.fileSizeBytes ?: return 0f
    if (total <= 0) return 0f
    return ((item.uploadBytes ?: 0).toFloat() / total).coerceIn(0f, 1f)
}

fun searchLibrary(items: List<LibraryItem>, query: String): List<LibraryItem> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return items
    return items.filter { item ->
        item.title.lowercase().contains(needle) ||
            item.filename.lowercase().contains(needle) ||
            item.year?.toString() == needle ||
            item.genres.any { it.lowercase().contains(needle) }
    }
}

fun libraryMediaBytes(items: List<LibraryItem>): Long =
    items.filter { it.copied }.sumOf { it.fileSizeBytes ?: 0L }

fun cloudMediaBytes(items: List<LibraryItem>): Long =
    items.filter { it.cloudObjectPresent }.sumOf { it.fileSizeBytes ?: 0L }

fun cloudAvailableBytes(items: List<LibraryItem>, cap: Long = CABLEGRAM_CLOUD_CAP_BYTES, unlimited: Boolean = false): Long {
    if (unlimited) return Long.MAX_VALUE / 4
    return (cap - cloudMediaBytes(items)).coerceAtLeast(0)
}

fun fitsInCloud(item: LibraryItem, available: Long): Boolean {
    val size = item.fileSizeBytes ?: 0L
    if (size <= 0) return true
    return size <= available
}

/** Free up space: only once a verified copy exists elsewhere (Cablegram cloud, or Telegram after Save to Telegram). */
fun canRemoveLocalCopy(item: LibraryItem): Boolean =
    item.sourceAvailable != false && (item.cloudObjectPresent || item.telegramCopy) && item.copied && item.transferStatus != TRANSFER_SAVING

/**
 * Save to Telegram (spec 004 T011). Private titles never go to Telegram: the channel would hold a copy that any
 * device signed in to the account can play, which the phone's approval for private titles is meant to prevent.
 */
fun canSaveToTelegram(item: LibraryItem): Boolean =
    item.sourceKind == "phone_local" && !item.telegramCopy && !item.isPrivate && !item.householdOnly &&
        item.sourceAvailable != false && item.transferStatus != TRANSFER_SAVING

/** Why a file can't be saved to Telegram before any upload starts, or null when it fits (2 GB, or 4 GB with Premium). */
fun telegramUploadRefusal(sizeBytes: Long, limitBytes: Long): String? = when {
    sizeBytes <= 0 -> "Cablegram can't tell how big this video is."
    sizeBytes > limitBytes -> "This video is ${formatGigabytes(sizeBytes)} GB. Telegram accepts files up to ${formatGigabytes(limitBytes)} GB " +
        "on your account" + if (limitBytes < 3_000L * 1024 * 1024) " (4 GB with Telegram Premium)." else "."
    else -> null
}

private fun formatGigabytes(bytes: Long): String = String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024 * 1024))

fun canSaveToCloud(item: LibraryItem): Boolean =
    item.sourceKind != "telegram" && item.sourceAvailable != false && !item.cloudObjectPresent && item.transferStatus != TRANSFER_SAVING

fun freeUpCandidates(items: List<LibraryItem>): List<LibraryItem> =
    items.filter(::canRemoveLocalCopy).sortedByDescending { it.fileSizeBytes ?: 0L }

fun freeUpBytes(items: List<LibraryItem>): Long =
    freeUpCandidates(items).sumOf { it.fileSizeBytes ?: 0L }

fun itemMatchesCollection(item: LibraryItem, collectionId: String): Boolean =
    collectionId in item.collectionIds

fun cloudFiles(items: List<LibraryItem>): List<LibraryItem> =
    items.filter { it.cloudObjectPresent }.sortedByDescending { it.fileSizeBytes ?: 0L }

fun needsTitleInput(vararg labels: String?): Boolean = firstCatalogHint(*labels) == null

enum class ImportMetadataAction { PromptForCorrection, Ready }

fun importMetadataAction(item: LibraryItem): ImportMetadataAction =
    ImportMetadataAction.PromptForCorrection

/**
 * A readable filename may skip title correction only after the local probe
 * produced artwork. Placeholder-only items still need the frame chooser.
 */
fun needsArtworkChoice(item: LibraryItem): Boolean =
    item.artworkOrigin == ARTWORK_PLACEHOLDER &&
        item.posterPath.isNullOrBlank() &&
        item.posterUrl.isNullOrBlank()

fun catalogMayReplaceArtwork(item: LibraryItem): Boolean =
    !item.artworkUserSelected

fun visibleBrowseEntries(entries: List<BrowseEntry>, videosOnly: Boolean): List<BrowseEntry> {
    // Flow 2 (legacy parity): browse lists video files only. The `videosOnly`
    // parameter is kept for call-site compatibility but never weakens the
    // filter — pictures/audio/documents never appear.
    val shown = entries.filterNot { isHiddenBrowseName(it.name) }
        .filter { it.isDirectory || it.isVideo || canAddAsVideo(it) }
    return shown.sortedWith(compareByDescending<BrowseEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
}

fun canAddAsVideo(entry: BrowseEntry): Boolean {
    if (entry.isDirectory) return false
    if (entry.isVideo) return true
    val type = entry.mime.lowercase()
    if (type.startsWith("image/") || type.startsWith("audio/") || type.startsWith("text/")) return false
    if (type == "application/pdf") return false
    val ext = entry.name.substringAfterLast('.', "").lowercase()
    if (ext in NON_VIDEO_EXTS) return false
    // Unknown/generic mime: only recognize known video extensions.
    if (type.startsWith("video/") || type == "application/vnd.rn-realmedia") return true
    return ext in VIDEO_EXTS
}

private val NON_VIDEO_EXTS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp",
    "mp3", "m4a", "aac", "flac", "wav", "ogg",
    "pdf", "txt", "json", "xml", "html", "apk",
)

fun isHiddenBrowseName(name: String): Boolean {
    val stem = name.trim().trimEnd('/').lowercase()
    return stem in HIDDEN_BROWSE_NAMES || stem.startsWith('.')
}

private val HIDDEN_BROWSE_NAMES = setOf("android", "lost+found", "thumbnails", ".thumbnails", ".trashed", "recycle bin")

fun previewFrameTimesUs(durationMs: Long, count: Int = 4, random: kotlin.random.Random = kotlin.random.Random.Default): List<Long> {
    val n = count.coerceIn(1, 8)
    if (durationMs <= 0L) return List(n) { index -> 1_000_000L * (index + 1) }
    val durationUs = durationMs * 1_000L
    val start = (durationUs * 12 / 100).coerceAtLeast(500_000L)
    val end = (durationUs * 88 / 100).coerceAtMost(durationUs - 250_000L).coerceAtLeast(start + 1)
    val span = (end - start).coerceAtLeast(1L)
    return List(n) { index ->
        val slot = span.toDouble() / n
        val center = start + slot * (index + 0.5)
        val jitter = (random.nextDouble() - 0.5) * slot * 0.6
        (center + jitter).toLong().coerceIn(start, end)
    }
}

/** A file in the videos folder, as the sweep sees it. */
internal data class VideoFileInfo(val name: String, val lastModified: Long)

/** Files younger than this are left alone: something may still be writing them. */
private const val SWEEP_GRACE_MS = 60_000L

/**
 * Names to delete from the videos folder: `.part` files and files no library row points to. A name in
 * [importing] (or a file changed within the last minute) is never touched.
 */
internal fun orphanedVideoFiles(
    files: List<VideoFileInfo>,
    referenced: Set<String>,
    importing: Set<String>,
    now: Long,
): List<String> = files.filter { file ->
    val final = file.name.removeSuffix(".part")
    val kept = final in importing || now - file.lastModified < SWEEP_GRACE_MS || (!file.name.endsWith(".part") && file.name in referenced)
    !kept
}.map { it.name }

internal enum class SharedImportResume { Finish, Copy, Drop }

/** What to do with a shared import a previous run left unfinished. */
internal fun sharedImportResume(alreadyRegistered: Boolean, sourceReadable: Boolean): SharedImportResume = when {
    alreadyRegistered -> SharedImportResume.Finish
    sourceReadable -> SharedImportResume.Copy
    else -> SharedImportResume.Drop
}

private val PERMANENT_WEB_IMPORT_ERRORS = setOf("unsupported_site", "no_video", "unsafe_url", "invalid_url")

/** A web import the server rejected for good: submitting the same link again cannot succeed. */
internal fun isPermanentWebImportError(error: String?): Boolean = error in PERMANENT_WEB_IMPORT_ERRORS
