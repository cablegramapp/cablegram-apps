package app.cablegram.phone

// ---- Choosing and connecting a storage provider (spec 006 US1, US2) ----
// Pure functions, so the provider list and every message can be tested without a screen.

enum class ProviderState {
    /** Can be connected now. */
    Available,
    /** Another provider is connected; this one waits until it is disconnected. */
    Locked,
    /** This is the connected provider. */
    Connected,
    /** Connected before, but the provider no longer accepts the sign-in. */
    NeedsSignIn,
}

data class ProviderRow(
    val id: String,
    val name: String,
    /** What connecting takes, in one line. */
    val summary: String,
    val duration: String,
    val state: ProviderState,
    /** Shown under a row that cannot be used right now. */
    val note: String? = null,
)

const val PROVIDER_GOOGLE_DRIVE = "google_drive"
const val PROVIDER_CLOUDFLARE_R2 = "cloudflare_r2"

/** What this build knows how to connect, with the words that describe each. Others the server lists are left out. */
private fun knownProviders(resources: android.content.res.Resources) = mapOf(
    PROVIDER_GOOGLE_DRIVE to Triple("Google Drive", resources.getString(R.string.ui_sign_in_with_google_1dbcee), resources.getString(R.string.ui_about_1_minute)),
    PROVIDER_CLOUDFLARE_R2 to Triple("Cloudflare R2", resources.getString(R.string.ui_use_your_own_bucket_and_keys_1a0fdb), resources.getString(R.string.ui_about_5_minutes_for_advanced_users_1d6ff3)),
)

/** A provider's display name for messages: the server's, else this build's, else a neutral phrase. */
fun providerName(id: String?, status: StorageStatusResponse? = null, resources: android.content.res.Resources): String =
    status?.providers?.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        ?: knownProviders(resources)[id]?.first
        ?: resources.getString(R.string.ui_your_own_storage_cc541c)

/**
 * The "Your own storage" list: the providers the server is set up for, in the server's order. While one is
 * connected the rest are turned off; a connection that needs signing in again is marked on its own row.
 */
fun providerRows(status: StorageStatusResponse?, resources: android.content.res.Resources): List<ProviderRow> {
    val offered = status?.providers.orEmpty().filter { it.configured && it.id in knownProviders(resources) }
    val active = status?.connection?.takeIf { it.status == "active" }
    val broken = status?.connections.orEmpty().firstOrNull { it.status == "error" && active == null }
    return offered.map { provider ->
        val (name, summary, duration) = knownProviders(resources).getValue(provider.id)
        val label = provider.name.ifBlank { name }
        val (state, note) = when {
            active?.provider == provider.id -> ProviderState.Connected to null
            active != null -> ProviderState.Locked to resources.getString(R.string.ui_disconnect_to_switch_5d456e, providerName(active.provider, status, resources = resources))
            broken?.provider == provider.id -> ProviderState.NeedsSignIn to resources.getString(R.string.ui_sign_in_again_to_keep_using_it_3abfd0)
            else -> ProviderState.Available to null
        }
        ProviderRow(provider.id, label, summary, duration, state, note)
    }
}

/** True when the server offers at least one provider this build can connect. */
fun hasOwnStorageProvider(status: StorageStatusResponse?): Boolean =
    status?.providers.orEmpty().any { it.configured && it.id in setOf(PROVIDER_GOOGLE_DRIVE, PROVIDER_CLOUDFLARE_R2) }

/** "Google Drive · name@gmail.com · 12.4 GB free": the connected card's line. */
fun connectedSummary(connection: StorageConnection, status: StorageStatusResponse? = null, resources: android.content.res.Resources): String {
    val base = connection.displayLabel?.takeIf { it.isNotBlank() } ?: providerName(connection.provider, status, resources = resources)
    val free = freeSpace(connection.quota)?.let { resources.getString(R.string.ui_free_97e1da, formatBytes(it)) }
    return listOfNotNull(base, free).joinToString(" · ")
}

