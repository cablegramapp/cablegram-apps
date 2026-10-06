package app.cablegram.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class LibraryItem(
    val id: String,
    val title: String,
    val filename: String,
    val fileName: String = filename,
    val durationSeconds: Int? = null,
    val fileSizeBytes: Long? = null,
    val genres: List<String> = emptyList(),
    val year: Int? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    /** Bumped whenever bytes under posterPath change so Compose reloads it. */
    val posterVersion: Int = 0,
    val posterUrl: String? = null,
    val mediaType: String = "movie",
    val enhanced: Boolean = false,
    val importedAt: String,
    val sourceUri: String? = null,
    /** `phone_local`, `web` (resolved by the control plane) or `telegram` (a video in the household's Telegram channel). */
    val sourceKind: String = "phone_local",
    val canonicalUrl: String? = null,
    val copied: Boolean = true,
    /** Last local reachability check; null means this source has not been checked yet. */
    val sourceAvailable: Boolean? = null,
    val storageState: String = STORAGE_LOCAL,
    /** Flow 5: stable source fingerprint sent to the control plane. */
    val fingerprint: String? = null,
    val positionSeconds: Int = 0,
    val lastWatchedAt: String? = null,
    val collectionIds: List<String> = emptyList(),
    val resolution: String? = null,
    val downloadBytes: Long? = null,
    val downloadTotal: Long? = null,
    val uploadBytes: Long? = null,
    val uploadTotal: Long? = null,
    val transferStatus: String = TRANSFER_IDLE,
    val webTransferJobId: String? = null,
    val webTransferError: String? = null,
    val transferBytesPerSec: Long = 0,
    val cloudObjectPresent: Boolean = false,
    /**
     * A copy of this title's video is in the household's Telegram channel and was verified (size and file id)
     * after "Save to Telegram" (spec 004 T011). Free up space may then remove the phone copy.
     */
    val telegramCopy: Boolean = false,
    /**
     * A verified copy of this title's video is in the household's own Cloudflare R2 bucket (spec 005). Set from the
     * catalog on every sync, so it clears when the bucket is disconnected (the copy is then unreachable).
     */
    val ownCloudCopy: Boolean = false,
    /** The catalog's id for that copy, used to remove just that copy (spec 006); null when there is none. */
    val ownCloudSourceId: String? = null,
    /** TMDB id and episode position, so the library can group a series' episodes under one poster. */
    val tmdbId: Int? = null,
    /** Exact identity explicitly reviewed in the editor; background lookup must not replace it. */
    val catalogIdentityUserSelected: Boolean = false,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    /**
     * Restored from the household catalog (e.g. after a reinstall) without a file on this phone.
     * Cleared once a matching file is found or imported again.
     */
    val householdOnly: Boolean = false,
    /** Fields explicitly saved by the user. Catalog refreshes must not replace them. */
    val userMetadataFields: Set<String> = emptySet(),
    /** Server id differs from the local id for files imported on this phone. */
    val catalogItemId: String? = null,
    val metadataRevision: Int = 0,
    /** Durable edits are retried on the next sync, including web and offline-only titles. */
    val pendingMetadataFields: Set<String> = emptySet(),
    val metadataEditId: String? = null,
    val metadataConflict: Boolean = false,
    /** The user-selected artwork source. `legacy` preserves pre-migration posters. */
    val artworkOrigin: String = ARTWORK_LEGACY,
    /** True only after an explicit artwork choice; automatic thumbnails stay replaceable. */
    val artworkUserSelected: Boolean = false,
)

@Serializable
data class UserCollection(
    val id: String,
    val name: String,
)

@Serializable
data class IndexedFolder(
    val uri: String,
    val name: String,
    val volumeId: String? = null,
)

@Serializable
data class LibraryFile(
    /** Household this library syncs with; another account's library is set aside, not merged. */
    val householdId: String? = null,
    /** Household titles the user removed from this phone's list; not restored again. */
    val dismissedRemoteIds: List<String> = emptyList(),
    val items: List<LibraryItem> = emptyList(),
    val collections: List<UserCollection> = emptyList(),
    val folders: List<IndexedFolder> = emptyList(),
    val pendingWebImports: List<PendingWebImport> = emptyList(),
    /** Shared files being copied in; a process death mid-copy leaves the entry for the next start. */
    val pendingSharedImports: List<PendingSharedImport> = emptyList(),
    /** Telegram titles the phone already asked about, so "Find details" never repeats for them. */
    val promptedTelegramIds: List<String> = emptyList(),
)

@Serializable
data class PendingWebImport(
    val id: String,
    val url: String,
    val caption: String? = null,
    val createdAt: String,
    val lastError: String? = null,
)

