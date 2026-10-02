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
private val KNOWN_PROVIDERS = mapOf(
    PROVIDER_GOOGLE_DRIVE to Triple("Google Drive", "Sign in with Google", "about 1 minute"),
    PROVIDER_CLOUDFLARE_R2 to Triple("Cloudflare R2", "Use your own bucket and keys", "about 5 minutes · for advanced users"),
)

/** A provider's display name for messages: the server's, else this build's, else a neutral phrase. */
fun providerName(id: String?, status: StorageStatusResponse? = null): String =
    status?.providers?.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() }
        ?: KNOWN_PROVIDERS[id]?.first
        ?: "your own storage"

/**
 * The "Your own storage" list: the providers the server is set up for, in the server's order. While one is
 * connected the rest are turned off; a connection that needs signing in again is marked on its own row.
 */
fun providerRows(status: StorageStatusResponse?): List<ProviderRow> {
    val offered = status?.providers.orEmpty().filter { it.configured && it.id in KNOWN_PROVIDERS }
    val active = status?.connection?.takeIf { it.status == "active" }
    val broken = status?.connections.orEmpty().firstOrNull { it.status == "error" && active == null }
    return offered.map { provider ->
        val (name, summary, duration) = KNOWN_PROVIDERS.getValue(provider.id)
        val label = provider.name.ifBlank { name }
        val (state, note) = when {
            active?.provider == provider.id -> ProviderState.Connected to null
            active != null -> ProviderState.Locked to "Disconnect ${providerName(active.provider, status)} to switch"
            broken?.provider == provider.id -> ProviderState.NeedsSignIn to "Sign in again to keep using it"
            else -> ProviderState.Available to null
        }
        ProviderRow(provider.id, label, summary, duration, state, note)
    }
}

/** True when the server offers at least one provider this build can connect. */
fun hasOwnStorageProvider(status: StorageStatusResponse?): Boolean = providerRows(status).isNotEmpty()

/** "Google Drive · name@gmail.com · 12.4 GB free": the connected card's line. */
fun connectedSummary(connection: StorageConnection, status: StorageStatusResponse? = null): String {
    val base = connection.displayLabel?.takeIf { it.isNotBlank() } ?: providerName(connection.provider, status)
    val free = freeSpace(connection.quota)?.let { "${formatBytes(it)} free" }
    return listOfNotNull(base, free).joinToString(" · ")
}

/** Free bytes when the provider reports a limit; null when unknown (R2 has no fixed quota). */
fun freeSpace(quota: StorageQuota?): Long? = quota?.limitBytes?.let { (it - quota.usedBytes).coerceAtLeast(0) }

/** Where a save goes, for the words on every save sheet: the connected provider, else Cablegram Cloud. */
fun saveDestination(status: StorageStatusResponse?): String {
    val active = status?.connection?.takeIf { it.status == "active" } ?: return "Cablegram Cloud"
    return providerName(active.provider, status)
}

/** The line under the destination on a save sheet. Own storage has no fixed size unless the provider reports one. */
fun destinationSpaceLine(status: StorageStatusResponse?, cablegramAvailable: Long): String {
    val active = status?.connection?.takeIf { it.status == "active" } ?: return "${formatBytes(cablegramAvailable)} available"
    return freeSpace(active.quota)?.let { "${formatBytes(it)} free" } ?: "In your own storage"
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

/** What to tell the owner when the sign-in ends. Never repeats text that came from the link or the provider. */
fun storageReturnMessage(ret: StorageReturn, status: StorageStatusResponse? = null): String {
    val name = providerName(ret.provider, status)
    return when (ret.result) {
        "connected" -> "Connected to $name."
        "denied" -> "Not connected. You didn't allow Cablegram to use $name."
        "scope_missing" -> "$name access wasn't granted. Sign in again and leave the permission ticked."
        "expired" -> "The sign-in expired. Start again."
        "already_connected" -> "Your storage is already connected."
        else -> "Couldn't connect $name. Try again in a moment."
    }
}

/** How `POST /api/storage/connect/google` ended: [authorizeUrl] to open, or the server's stable [error]. */
data class GoogleConnectStart(val authorizeUrl: String?, val error: String?)

/** What to tell the owner when the app could not even start the sign-in. */
fun googleStartMessage(error: String): String = when (error) {
    "provider_not_configured" -> "Google Drive isn't set up on this server."
    "storage_already_connected" -> "Your storage is already connected."
    "offline" -> "Couldn't reach Cablegram. Check your connection."
    else -> "Couldn't start the Google sign-in."
}

/** Words for a disconnect, naming where the files stay. */
fun disconnectMessage(provider: String?, status: StorageStatusResponse? = null): String = when (provider) {
    PROVIDER_GOOGLE_DRIVE -> "Google Drive disconnected. Your files stay in your Drive."
    PROVIDER_CLOUDFLARE_R2 -> "Storage disconnected. Your files stay in the bucket; delete the API token in Cloudflare to remove access."
    else -> "${providerName(provider, status).replaceFirstChar { it.uppercase() }} disconnected. Your files stay where they are."
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