/** Free bytes when the provider reports a limit; null when unknown (R2 has no fixed quota). */
fun freeSpace(quota: StorageQuota?): Long? = quota?.limitBytes?.let { (it - quota.usedBytes).coerceAtLeast(0) }

/** Where a save goes, for the words on every save sheet: the connected provider. Saves need one (CAB-38). */
fun saveDestination(status: StorageStatusResponse?, resources: android.content.res.Resources): String {
    val active = status?.connection?.takeIf { it.status == "active" } ?: return resources.getString(R.string.ui_your_own_storage_cc541c)
    return providerName(active.provider, status, resources = resources)
}

/** The line under the destination on a save sheet. Own storage has no fixed size unless the provider reports one. */
fun destinationSpaceLine(status: StorageStatusResponse?, cablegramAvailable: Long, resources: android.content.res.Resources): String {
    val active = status?.connection?.takeIf { it.status == "active" } ?: return resources.getString(R.string.ui_available_9ffcbc, formatBytes(cablegramAvailable))
    return freeSpace(active.quota)?.let { resources.getString(R.string.ui_free_97e1da, formatBytes(it)) } ?: resources.getString(R.string.ui_in_your_own_storage_e2575f)
}

/** What `cablegram://storage?provider=…&result=…` carried. */
data class StorageReturn(val provider: String, val result: String)

/** Reads the app link Google's flow ends on. Anything else, or any value that is not a plain code, is refused. */
fun parseStorageReturn(uri: String): StorageReturn? = runCatching<StorageReturn?> {
    val parsed = java.net.URI(uri.trim())
    if (parsed.scheme != "cablegram" || parsed.rawAuthority != "storage" || !parsed.path.isNullOrEmpty() || parsed.fragment != null) return null
    val params = parsed.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to java.net.URLDecoder.decode(part.substring(i + 1), "UTF-8")
    }.toMap()
    val code = Regex("[a-z_]{1,40}")
    val provider = params["provider"]?.takeIf { code.matches(it) } ?: return null
    val result = params["result"]?.takeIf { code.matches(it) } ?: return null
    StorageReturn(provider, result)
}.getOrNull()

/**
 * The wording Google puts beside the one box that matters on its consent screen. Google lets a person leave any permission
 * unticked, and the box starts unticked for most people, so the app says which one to tick before opening Google and again if
 * it was missed.
 */
const val DRIVE_PERMISSION_TEXT = "See, edit, create, and delete only the specific Google Drive files you use with this app"

/** What the app says just before it opens Google's sign-in. */
fun googleSignInPreparation( resources: android.content.res.Resources): String =
    resources.getString(R.string.ui_google_will_show_a_box_beside_tick_it_then_tap_continu_c21e7b, DRIVE_PERMISSION_TEXT) +
        resources.getString(R.string.ui_it_only_reaches_the_files_cablegram_creates_never_the__2a9187)

/** True when the sign-in ended because the permission box was left unticked, so the screen offers to try again. */
fun signInNeedsAnotherTry(ret: StorageReturn): Boolean = ret.result == "scope_missing"

/** What to tell the owner when the sign-in ends. Never repeats text that came from the link or the provider. */
fun storageReturnMessage(ret: StorageReturn, status: StorageStatusResponse? = null, resources: android.content.res.Resources): String {
    val name = providerName(ret.provider, status, resources = resources)
    return when (ret.result) {
        "connected" -> resources.getString(R.string.ui_connected_to_6c372e, name)
        "denied" -> resources.getString(R.string.ui_not_connected_you_didn_t_allow_cablegram_to_use_fb658f, name)
        "scope_missing" -> resources.getString(R.string.ui_access_wasn_t_granted_on_google_s_screen_tick_the_box__5ed88c, name, DRIVE_PERMISSION_TEXT)
        "expired" -> resources.getString(R.string.ui_the_sign_in_expired_start_again_683df5)
        "already_connected" -> resources.getString(R.string.ui_your_storage_is_already_connected_17b55e)
        else -> resources.getString(R.string.ui_couldn_t_connect_try_again_in_a_moment_ed1a38, name)
    }
}

