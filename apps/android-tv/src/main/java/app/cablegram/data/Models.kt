package app.cablegram.data

import app.cablegram.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AccountCredential(
    val sessionId: String,
    val token: String,
    val userId: String? = null,
    /** The LAN credential minted when this account's phone paired the TV; each account has its own. */
    val lanCapability: String? = null,
) {
    val effectiveUserId: String?
        get() = userId ?: extractUserIdFromToken(token)
}

fun extractUserIdFromToken(token: String): String? = runCatching {
    val parts = token.split(".")
    if (parts.size >= 2) {
        val payload = parts[1]
        val bytes = runCatching {
            android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }.getOrElse {
            java.util.Base64.getUrlDecoder().decode(payload)
        }
        val jsonStr = String(bytes, Charsets.UTF_8)
        Json.parseToJsonElement(jsonStr).jsonObject["sub"]?.jsonPrimitive?.content
    } else null
}.getOrNull()

@Serializable
data class DeviceSession(
    val sessionId: String,
    val pairingToken: String,
    val pin: String,
    val qrUrl: String,
    val phoneQrUrl: String? = null,
    val expiresAt: String,
)

@Serializable
data class PairingStatus(
    val status: String,
    val token: String? = null,
    @SerialName("sessionId") val sessionId: String? = null,
    @SerialName("phoneHost") val phoneHost: String? = null,
    @SerialName("phonePort") val phonePort: Int? = null,
    @SerialName("phonePublicBaseUrl") val phonePublicBaseUrl: String? = null,
    @SerialName("lan_capability") val lanCapability: String? = null,
)

@Serializable
data class TvCommand(
    val id: String,
    val command: String,
    val payload: Map<String, JsonElement> = emptyMap(),
    @SerialName("expires_at_ms") val expiresAtMs: Long? = null,
    /** Time left when the server sent it; lets a TV with a wrong clock compute a local deadline. */
    @SerialName("expires_in_ms") val expiresInMs: Long? = null,
)

@Serializable
data class TvCommandLibrary(val commands: List<TvCommand> = emptyList())

@Serializable
data class TvPlaybackState(
    val videoId: String? = null,
    val title: String? = null,
    val artworkUrl: String? = null,
    val positionSeconds: Int = 0,
    val durationSeconds: Int? = null,
    val isPlaying: Boolean = false,
    val volume: Int = 100,
    val muted: Boolean = false,
    val updatedAt: String = "",
    val stale: Boolean = false,
)

@Serializable
data class TvPlaybackStateResponse(val state: TvPlaybackState? = null)

@Serializable
data class Profile(
    val id: String,
    val name: String,
    @SerialName("profile_type") val profileType: String = "adult",
    @SerialName("pin_set") val pinSet: Boolean = false,
    @JsonNames("avatar_url")
    @SerialName("avatarUrl") val avatarUrl: String? = null,
) {
    fun resolvedAvatarUrl(apiBaseUrl: String = BuildConfig.API_BASE_URL): String? {
        val url = avatarUrl?.trim().orEmpty()
        if (url.isEmpty()) return null
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("data:")) return url
        return apiBaseUrl.trimEnd('/') + "/" + url.trimStart('/')
    }
}

@Serializable
data class ProfileLibrary(val profiles: List<Profile> = emptyList())

@Serializable
data class ProfileSwitchRequest(
    @SerialName("requestId") val requestId: String,
    val status: String,
    @SerialName("profileName") val profileName: String? = null,
)

@Serializable
data class VideoLibrary(val videos: List<Video> = emptyList())

@Serializable
data class Video(
    val id: String,
    val title: String? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long? = null,
    @SerialName("thumbnail_file_id") val thumbnailFileId: String? = null,
    @SerialName("added_at") val addedAtTimestamp: String = "",
    // `ingesting` means the bot is still copying/remuxing this video for TV playback.
    val tier: String = "hot",
    @SerialName("resume_position_seconds") val resumePositionSeconds: Int? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val resolution: String? = null,
    val overview: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    @SerialName("backdrop_url") val backdropUrl: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: String? = null,
    @SerialName("collection_name") val collectionName: String? = null,
    @SerialName("tmdb_id") val tmdbId: Int? = null,
    @SerialName("media_type") val mediaType: String = "movie",
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
    @SerialName("match_status") val matchStatus: String = "pending",
    @SerialName("ingest_progress") val ingestProgress: Int? = null,
    @SerialName("ingest_stage") val ingestStage: String? = null,
    @SerialName("ingest_label") val ingestLabel: String? = null,
    @SerialName("in_my_list") val inMyList: Boolean = false,
    val playbackUrl: String? = null,
    val source: String? = null,
    val lanPlaybackUrl: String? = null,
    val cloudPlaybackUrl: String? = null,
    val relayPlaybackUrl: String? = null,
    val availableOnLan: Boolean = false,
    val availableOnCloud: Boolean = false,
    val availableOnRelay: Boolean = false,
    val originIdentity: String? = null,
)

