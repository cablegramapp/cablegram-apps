package app.cablegram.phone

import android.content.Context
import app.cablegram.phone.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * One paired TV from the phone's point of view.
 *
 * [capability] is the per-device LAN secret minted by the control plane at
 * claim time (R-1) and the only credential the LAN server accepts. [pin] is the
 * 6-digit pairing code the phone typed/claimed; it identifies the TV locally
 * and is never a LAN credential.
 */
data class PairedTv(
    val pin: String,
    val name: String,
    val capability: String? = null,
    val deviceId: String? = null,
    val trust: TvTrust = TvTrust.Home,
    val castDeviceId: String? = null,
)

class PairingStore(context: Context) : AccountTokens {
    private val prefs = context.getSharedPreferences("cablegram_phone_pair", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    init {
        // T079: one-shot migration — move the legacy plaintext token into the
        // Keystore-backed SecretStore, then delete it from prefs.
        val legacy = prefs.getString("account_token", null)
        if (!legacy.isNullOrBlank() && secrets.accountToken == null) {
            secrets.accountToken = legacy
        }
        prefs.edit().remove("account_token").apply()
        prefs.getString("phone_device_id", null)?.takeIf { it.isNotBlank() }?.let {
            if (secrets.phoneDeviceId == null) secrets.phoneDeviceId = it
        }
        prefs.edit().remove("phone_device_id").apply()
    }

    var tvName: String
        get() = tvs.lastOrNull()?.name ?: prefs.getString("tv_name", "TV") ?: "TV"
        set(value) { prefs.edit().putString("tv_name", value).apply() }

    override var accountToken: String?
        get() = secrets.accountToken
        set(value) { secrets.accountToken = value }

    override var refreshToken: String?
        get() = secrets.refreshToken
        set(value) { secrets.refreshToken = value }

    var phoneDeviceId: String?
        get() = secrets.phoneDeviceId
        set(value) { secrets.phoneDeviceId = value }

    var displayName: String
        get() = prefs.getString("display_name", "") ?: ""
        set(value) { prefs.edit().putString("display_name", value).apply() }

    var tvs: List<PairedTv>
        get() {
            val raw = prefs.getString("tvs_json", null)
            if (raw.isNullOrBlank()) {
                val pin = prefs.getString("lan_token", null) ?: return emptyList()
                return listOf(PairedTv(pin, prefs.getString("tv_name", "TV") ?: "TV"))
            }
            return runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map { index ->
                    val obj = array.getJSONObject(index)
                    PairedTv(
                        pin = obj.getString("pin"),
                        name = obj.optString("name", "TV"),
                        capability = obj.optString("capability", "").takeIf { it.isNotBlank() },
                        deviceId = obj.optString("device_id", "").takeIf { it.isNotBlank() },
                        castDeviceId = obj.optString("cast_device_id", "").takeIf { it.isNotBlank() },
                        trust = TvTrust(
                            temporary = obj.optBoolean("temporary", false),
                            expiresAt = obj.optString("expires_at", "").takeIf { it.isNotBlank() }?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() },
                            telegramDirect = obj.optBoolean("telegram_direct", false),
                        ),
                    )
                }
            }.getOrDefault(emptyList())
        }
        set(value) {
            val array = JSONArray()
            value.forEach { tv ->
                array.put(
                    JSONObject()
                        .put("pin", tv.pin)
                        .put("name", tv.name)
                        .put("capability", tv.capability.orEmpty())
                        .put("device_id", tv.deviceId.orEmpty())
                        .put("cast_device_id", tv.castDeviceId.orEmpty())
                        .put("temporary", tv.trust.temporary)
                        .put("expires_at", tv.trust.expiresAt?.toString().orEmpty())
                        .put("telegram_direct", tv.trust.telegramDirect),
                )
            }
            prefs.edit()
                .putString("tvs_json", array.toString())
                .putString("lan_token", value.lastOrNull()?.capability ?: value.lastOrNull()?.pin)
                .putString("tv_name", value.lastOrNull()?.name ?: "TV")
                .apply()
        }

    fun addTv(pin: String, name: String, capability: String? = null, deviceId: String? = null, trust: TvTrust = TvTrust.Home) {
        tvs = tvs.filterNot { it.pin == pin } + PairedTv(pin, name, capability, deviceId, trust)
    }

    /** Upgrade an existing pairing with the capability minted at claim time. */
    fun attachCapability(pin: String, capability: String) {
        tvs = tvs.map { if (it.pin == pin) it.copy(capability = capability) else it }
    }

    /** Record the server-side device id for this TV (used for revocation checks). */
    fun attachDeviceId(pin: String, deviceId: String) {
        tvs = tvs.map { if (it.pin == pin) it.copy(deviceId = deviceId) else it }
    }

    /** A Cast status may associate a route with an existing pairing, never create one. */
    fun attachCastDeviceId(deviceId: String, castDeviceId: String) {
        if (tvs.none { it.deviceId == deviceId }) return
        tvs = tvs.map { when {
            it.deviceId == deviceId -> it.copy(castDeviceId = castDeviceId)
            it.castDeviceId == castDeviceId -> it.copy(castDeviceId = null)
            else -> it
        } }
    }

    var castConnect: Boolean
        get() = prefs.getBoolean("cast_connect", false)
        set(value) { prefs.edit().putBoolean("cast_connect", value).apply() }

    var legacyRemote: Boolean
        get() = prefs.getBoolean("legacy_remote", true)
        set(value) { prefs.edit().putBoolean("legacy_remote", value).apply() }

    fun removeTv(pin: String) {
        tvs = tvs.filterNot { it.pin == pin }
    }

    fun clearTVs() {
        tvs = emptyList()
    }

    var adsHidden: Boolean
        get() = prefs.getBoolean("ads_hidden", false)
        set(value) { prefs.edit().putBoolean("ads_hidden", value).apply() }

    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.API_BASE_URL
        set(value) { prefs.edit().putString("api_base_url", value).apply() }

    var tunnelUrl: String
        get() = prefs.getString("tunnel_url", "") ?: ""
        set(value) { prefs.edit().putString("tunnel_url", value).apply() }

    var wifiOnlyTransfers: Boolean
        get() = prefs.getBoolean("wifi_only_transfers", true)
        set(value) { prefs.edit().putBoolean("wifi_only_transfers", value).apply() }

    var transferWhileCharging: Boolean
        get() = prefs.getBoolean("transfer_while_charging", false)
        set(value) { prefs.edit().putBoolean("transfer_while_charging", value).apply() }

    var syncLibraryToApi: Boolean
        get() = prefs.getBoolean("sync_library_to_api", true)
        set(value) { prefs.edit().putBoolean("sync_library_to_api", value).apply() }

    var importDestination: String
        get() = prefs.getString("import_destination", STORAGE_LOCAL) ?: STORAGE_LOCAL
        set(value) { prefs.edit().putString("import_destination", value).apply() }

    /** Spec 003: serving relay streams over mobile data (default: ask). */
    var relayMobileData: RelayMobileDataPolicy
        get() = runCatching { RelayMobileDataPolicy.valueOf(prefs.getString("relay_mobile_data", "Ask") ?: "Ask") }
            .getOrDefault(RelayMobileDataPolicy.Ask)
        set(value) { prefs.edit().putString("relay_mobile_data", value.name).apply() }

    /** "Allow this time": mobile-data relay is allowed until this time or until the phone leaves mobile data. */
    var relayMobileAllowedUntil: Long
        get() = prefs.getLong("relay_mobile_allowed_until", 0L)
        set(value) { prefs.edit().putLong("relay_mobile_allowed_until", value).apply() }

    var cablegramCloudReady: Boolean
        get() = prefs.getBoolean("cablegram_cloud_ready", false)
        set(value) { prefs.edit().putBoolean("cablegram_cloud_ready", value).apply() }
}
