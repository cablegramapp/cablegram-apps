package app.cablegram.phone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HouseholdProfile(
    val id: String,
    val name: String,
    @kotlinx.serialization.SerialName("sort_order") val sortOrder: Int = 0,
    @kotlinx.serialization.SerialName("pin_set") val pinSet: Boolean = false,
)

/** GET /api/relay/usage (spec 003 US4). */
@Serializable
data class RelayUsage(
    val plan: String = "free",
    @kotlinx.serialization.SerialName("quota_bytes") val quotaBytes: Long? = null,
    @kotlinx.serialization.SerialName("used_bytes") val usedBytes: Long = 0,
    @kotlinx.serialization.SerialName("remaining_bytes") val remainingBytes: Long? = null,
    @kotlinx.serialization.SerialName("limited_reason") val limitedReason: String? = null,
    @kotlinx.serialization.SerialName("resets_at") val resetsAt: String? = null,
)

@Serializable
data class HouseholdProfiles(val profiles: List<HouseholdProfile> = emptyList())

@Serializable
data class MeDevice(
    val id: String,
    val kind: String? = null,
    @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
    @kotlinx.serialization.SerialName("revoked_at") val revokedAt: String? = null,
    @kotlinx.serialization.SerialName("trust_level") val trustLevel: String = "home",
    @kotlinx.serialization.SerialName("trust_expires_at") val trustExpiresAt: String? = null,
    @kotlinx.serialization.SerialName("telegram_direct_allowed") val telegramDirectAllowed: Boolean = false,
)

fun MeDevice.toTrust() = TvTrust(
    temporary = trustLevel == "temporary",
    expiresAt = trustExpiresAt?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
    telegramDirect = telegramDirectAllowed,
)

@Serializable
data class MeResponse(
    val household: kotlinx.serialization.json.JsonElement? = null,
    val devices: List<MeDevice> = emptyList(),
)

@Serializable
data class PendingApproval(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("media_item_id") val mediaItemId: String? = null,
    val title: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("tv_name") val tvName: String? = null,
)

@Serializable
data class LanPassResponse(
    @SerialName("origin_identities") val originIdentities: List<String> = emptyList(),
    @SerialName("tv_device_id") val tvDeviceId: String? = null,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class PendingApprovalsResponse(val items: List<PendingApproval> = emptyList())

@Serializable
data class ApprovalResponse(val token: String? = null, val ok: Boolean = true)

/** GET/PUT /api/telegram/link (spec 004). Ids are 64-bit and may arrive as numbers or strings. */
@Serializable
data class TelegramLinkInfo(
    val linked: Boolean = false,
    @kotlinx.serialization.SerialName("telegram_user_id") val telegramUserId: kotlinx.serialization.json.JsonPrimitive? = null,
    @kotlinx.serialization.SerialName("display_name") val displayName: String? = null,
    @kotlinx.serialization.SerialName("library_chat_id") val libraryChatId: kotlinx.serialization.json.JsonPrimitive? = null,
    @kotlinx.serialization.SerialName("linked_at") val linkedAt: String? = null,
) {
    val chatId: Long? get() = libraryChatId?.content?.toLongOrNull()
    val userId: Long? get() = telegramUserId?.content?.toLongOrNull()
}

/** Outcome of linking: [conflictName] is set when the household already uses another Telegram account. */
data class TelegramLinkResult(val link: TelegramLinkInfo?, val conflictName: String? = null)

/** A TV Telegram session the phone must end (GET /api/telegram/tv-sessions?due=true). */
@Serializable
data class DueTvSession(
    @kotlinx.serialization.SerialName("tv_device_id") val tvDeviceId: String,
    @kotlinx.serialization.SerialName("telegram_session_id") val telegramSessionId: String,
    val reason: String = "removed",
)

@Serializable
data class DueTvSessions(val sessions: List<DueTvSession> = emptyList())

/** A title removed from the library but remembered by its Telegram file (spec 004 US5). `hidden` can be restored. */
@Serializable
data class TelegramTombstone(
    @kotlinx.serialization.SerialName("stable_source_key") val stableSourceKey: String,
    @kotlinx.serialization.SerialName("item_id") val itemId: String? = null,
    val title: String = "",
    val state: String = "hidden",
    @kotlinx.serialization.SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class TelegramTombstones(val tombstones: List<TelegramTombstone> = emptyList())

@Serializable
data class TelegramDeletionOrder(
    @kotlinx.serialization.SerialName("stable_source_key") val stableSourceKey: String,
    @kotlinx.serialization.SerialName("chat_id") val chatId: Long,
    @kotlinx.serialization.SerialName("message_ids") val messageIds: List<Long> = emptyList(),
)

@Serializable
data class TelegramRemovalResult(@kotlinx.serialization.SerialName("telegram_deletions") val telegramDeletions: List<TelegramDeletionOrder> = emptyList())

/** GET /api/telegram/tv-password-requests/pending: TVs waiting for the Telegram two-step password. */
@Serializable
data class PendingTvPasswordRequests(val requests: List<PendingTvPasswordRequest> = emptyList())

@Serializable
data class PendingTvPasswordRequest(
    @kotlinx.serialization.SerialName("request_id") val requestId: String,
    @kotlinx.serialization.SerialName("tv_name") val tvName: String = "TV",
    /** The TV's one-time public key; the password is sealed to it. */
    @kotlinx.serialization.SerialName("tv_public_key") val tvPublicKey: String,
    /** Telegram's own password hint. */
    val hint: String = "",
)

/** GET /api/telegram/tv-logins/pending (spec 004 US2). */
@Serializable
data class PendingTvLogins(val requests: List<PendingTvLogin> = emptyList())

@Serializable
data class PendingTvLogin(
    @kotlinx.serialization.SerialName("request_id") val requestId: String,
    @kotlinx.serialization.SerialName("tv_device_id") val tvDeviceId: String,
    @kotlinx.serialization.SerialName("tv_name") val tvName: String = "TV",
    @kotlinx.serialization.SerialName("login_link") val loginLink: String,
    @kotlinx.serialization.SerialName("expires_in_ms") val expiresInMs: Long = 0,
)