/**
 * Phones identify titles by their own local id (the catalog's origin
 * identity); the TV library is keyed by catalog id. Accept either.
 */
fun findRemoteTitle(videos: List<Video>, videoId: String?): Video? {
    if (videoId.isNullOrBlank()) return null
    return videos.firstOrNull { it.id == videoId } ?: videos.firstOrNull { it.originIdentity == videoId }
}

fun Video.hasCompletedIngest(): Boolean {
    if (tier == "ingesting") return false
    if (ingestStage.equals("ready", ignoreCase = true) || (ingestProgress ?: 0) >= 100) return true
    return tier == "hot" || tier == "hot+r2" || tier == "r2" || tier == "evicted" || tier == "failed"
}

@Serializable
data class PlaybackResponse(
    val status: String,
    val url: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val expiresAt: String? = null,
    val pollAfterSeconds: Int? = null,
    val resumePositionSeconds: Int? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val media: MediaInfo? = null,
    val loadingVideoUrl: String? = null,
    val prepareProgress: Int? = null,
    val prepareStage: String? = null,
    val prepareLabel: String? = null,
    val title: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    // T075 / R-5: one-time private playback approval fields.
    @SerialName("attempt_id") val attemptId: String? = null,
    @SerialName("approval_token") val approvalToken: String? = null,
    /**
     * Spec 003: the other path to the same bytes (relay when [url] is LAN, LAN when [url] is the
     * relay). The player switches to it when [url] is unreachable or stalls.
     */
    @kotlinx.serialization.Transient val fallbackUrl: String? = null,
    /**
     * The headers [fallbackUrl] needs, kept apart from [headers] so a cloud bearer token is never sent to a phone, a relay
     * or a local stream (spec 006). Empty for every fallback today.
     */
    @kotlinx.serialization.Transient val fallbackHeaders: Map<String, String> = emptyMap(),
    /**
     * Review fix 9: a fallback that costs requests to find (the phone's own Telegram session) is looked up
     * only when the player actually needs it, not before every Telegram playback starts.
     */
    @kotlinx.serialization.Transient val fallbackResolver: (suspend () -> String?)? = null,
)

@Serializable
data class LoadingVideosResponse(
    val videos: List<String> = emptyList(),
)

val DEFAULT_LOADING_VIDEOS = listOf(
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/71d5c9fa12bcb2c00065650ec0a9c036_1788595470(1).mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/8ce3920ad5938019cd4da4a35b10e4d3_1788596836%20copy.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Italy.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Japan.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Paris.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/Persian.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/a0973935ccd2cacdf98b9957473a7b46_1788595113.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/a4571b080d6c76759e3bb973c3ca8ce8_1788597900%20copy.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/africa.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/arabic.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/brazil.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/c51e98d6da756d6f38c62a2651a7cc8f_1788595799%20copy.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/germany.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/mexico.mp4",
    "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev/russia.mp4",
)

fun getRandomLoadingVideoUrl(): String = DEFAULT_LOADING_VIDEOS.random()

const val PRIVACY_POLICY_URL = "https://cablegram.app/privacy.html"
const val DEMO_REVIEWER_PIN = "9999"
const val DEMO_SELECT_CLICKS = 5
const val DEMO_ID_PREFIX = "demo:"

fun isDemoVideoId(videoId: String): Boolean = videoId.startsWith(DEMO_ID_PREFIX)

private data class DemoTitle(
    val slug: String,
    val title: String,
    val url: String,
    val posterUrl: String,
    val overview: String,
    val year: Int,
    val durationSeconds: Int,
)

private const val DEMO_LOOP_BASE = "https://pub-e1061ea99e4c4c17bf26411787d5a160.r2.dev"