/** A shared file whose copy into the library has begun. [id] becomes the library item id, so a resume cannot register it twice. */
@Serializable
data class PendingSharedImport(
    val id: String,
    val uri: String,
    val displayName: String,
    val createdAt: String,
)

const val STORAGE_LOCAL = "local"
const val STORAGE_CLOUD = "cloud"
const val STORAGE_BOTH = "both"
const val TRANSFER_IDLE = "idle"
const val TRANSFER_SAVING = "saving"
const val TRANSFER_FAILED = "failed"
/** Marks a failed transfer as a "Save to Telegram" one (kept in `webTransferError`), so Retry goes to Telegram. */
const val TELEGRAM_SAVE_FAILED = "telegram_save"
const val ARTWORK_CATALOG = "catalog"
const val ARTWORK_FRAME = "frame"
const val ARTWORK_USER_IMAGE = "user_image"
const val ARTWORK_PLACEHOLDER = "placeholder"
const val ARTWORK_LEGACY = "legacy"
const val CABLEGRAM_CLOUD_CAP_BYTES = 5L * 1024 * 1024 * 1024

enum class LibrarySyncState { Idle, Running, Completed, Failed }

@Serializable
data class CatalogMetadata(
    val title: String,
    val mediaType: String = "movie",
    val year: Int? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val matchStatus: String = "unmatched",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val tmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Alternative search spellings from AI title resolve; tried when the title misses on TMDB. */
    val variants: List<String> = emptyList(),
    val confidence: Double? = null,
    val cast: List<String> = emptyList(),
    val director: String? = null,
    /** IMDb id when Gemini naming was confident; drives exact TMDB /find lookup. */
    val imdbId: String? = null,
)

@Serializable
data class RemoteCatalogResponse(val items: List<RemoteCatalogItem> = emptyList())

@Serializable
data class CatalogImportResponse(val id: String)

@Serializable
data class WebImportResponse(
    val id: String,
    @SerialName("source_id") val sourceId: String? = null,
    val status: String = "ready",
    val title: String,
    val description: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("canonical_url") val canonicalUrl: String,
)

@Serializable
data class ApiError(val error: String? = null)

@Serializable
data class WebStorageResponse(
    @SerialName("job_id") val jobId: String,
    val status: String = "queued",
    val destination: String,
)

@Serializable
data class LibraryJobResponse(
    val id: String,
    val status: String,
    val progress: Int? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

@Serializable
data class RemoteCatalogItem(
    val id: String,
    val title: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("media_type") val mediaType: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    @SerialName("user_metadata_fields") val userMetadataFields: Set<String> = emptySet(),
    @SerialName("metadata_revision") val metadataRevision: Int = 0,
    val genres: List<String> = emptyList(),
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("series_identity") val seriesIdentity: String? = null,
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("match_status") val matchStatus: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val sources: List<RemoteCatalogSource> = emptyList(),
)

@Serializable
data class RemoteCatalogSource(
    val id: String? = null,
    val kind: String = "phone_local",
    @SerialName("origin_identity") val originIdentity: String? = null,
    @SerialName("origin_filename") val originFilename: String? = null,
    @SerialName("stable_source_key") val stableSourceKey: String? = null,
    @SerialName("phone_location") val phoneLocation: String? = null,
    @SerialName("archive_state") val archiveState: String? = null,
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("canonical_url") val canonicalUrl: String? = null,
    @SerialName("source_fingerprint") val sourceFingerprint: String? = null,
    @SerialName("serving_device_id") val servingDeviceId: String? = null,
    val availability: String? = null,
)

@Serializable
data class MatchResponse(val metadata: CatalogMetadata)

@Serializable
data class TitleResolveResponse(
    val found: Boolean = false,
    val metadata: CatalogMetadata? = null,
)

data class IdentifyStillsResult(
    val metadata: CatalogMetadata? = null,
    val error: String? = null,
)

@Serializable
data class PhonePairResponse(
    val token: String,
    val refreshToken: String? = null,
    val userId: String? = null,
    val displayName: String? = null,
    val needsName: Boolean = false,
    val householdId: String? = null,
    val tvName: String = "TV",
    @SerialName("lan_capability") val lanCapability: String? = null,
    @SerialName("tv_device_id") val tvDeviceId: String? = null,
)

@Serializable
data class AuthTokenResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val user_id: String? = null,
    val household_id: String? = null,
    val display_name: String? = null,
)

@Serializable
data class LanClaimResponse(
    @SerialName("pairing_session_id") val pairingSessionId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("lan_capability") val lanCapability: String? = null,
)@Serializable
data class PhoneDeviceResponse(
    val device_id: String,
    val device_secret: String? = null,
)