/** How `POST /api/storage/connect/google` ended: [authorizeUrl] to open, or the server's stable [error]. */
data class GoogleConnectStart(val authorizeUrl: String?, val error: String?)

/** What to tell the owner when the app could not even start the sign-in. */
fun googleStartMessage(error: String, resources: android.content.res.Resources): String = when (error) {
    "provider_not_configured" -> resources.getString(R.string.ui_google_drive_isn_t_set_up_on_this_server_7692c7)
    "storage_already_connected" -> resources.getString(R.string.ui_your_storage_is_already_connected_17b55e)
    "offline" -> resources.getString(R.string.ui_couldn_t_reach_cablegram_check_your_connection_3c4a29)
    else -> resources.getString(R.string.ui_couldn_t_start_the_google_sign_in_1730f7)
}

/** Words for a disconnect, naming where the files stay. */
fun disconnectMessage(provider: String?, status: StorageStatusResponse? = null, resources: android.content.res.Resources): String = when (provider) {
    PROVIDER_GOOGLE_DRIVE -> resources.getString(R.string.ui_google_drive_disconnected_your_files_stay_in_your_driv_d0a3b7)
    PROVIDER_CLOUDFLARE_R2 -> resources.getString(R.string.ui_storage_disconnected_your_files_stay_in_the_bucket_del_cc7dcf)
    else -> resources.getString(R.string.ui_disconnected_your_files_stay_where_they_are_fb9447, providerName(provider, status, resources = resources).replaceFirstChar { it.uppercase() })
}

/**
 * Where to fetch this phone's own cloud copy back from (spec 006). [headers] is empty for a presigned URL (R2) and carries
 * the bearer token for Google Drive. Never logged: [toString] leaves both out.
 */
data class ReadUrl(val url: String, val headers: Map<String, String>) {
    override fun toString() = "ReadUrl(headers=${headers.size})"
}

/** Reads the control plane's `read-url` answer; null when it has no usable HTTPS URL. */
fun parseReadUrl(body: String): ReadUrl? = runCatching<ReadUrl?> {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: return null
    val url = (obj["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.startsWith("https://") } ?: return null
    val headers = (obj["headers"] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (name, value) ->
        val v = (value as? kotlinx.serialization.json.JsonPrimitive)?.content
        // Only plain header names and single-line values are sent.
        if (v != null && name.matches(Regex("[A-Za-z0-9-]+")) && !v.contains('\n') && !v.contains('\r')) name to v else null
    }?.toMap().orEmpty()
    ReadUrl(url, headers)
}.getOrNull()

/** What to tell the owner when downloading a cloud copy back fails. Never repeats provider text. */
fun cloudDownloadMessage(code: Int): String = when (code) {
    401 -> "Your cloud storage needs you to sign in again. Open Storage and sign in."
    403, 429 -> "Your cloud storage is limiting downloads of this video. Try again in a little while."
    404 -> "This video is no longer in your cloud storage."
    else -> "Your cloud storage didn't send the video ($code)."
}

/** How `DELETE /api/storage/sources/:id` ended: [error] is null on success, else the server's stable error string. */
data class RemoveCopyResult(val error: String?)

/** What the confirmation says before a cloud copy is deleted. */
fun removeCopyQuestion(title: String, destination: String): String =
    "This deletes the copy of \"$title\" from $destination. The video stays on this phone, and you can save it again later."

/** What to tell the owner when removing a cloud copy ends. Never repeats provider text. */
fun removeCopyMessage(result: RemoveCopyResult, destination: String): String = when (result.error) {
    null, "not_found" -> "Removed the copy from $destination. The video stays on this phone."
    "reauthorization_required" -> "$destination needs you to sign in again before it can remove files. Open Storage and sign in."
    "storage_unavailable" -> "$destination isn't answering. Try again later."
    "provider_rate_limited" -> "$destination is limiting requests right now. Try again in a little while."
    "offline" -> "Couldn't reach Cablegram. Check your connection."
    else -> "Couldn't remove the copy from $destination."
}