private val DEMO_TITLES = listOf(
    DemoTitle(
        slug = "italy",
        title = "Italy",
        url = "$DEMO_LOOP_BASE/Italy.mp4",
        posterUrl = "$DEMO_LOOP_BASE/italy.png",
        overview = "Royalty-free travel loop for Play Console Demo Mode.",
        year = 2024,
        durationSeconds = 30,
    ),
    DemoTitle(
        slug = "japan",
        title = "Japan",
        url = "$DEMO_LOOP_BASE/Japan.mp4",
        posterUrl = "$DEMO_LOOP_BASE/japan.png",
        overview = "Royalty-free travel loop for Play Console Demo Mode.",
        year = 2024,
        durationSeconds = 30,
    ),
    DemoTitle(
        slug = "paris",
        title = "Paris",
        url = "$DEMO_LOOP_BASE/Paris.mp4",
        posterUrl = "$DEMO_LOOP_BASE/paris.png",
        overview = "Royalty-free travel loop for Play Console Demo Mode.",
        year = 2024,
        durationSeconds = 30,
    ),
    DemoTitle(
        slug = "africa",
        title = "Africa",
        url = "$DEMO_LOOP_BASE/africa.mp4",
        posterUrl = "$DEMO_LOOP_BASE/africa.png",
        overview = "Royalty-free travel loop for Play Console Demo Mode.",
        year = 2024,
        durationSeconds = 30,
    ),
    DemoTitle(
        slug = "big-buck-bunny",
        title = "Big Buck Bunny",
        url = "https://archive.org/download/BigBuckBunny_124/Content/big_buck_bunny_720p_surround.mp4",
        posterUrl = "https://archive.org/services/img/BigBuckBunny_124",
        overview = "Open movie from the Blender Foundation. Play Console Demo Mode sample.",
        year = 2008,
        durationSeconds = 596,
    ),
    DemoTitle(
        slug = "elephants-dream",
        title = "Elephants Dream",
        url = "https://archive.org/download/ElephantsDream/ed_hd.mp4",
        posterUrl = "https://archive.org/services/img/ElephantsDream",
        overview = "Royalty-free Blender Foundation short, used for the demo library.",
        year = 2006,
        durationSeconds = 654,
    ),
    DemoTitle(
        slug = "sintel",
        title = "Sintel",
        url = "https://archive.org/download/Sintel/sintel-2048-surround.mp4",
        posterUrl = "https://archive.org/services/img/Sintel",
        overview = "Royalty-free Blender Foundation short for store review.",
        year = 2010,
        durationSeconds = 888,
    ),
)

fun demoLibraryVideos(): List<Video> = DEMO_TITLES.map { item ->
    Video(
        id = DEMO_ID_PREFIX + item.slug,
        title = item.title,
        durationSeconds = item.durationSeconds,
        addedAtTimestamp = "1970-01-01T00:00:00Z",
        tier = "hot",
        overview = item.overview,
        releaseYear = item.year,
        posterUrl = item.posterUrl,
        backdropUrl = item.posterUrl,
        genres = listOf("Demo"),
        mediaType = "movie",
        matchStatus = "matched",
        ingestProgress = 100,
        ingestStage = "ready",
        ingestLabel = "Ready",
    )
}

fun demoPlaybackUrl(videoId: String): String? {
    val slug = videoId.removePrefix(DEMO_ID_PREFIX)
    return DEMO_TITLES.firstOrNull { it.slug == slug }?.url
}

@Serializable
data class MediaInfo(
    val videoCodec: String? = null,
    val videoProfile: String? = null,
    val videoBitDepth: Int? = null,
    val videoLevel: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
)

@Serializable
data class TranscodeResponse(
    val status: String,
    val pollAfterSeconds: Int? = null,
)

@Serializable
data class SubtitleTrack(
    val trackId: String,
    val language: String? = null,
    val label: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val url: String,
)

@Serializable
private data class ApiError(
    val error: String? = null,
    val message: String? = null,
)

class ApiException(
    val statusCode: Int,
    override val message: String,
    val error: String? = null,
) : Exception(message)

/**
 * Set by the app: called when the control plane says this TV's end time has passed (`401 device_expired`,
 * spec 004 US8), so it can wipe what it stored. A process-wide hook, because the error can surface from any call.
 */
@Volatile internal var onDeviceExpired: (() -> Unit)? = null

internal fun decodeApiError(statusCode: Int, body: String, json: kotlinx.serialization.json.Json): ApiException {
    val parsed = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
    if (statusCode == 401 && parsed?.error == "device_expired") onDeviceExpired?.invoke()
    return ApiException(
        statusCode,
        parsed?.message ?: parsed?.error ?: "Request failed ($statusCode)",
        parsed?.error,
    )
}

/**
 * Whether a renewed playback needs the player reloaded. A signed URL (R2) changes with every renewal; a Drive URL stays
 * the same and only the bearer header changes, so the headers count too (spec 006).
 */
fun playbackNeedsReload(current: PlaybackResponse, renewed: PlaybackResponse): Boolean =
    renewed.url != null && (renewed.url != current.url || renewed.headers != current.headers)