@Serializable
data class LibrarySyncResponse(val items: List<LibraryItem> = emptyList())

@Serializable
data class TitleSuggestion(
    val title: String,
    val year: Int? = null,
    val mediaType: String = "movie",
    val source: String = "library",
    /** Highest known season for this series title in the local library. */
    val maxSeason: Int? = null,
    /** Highest known episode within [maxSeason]. */
    val maxEpisode: Int? = null,
)

@Serializable
data class TitleSuggestResponse(val suggestions: List<TitleSuggestion> = emptyList())

@Serializable
data class StorageConnection(
    val id: String? = null,
    val provider: String? = null,
    val status: String? = null,
    val bucketName: String? = null,
    val displayLabel: String? = null,
    /** The signed-in account (Google email); null for providers that connect by keys. */
    val accountLabel: String? = null,
    /** Where files go: the Drive folder name. */
    val locationLabel: String? = null,
    /** A stable code such as `reauthorization_required`, never provider text. */
    val lastError: String? = null,
    val quota: StorageQuota? = null,
)

@Serializable
data class StorageQuota(val usedBytes: Long = 0, val limitBytes: Long? = null)

/** A storage provider the server offers (spec 006). `configured` is false when the server lacks what it needs. */
@Serializable
data class StorageProviderInfo(
    val id: String,
    val name: String = id,
    val connectMethod: String = "keys",
    val configured: Boolean = false,
)

@Serializable
data class StorageStatusResponse(
    val configured: Boolean = false,
    val providers: List<StorageProviderInfo> = emptyList(),
    val connection: StorageConnection? = null,
    val connections: List<StorageConnection> = emptyList(),
)

@Serializable
data class AdsResponse(
    val enabled: Boolean = false,
    val placement: String = "library",
    val headline: String = "",
    val body: String = "",
)

@Serializable
data class RemoteCommand(
    val id: String,
    val command: String,
    val videoId: String? = null,
)

fun parsePairCode(input: String): Pair<String, String>? {
    val trimmed = input.trim()
    if (trimmed.matches(Regex("\\d{6}"))) return trimmed to "TV"
    return parsePairUri(trimmed)
}

fun parsePairUri(uri: String): Pair<String, String>? {
    return runCatching {
        val parsed = java.net.URI(uri.trim())
        if (parsed.scheme != "cablegram" || parsed.rawAuthority != "pair" ||
            !parsed.path.isNullOrEmpty() || parsed.fragment != null) return null
        val params = parsed.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx) to java.net.URLDecoder.decode(part.substring(idx + 1), "UTF-8")
        }.toMap()
        val token = params["token"]?.takeIf { it.isNotBlank() } ?: return null
        token to (params["name"]?.takeIf { it.isNotBlank() } ?: "TV")
    }.getOrNull()
}

fun isValidServerUrl(value: String): Boolean = runCatching {
    val uri = java.net.URI(value.trim())
    uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.query == null && uri.fragment == null &&
        (uri.port == -1 || uri.port in 1..65535)
}.getOrDefault(false)

private val VIDEO_SUFFIX = Regex(
    "\\.(?:mkv|mp4|mov|avi|m4v|webm|mpeg|mpg|ts|m2ts|wmv|flv|3gp|ogv)$",
    RegexOption.IGNORE_CASE,
)

fun isWeakCatalogLabel(value: String?): Boolean {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return true
    val stem = text.replace(VIDEO_SUFFIX, "").trim()
    if (stem.length < 3) return true
    if (stem.count { it.isLetter() } < 3) return true
    if (stem.matches(Regex("^[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$"))) return true
    if (stem.matches(Regex("^[0-9a-fA-F]{12,}$"))) return true
    if (stem.matches(Regex("^(?:AgA|BAA|AQA)[A-Za-z0-9_-]{8,}$"))) return true
    if (stem.matches(Regex("^\\d+_\\d+"))) return true
    // Camera-generated names identify a capture time, not the video's title.
    // Pixel uses PXL_yyyyMMdd_HHmmssSSS (sometimes with an extra suffix).
    if (stem.matches(Regex("^(?:PXL|MVIMG|VID|IMG)[-_]?\\d{8}[-_]?\\d{6,}.*$", RegexOption.IGNORE_CASE))) return true
    if (stem.matches(Regex("^(video|audio|document|photo|image|img|vid)[-_].*", RegexOption.IGNORE_CASE))) return true
    if (stem.matches(Regex("^(personal\\s+video|video|vid|movie|clip|file|telegram|tg|download|media)(\\s+\\d+)?$", RegexOption.IGNORE_CASE))) return true
    return false
}

fun firstCatalogHint(vararg labels: String?): String? =
    labels.mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() && !isWeakCatalogLabel(value) } }.firstOrNull()
