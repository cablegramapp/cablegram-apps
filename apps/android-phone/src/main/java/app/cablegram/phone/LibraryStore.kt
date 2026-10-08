package app.cablegram.phone

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.os.StatFs
import android.system.Os
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class LibraryStore private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: LibraryStore? = null

        operator fun invoke(context: Context): LibraryStore = instance ?: synchronized(this) {
            instance ?: LibraryStore(context.applicationContext).also { instance = it }
        }
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root: File get() = File(context.filesDir, "library").apply { mkdirs() }
    private val videosDir: File get() = File(root, "videos").apply { mkdirs() }
    private val postersDir: File get() = File(root, "posters").apply { mkdirs() }
    private val cloudDir: File get() = File(root, "cloud").apply { mkdirs() }
    private val indexFile: File get() = File(root, "index.json")

    /**
     * Goes up on every write to the library index. The screen and CloudTransferService share this instance (one
     * process), so the screen can redraw when the service finishes a save it never saw running (CAB-51).
     */
    private val writes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = writes.asStateFlow()

    @Synchronized
    fun snapshot(): LibraryFile {
        val atomic = android.util.AtomicFile(indexFile)
        return try {
            atomic.openRead().bufferedReader().use { json.decodeFromString<LibraryFile>(it.readText()) }
        } catch (_: java.io.FileNotFoundException) {
            LibraryFile()
        }
    }

    @Synchronized
    fun list(): List<LibraryItem> = snapshot().items

    @Synchronized
    fun collections(): List<UserCollection> = snapshot().collections

    @Synchronized
    fun folders(): List<IndexedFolder> = snapshot().folders

    @Synchronized
    fun queueWebImport(url: String, caption: String?): PendingWebImport {
        val pending = PendingWebImport(UUID.randomUUID().toString(), url, caption, Instant.now().toString())
        val file = snapshot()
        save(file.copy(pendingWebImports = file.pendingWebImports + pending))
        return pending
    }

    @Synchronized
    fun completeWebImport(id: String) {
        val file = snapshot()
        save(file.copy(pendingWebImports = file.pendingWebImports.filterNot { it.id == id }))
    }

    @Synchronized
    fun failWebImport(id: String, error: String) {
        val file = snapshot()
        save(file.copy(pendingWebImports = file.pendingWebImports.map { if (it.id == id) it.copy(lastError = error) else it }))
    }

    @Synchronized
    fun get(id: String): LibraryItem? = list().firstOrNull { it.id == id }

    fun videoFile(item: LibraryItem): File = File(videosDir, item.fileName)

    fun cloudFile(item: LibraryItem): File = File(cloudDir, item.fileName)

    fun posterFile(item: LibraryItem): File? = item.posterPath?.let { File(it) }?.takeIf { it.exists() }

    fun hasLocalBytes(item: LibraryItem): Boolean = videoFile(item).exists()

    /** Checks the original phone source without copying it into Cablegram storage. */
    fun hasSource(item: LibraryItem): Boolean {
        if (hasLocalBytes(item)) return true
        val uri = item.sourceUri?.let(Uri::parse) ?: return false
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
    }

    /**
     * Flow 5: stable source fingerprint = name + size + observed length/
     * last-modified of the original source. Survives app reinstall because it
     * does not depend on internal library ids.
     */
    fun sourceFingerprint(item: LibraryItem): String? {
        val size = item.fileSizeBytes ?: return null
        var mtime = 0L
        val local = videoFile(item).takeIf { it.exists() }
        if (local != null) {
            mtime = local.lastModified()
        } else {
            item.sourceUri?.let(Uri::parse)?.let { uri ->
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use {
                        // ParcelFileDescriptor.statSize confirms the reachable length.
                        if (it.statSize <= 0) return@runCatching
                        mtime = it.statSize
                    }
                }
            }
        }
        return fingerprintOf(item.filename, size, mtime)
    }

    /**
     * Same formula as [sourceFingerprint] for a picked/indexed file, whose "mtime" is its stat size.
     * Lets a reinstalled phone recognise its household titles from MediaStore without opening files.
     */
    private fun fingerprintOf(name: String, size: Long, mtime: Long): String {
        val namePart = name.lowercase().trim()
        return "sha:${((namePart.hashCode().toLong() shl 32) or (size xor (mtime shl 7))).toString(16)}"
    }

    data class DeviceVideo(val uri: String, val name: String, val size: Long)

    /** Videos on this phone keyed by fingerprint; empty without media permission. */
    fun deviceVideosByFingerprint(): Map<String, DeviceVideo> {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) return emptyMap()
        val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Video.Media._ID,
            android.provider.MediaStore.Video.Media.DISPLAY_NAME,
            android.provider.MediaStore.Video.Media.SIZE,
        )
        val found = mutableMapOf<String, DeviceVideo>()
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val size = cursor.getLong(2).takeIf { it > 0 } ?: continue
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)).toString()
                    found[fingerprintOf(name, size, size)] = DeviceVideo(uri, name, size)
                }
            }
        }
        return found
    }

    /**
     * Ties this library to a household. A library that belongs to another account is set aside
     * (never merged or synced into this household) and brought back when that account signs in.
     * Returns true when a different household's library was set aside.
     */
    @Synchronized
    fun bindHousehold(householdId: String): Boolean {
        val current = snapshot()
        if (current.householdId == householdId) return false
        if (current.householdId == null) {
            save(current.copy(householdId = householdId))
            return false
        }
        File(root, "index-${current.householdId}.json").writeText(json.encodeToString(current))
        val stash = File(root, "index-$householdId.json")
        val next = runCatching { json.decodeFromString<LibraryFile>(stash.readText()) }.getOrNull()
            ?: LibraryFile(householdId = householdId)
        save(next.copy(householdId = householdId))
        stash.delete()
        return current.items.isNotEmpty()
    }

    /**
     * The household was deleted with the account: nothing tied to it may stay or sync into a later account.
     * Videos that are files on this phone stay (they are the user's own); titles that only existed because of
     * the household (restored placeholders, Telegram channel titles) and its bookkeeping go.
     */
    @Synchronized
    fun forgetHousehold() {
        val current = snapshot()
        save(current.copy(
            householdId = null,
            dismissedRemoteIds = emptyList(),
            promptedTelegramIds = emptyList(),
            pendingWebImports = emptyList(),
            items = current.items.filterNot { it.householdOnly || it.sourceKind == "telegram" }
                .map { it.copy(telegramCopy = false) },
        ))
        File(root, "index-${current.householdId}.json").delete()
    }

    fun dismissedRemoteIds(): Set<String> = snapshot().dismissedRemoteIds.toSet()

    /** A household title without a file on this phone (restored after a reinstall / on a new phone). */
    @Synchronized
    fun restoreFromHousehold(remote: RemoteCatalogItem, source: RemoteCatalogSource): LibraryItem {
        get(remote.id)?.let { return it }
        val name = source.originFilename?.takeIf { it.isNotBlank() } ?: (remote.title ?: "Video")
        val item = LibraryItem(
            id = remote.id,
            title = remote.title?.takeIf { it.isNotBlank() } ?: name.substringBeforeLast('.'),
            filename = name,
            fileName = "${remote.id}.${name.substringAfterLast('.', "mp4")}",
            durationSeconds = remote.durationSeconds,
            genres = remote.genres,
            year = remote.year,
            overview = remote.overview,
            posterUrl = remote.posterUrl,
            mediaType = remote.mediaType ?: "movie",
            tmdbId = remote.tmdbId, seasonNumber = remote.seasonNumber, episodeNumber = remote.episodeNumber,
            importedAt = Instant.now().toString(),
            copied = false,
            sourceAvailable = false,
            storageState = STORAGE_LOCAL,
            fingerprint = source.sourceFingerprint,
            isPrivate = source.isPrivate,
            householdOnly = true,
            catalogItemId = remote.id,
            metadataRevision = remote.metadataRevision,
            userMetadataFields = remote.userMetadataFields.map { if (it == "media_type") "mediaType" else it }.toSet(),
            artworkOrigin = if (remote.posterUrl == null) ARTWORK_PLACEHOLDER else ARTWORK_CATALOG,
        )
        save(snapshot().copy(items = list() + item))
        return item
    }

    /** A video in the household's Telegram channel: listed on the phone, played by the TV from Telegram. */
    @Synchronized
    fun registerTelegram(remote: RemoteCatalogItem, source: RemoteCatalogSource): LibraryItem {
        val name = source.originFilename?.takeIf { it.isNotBlank() } ?: (remote.title ?: "Video")
        val existing = get(remote.id)
        val item = (existing ?: LibraryItem(
            id = remote.id,
            title = name.substringBeforeLast('.'),
            filename = name,
            fileName = remote.id,
            importedAt = Instant.now().toString(),
            copied = false,
            storageState = STORAGE_CLOUD,
            sourceKind = "telegram",
            householdOnly = false,
            artworkOrigin = if (remote.posterUrl == null) ARTWORK_PLACEHOLDER else ARTWORK_CATALOG,
        )).copy(
            durationSeconds = remote.durationSeconds ?: existing?.durationSeconds,
            genres = remote.genres,
            tmdbId = remote.tmdbId, seasonNumber = remote.seasonNumber, episodeNumber = remote.episodeNumber,
            posterUrl = remote.posterUrl ?: existing?.posterUrl,
            sourceAvailable = source.availability != "unavailable",
            telegramFileKey = source.stableSourceKey?.takeIf { it.startsWith("tgfile:") } ?: existing?.telegramFileKey,
        )
        val merged = mergeManualMetadata(item, remote)
        if (existing == null) save(snapshot().copy(items = list() + merged)) else update(merged)
        return merged
    }

    fun promptedTelegramIds(): Set<String> = snapshot().promptedTelegramIds.toSet()

    @Synchronized
    fun markTelegramPrompted(id: String) {
        val current = snapshot()
        if (id !in current.promptedTelegramIds) save(current.copy(promptedTelegramIds = current.promptedTelegramIds + id))
    }

    /** Telegram titles that left the household catalog (hidden or deleted on another device) leave this list too. */
    @Synchronized
    fun pruneTelegram(remoteIds: Set<String>) {
        val current = snapshot()
        val kept = current.items.filterNot { it.sourceKind == "telegram" && it.id !in remoteIds }
        if (kept.size != current.items.size) save(current.copy(items = kept))
    }

    /** Points a restored title at the matching file found on this phone. */
    @Synchronized
    fun relink(id: String, video: DeviceVideo): LibraryItem? {
        val item = get(id) ?: return null
        val linked = item.copy(
            sourceUri = video.uri,
            filename = video.name,
            fileSizeBytes = video.size,
            sourceAvailable = true,
            householdOnly = false,
        )
        update(linked)
        return linked
    }

    fun hasCloudBytes(item: LibraryItem): Boolean = cloudFile(item).exists()

    fun openPfd(item: LibraryItem): ParcelFileDescriptor? {
        val file = videoFile(item).takeIf { it.exists() } ?: cloudFile(item).takeIf { it.exists() }
        if (file != null) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val uri = item.sourceUri ?: return null
        return runCatching { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") }.getOrNull()
    }

    fun videoLength(item: LibraryItem): Long {
        videoFile(item).takeIf { it.exists() }?.let { return it.length() }
        cloudFile(item).takeIf { it.exists() }?.let { return it.length() }
        return item.fileSizeBytes ?: 0L
    }

    /** A file that must be removed after use (see [telegramUploadFile]). */
    class UploadFile(val file: File, val temporary: Boolean) {
        fun cleanUp() { if (temporary) runCatching { file.delete() } }
    }

    /**
     * The file TDLib uploads for "Save to Telegram": it needs a real path named like the video (Telegram shows
     * that name). A copied video is linked under its own name when the system allows, otherwise uploaded as
     * stored; a video indexed from other storage is copied into the cache first, which needs free space
     * for a second copy and is deleted afterwards.
     */
    /** The file name Telegram shows for this title's upload; the same every time, so a repeat can find it. */
    fun telegramUploadName(item: LibraryItem): String {
        val safeName = item.filename.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "video.mp4" }
        return "${item.id.take(8)}-$safeName"
    }

    /**
     * Every name an upload of [item] can carry in Telegram: the prepared name, or the file's own name when the
     * phone could not link it under the prepared name and uploaded the original (review fix 10).
     */
    fun telegramUploadNames(item: LibraryItem): Set<String> = setOf(telegramUploadName(item), videoFile(item).name)

    fun telegramUploadFile(item: LibraryItem): UploadFile {
        val dir = File(context.cacheDir, "telegram-upload").apply { mkdirs() }
        val target = File(dir, telegramUploadName(item))
        target.delete()
        val local = videoFile(item).takeIf { it.exists() }
        if (local != null) {
            val linked = runCatching { java.nio.file.Files.createSymbolicLink(target.toPath(), local.toPath()); target }.getOrNull()
            return if (linked != null) UploadFile(linked, temporary = true) else UploadFile(local, temporary = false)
        }
        val size = item.fileSizeBytes ?: 0L
        val free = StatFs(dir.absolutePath).availableBytes
        check(size <= 0 || free > size + 64L * 1024 * 1024) { "Not enough free space on the phone to prepare the upload." }
        val pfd = openPfd(item) ?: error("Cablegram can't open this video any more.")
        pfd.use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return UploadFile(target, temporary = true)
    }

    /** Files being copied in right now (by stored name), which the sweep must leave alone. */
    private val importing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Ids of shared imports running in this process, which a resume must not start a second time. */
    private val importingIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Synchronized
    private fun queueSharedImport(uri: Uri, displayName: String): PendingSharedImport {
        val pending = PendingSharedImport(UUID.randomUUID().toString(), uri.toString(), displayName, Instant.now().toString())
        val file = snapshot()
        save(file.copy(pendingSharedImports = file.pendingSharedImports + pending))
        return pending
    }

    @Synchronized
    private fun completeSharedImport(id: String) {
        val file = snapshot()
        save(file.copy(pendingSharedImports = file.pendingSharedImports.filterNot { it.id == id }))
    }

    /**
     * Records the import before copying, so a process death mid-copy is picked up by
     * [resumeSharedImports]. A failure that is reported to the caller drops the record again.
     */
    fun importUri(uri: Uri, displayName: String, metadata: CatalogMetadata?): LibraryItem {
        val pending = queueSharedImport(uri, displayName)
        importingIds += pending.id
        return try {
            copyShared(pending, metadata)
        } finally {
            importingIds -= pending.id
            completeSharedImport(pending.id)
        }
    }

    /** Copies to `<name>.part`, renames it into place in one step, then registers it. */
    private fun copyShared(pending: PendingSharedImport, metadata: CatalogMetadata?): LibraryItem {
        val ext = pending.displayName.substringAfterLast('.', "mp4").ifBlank { "mp4" }
        val storedName = "${pending.id}.$ext"
        val dest = File(videosDir, storedName)
        val part = File(videosDir, "$storedName.part")
        importing += storedName
        try {
            context.contentResolver.openInputStream(Uri.parse(pending.uri))?.use { input ->
                part.outputStream().use { input.copyTo(it) }
            } ?: error("Could not read shared file")
            java.nio.file.Files.move(part.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return registerCopied(pending.id, dest, storedName, pending.displayName, pending.uri, metadata)
        } catch (error: Exception) {
            part.delete()
            if (get(pending.id) == null) dest.delete()
            throw error
        } finally {
            importing -= storedName
        }
    }

    /** What happened to a shared import that a previous run left unfinished. */
    sealed interface SharedImportResult {
        data class Imported(val item: LibraryItem) : SharedImportResult
        data class Dropped(val displayName: String) : SharedImportResult
    }

    /** Finishes shared imports a process death interrupted; one whose file can't be read any more is dropped. */
    fun resumeSharedImports(): List<SharedImportResult> = snapshot().pendingSharedImports.filter { it.id !in importingIds }.map { pending ->
        val registered = get(pending.id)
        val readable = registered == null && runCatching {
            context.contentResolver.openInputStream(Uri.parse(pending.uri))?.use { true } ?: false
        }.getOrDefault(false)
        when (sharedImportResume(registered != null, readable)) {
            SharedImportResume.Finish -> SharedImportResult.Imported(registered!!)
            SharedImportResume.Copy -> try {
                SharedImportResult.Imported(copyShared(pending, null))
            } catch (error: Exception) {
                PairLog.e("Resuming shared import failed", error)
                SharedImportResult.Dropped(pending.displayName)
            }
            SharedImportResume.Drop -> SharedImportResult.Dropped(pending.displayName)
        }.also { completeSharedImport(pending.id) }
    }

    /**
     * Deletes `.part` files and files in the videos folder that no library row points to (the current
     * library and any set-aside one). Does nothing if a set-aside library can't be read.
     */
    fun sweepVideos(now: Long = System.currentTimeMillis()): Int {
        val referenced = HashSet<String>()
        list().forEach { referenced += it.fileName }
        root.listFiles { file -> file.name.startsWith("index-") && file.name.endsWith(".json") }.orEmpty().forEach { stash ->
            val stashed = runCatching { json.decodeFromString<LibraryFile>(stash.readText()) }.getOrNull() ?: return 0
            stashed.items.forEach { referenced += it.fileName }
        }
        val files = videosDir.listFiles().orEmpty().filter { it.isFile }
        val doomed = orphanedVideoFiles(files.map { VideoFileInfo(it.name, it.lastModified()) }, referenced, importing.toSet(), now)
        doomed.forEach { File(videosDir, it).delete() }
        return doomed.size
    }

    @Synchronized
    fun registerWeb(response: WebImportResponse): LibraryItem {
        get(response.id)?.let { return it }
        val item = LibraryItem(
            id = response.id,
            catalogItemId = response.id,
            title = response.title,
            filename = response.canonicalUrl,
            fileName = response.id,
            durationSeconds = response.durationSeconds,
            overview = response.description,
            posterUrl = response.posterUrl,
            mediaType = "movie",
            importedAt = Instant.now().toString(),
            sourceKind = "web",
            canonicalUrl = response.canonicalUrl,
            copied = false,
            sourceAvailable = true,
            storageState = STORAGE_LOCAL,
            artworkOrigin = if (response.posterUrl == null) ARTWORK_PLACEHOLDER else ARTWORK_CATALOG,
        )
        save(snapshot().copy(items = list() + item))
        return item
    }

    @Synchronized
    fun indexExternal(
        uri: Uri,
        displayName: String,
        sizeBytes: Long?,
        durationSeconds: Int?,
    ): LibraryItem? {
        val existing = list().firstOrNull { it.sourceUri == uri.toString() }
        if (existing != null) return null
        val id = UUID.randomUUID().toString()
        val item = LibraryItem(
            id = id,
            title = firstCatalogHint(displayName) ?: displayName.substringBeforeLast('.'),
            filename = displayName,
            fileName = "$id.${displayName.substringAfterLast('.', "mp4")}",
            durationSeconds = durationSeconds,
            fileSizeBytes = sizeBytes,
            genres = listOf("Personal Videos"),
            mediaType = guessMediaType(displayName),
            importedAt = Instant.now().toString(),
            sourceUri = uri.toString(),
            copied = false,
            storageState = STORAGE_LOCAL,
            artworkOrigin = ARTWORK_PLACEHOLDER,
        )
        save(snapshot().copy(items = list() + item))
        // Indexed sources deserve the same offline artwork fallback as copied
        // files. A failure here is non-fatal: the item remains editable/playable.
        val poster = extractPoster(uri, id)
        return if (poster == null) item else item.copy(
            posterPath = poster.absolutePath,
            artworkOrigin = ARTWORK_FRAME,
        ).also(::update)
    }

    /**
     * Makes a phone copy of [item]. With [remoteUrl] (a presigned read URL for the user's own R2 bucket, spec 005)
     * the bytes come from there, which is how a title freed up from the phone comes back.
     */
    fun materialize(item: LibraryItem, remote: ReadUrl? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }): LibraryItem {
        if (videoFile(item).exists()) return item.copy(copied = true)
        val dest = videoFile(item)
        val total = item.fileSizeBytes ?: 0L
        if (remote != null) {
            val request = okhttp3.Request.Builder().url(remote.url).apply { remote.headers.forEach { (name, value) -> header(name, value) } }.build()
            okhttp3.OkHttpClient.Builder().readTimeout(2, java.util.concurrent.TimeUnit.MINUTES).build().newCall(request).execute().use { response ->
                check(response.isSuccessful) { cloudDownloadMessage(response.code) }
                val body = response.body ?: error("Your cloud storage sent an empty answer.")
                body.byteStream().use { copyWithProgress(it, dest, total.takeIf { t -> t > 0 } ?: body.contentLength(), onProgress) }
            }
        } else {
            val uri = item.sourceUri ?: error("No file to download")
            val parsed = Uri.parse(uri)
            val source = cloudFile(item).takeIf { it.exists() }
            if (source != null) {
                source.inputStream().use { copyWithProgress(it, dest, total, onProgress) }
            } else {
                context.contentResolver.openInputStream(parsed)?.use { input ->
                    copyWithProgress(input, dest, total, onProgress)
                } ?: error("Could not read that video")
            }
        }
        val duration = item.durationSeconds ?: probeDuration(dest)
        val poster = item.posterPath?.let { File(it) }?.takeIf { it.exists() } ?: extractPoster(dest, item.id)
        val updated = item.copy(
            copied = true,
            fileSizeBytes = dest.length(),
            durationSeconds = duration,
            posterPath = poster?.absolutePath ?: item.posterPath,
            resolution = item.resolution ?: probeResolution(dest),
            downloadBytes = dest.length(),
            downloadTotal = dest.length(),
            storageState = if (item.cloudObjectPresent) STORAGE_BOTH else STORAGE_LOCAL,
        )
        update(updated)
        return updated
    }

    @Synchronized
    fun applyCatalog(id: String, metadata: CatalogMetadata, posterFile: File?): LibraryItem? {
        val item = get(id) ?: return null
        val updated = item.copy(
            title = if ("title" in item.userMetadataFields) item.title else metadata.title.ifBlank { item.title },
            year = if ("year" in item.userMetadataFields) item.year else metadata.year ?: item.year,
            overview = if ("overview" in item.userMetadataFields) item.overview else metadata.overview ?: item.overview,
            genres = metadata.genres.ifEmpty { item.genres },
            mediaType = if ("mediaType" in item.userMetadataFields) item.mediaType else metadata.mediaType.ifBlank { item.mediaType },
            tmdbId = if (item.catalogIdentityUserSelected) item.tmdbId else metadata.tmdbId ?: item.tmdbId,
            seasonNumber = if (item.catalogIdentityUserSelected) item.seasonNumber else metadata.seasonNumber ?: item.seasonNumber,
            episodeNumber = if (item.catalogIdentityUserSelected) item.episodeNumber else metadata.episodeNumber ?: item.episodeNumber,
            posterPath = if (!catalogMayReplaceArtwork(item)) item.posterPath else posterFile?.absolutePath ?: item.posterPath,
            posterUrl = if (!catalogMayReplaceArtwork(item)) item.posterUrl else metadata.posterUrl ?: item.posterUrl,
            artworkOrigin = if (!catalogMayReplaceArtwork(item)) item.artworkOrigin else ARTWORK_CATALOG,
        )
        update(updated)
        return updated
    }

    fun writeCatalogPoster(id: String, bytes: ByteArray): File? {
        val current = get(id) ?: return null
        if (!catalogMayReplaceArtwork(current)) return null
        val out = File(postersDir, "$id.jpg")
        val pending = File(postersDir, "$id.jpg.pending")
        pending.writeBytes(bytes)
        if (!pending.renameTo(out)) {
            pending.copyTo(out, overwrite = true)
            pending.delete()
        }
        // Bump posterVersion: same path, new bytes — callers/Compose must reload.
        update(current.copy(posterVersion = current.posterVersion + 1, artworkOrigin = ARTWORK_CATALOG, artworkUserSelected = false))
        return out
    }

    fun setPosterFromFrame(id: String, frameFile: File): LibraryItem? {
        val item = get(id) ?: return null
        val dest = File(postersDir, "$id.jpg")
        if (frameFile.canonicalPath != dest.canonicalPath) {
            frameFile.copyTo(dest, overwrite = true)
        }
        val updated = item.copy(
            posterPath = dest.absolutePath,
            posterVersion = item.posterVersion + 1,
            artworkOrigin = ARTWORK_FRAME,
            artworkUserSelected = true,
        )
        update(updated)
        return updated
    }

    /** Temporary preview bytes are never published as the item's cover. */
    fun stageArtwork(session: Long, revision: Long, bytes: ByteArray): String {
        require(bytes.isNotEmpty())
        val directory = File(context.cacheDir, "artwork-editor-$session").apply { mkdirs() }
        val file = File(directory, "catalog-$revision.jpg")
        file.writeBytes(bytes)
        require(decodePosterBitmap(file.absolutePath) != null) { "Invalid cover image" }
        return file.absolutePath
    }

    fun discardArtworkPreviews(session: Long) {
        File(context.cacheDir, "artwork-editor-$session").deleteRecursively()
    }

    /** A versioned image plus one atomic index write keeps the old cover intact on failure. */
    @Synchronized
    fun saveArtworkDraft(draft: ArtworkDraft): LibraryItem {
        require(draft.valid)
        val current = get(draft.base.id) ?: error("Video no longer exists")
        require(!hasDuplicateEpisode(list(), draft) || draft.keepBoth) { "Episode already exists" }
        var updated = commitArtworkDraft(current, draft)
        val source = when (val choice = draft.cover) {
            CoverChoice.Keep -> null
            is CoverChoice.Frame -> File(choice.path)
            is CoverChoice.Catalog -> File(choice.path)
        }
        var committedImage: File? = null
        try {
            if (source != null) {
                require(source.exists() && decodePosterBitmap(source.absolutePath) != null) { "Cover unavailable" }
                val target = File(postersDir, "${current.id}-chosen-${UUID.randomUUID()}.jpg")
                committedImage = target
                source.copyTo(target)
                updated = updated.copy(posterPath = target.absolutePath,
                    posterUrl = (draft.cover as? CoverChoice.Catalog)?.url,
                    posterVersion = current.posterVersion + 1, artworkUserSelected = true,
                    artworkOrigin = if (draft.cover is CoverChoice.Catalog) ARTWORK_CATALOG else ARTWORK_FRAME)
            }
            update(updated)
            return updated
        } catch (error: Exception) {
            committedImage?.delete()
            throw error
        }
    }

    fun extractPreviewFrames(item: LibraryItem, count: Int = 4, sessionTag: String = "legacy"): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            val local = videoFile(item).takeIf { it.exists() }
            if (local != null) {
                retriever.setDataSource(local.absolutePath)
            } else {
                val uri = item.sourceUri?.let(Uri::parse) ?: return emptyList()
                retriever.setDataSource(context, uri)
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val paths = mutableListOf<String>()
            previewFrameTimesUs(durationMs, count).forEachIndexed { index, timeUs ->
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@forEachIndexed
                val directory = if (sessionTag == "legacy") postersDir else File(context.cacheDir, "artwork-editor-$sessionTag").apply { mkdirs() }
                val out = File(directory, "${item.id}-frame-$index.jpg")
                writeScaledJpeg(bitmap, out)
                paths += out.absolutePath
            }
            paths
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { retriever.release() }
        }
    }

    @Synchronized
    fun update(item: LibraryItem) {
        save(snapshot().copy(items = list().map { if (it.id == item.id) item else it }))
    }

    @Synchronized
    fun updateItem(id: String, transform: (LibraryItem) -> LibraryItem) {
        val file = snapshot()
        save(file.copy(items = file.items.map { if (it.id == id) transform(it) else it }))
    }

    @Synchronized
    fun removeFromLibrary(id: String) {
        val current = snapshot()
        val restored = current.items.firstOrNull { it.id == id }?.householdOnly == true
        save(current.copy(
            items = current.items.filterNot { it.id == id },
            // A removed household title must not come back on the next sync.
            dismissedRemoteIds = if (restored) (current.dismissedRemoteIds + id).distinct() else current.dismissedRemoteIds,
        ))
    }

    /** Drops a restored placeholder that a re-imported file now represents (not a user dismissal). */
    @Synchronized
    fun dropPlaceholder(id: String) {
        val current = snapshot()
        if (current.items.firstOrNull { it.id == id }?.householdOnly != true) return
        save(current.copy(items = current.items.filterNot { it.id == id }))
    }

    @Synchronized
    fun deleteFile(id: String) {
        val item = get(id) ?: return
        videoFile(item).delete()
        cloudFile(item).delete()
        posterFile(item)?.delete()
        save(snapshot().copy(items = list().filterNot { it.id == id }))
    }

    @Synchronized
    fun delete(id: String) = deleteFile(id)

    @Synchronized
    fun removeLocalCopy(id: String): LibraryItem? {
        val item = get(id) ?: return null
        if (!canRemoveLocalCopy(item)) return item
        videoFile(item).delete()
        val updated = item.copy(copied = false, storageState = STORAGE_CLOUD)
        update(updated)
        return updated
    }

    private fun hardLink(source: File, dest: File): Boolean {
        dest.delete()
        return runCatching {
            Os.link(source.absolutePath, dest.absolutePath)
            dest.exists() && dest.length() == source.length()
        }.getOrDefault(false)
    }

    fun copyWithProgress(
        input: java.io.InputStream,
        dest: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.outputStream().use { output ->
            val buffer = ByteArray(512 * 1024)
            var copied = 0L
            var lastEmit = 0L
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                output.write(buffer, 0, n)
                copied += n
                if (copied - lastEmit >= 8L * 1024 * 1024 || copied == total) {
                    lastEmit = copied
                    onProgress(copied, if (total > 0) total else copied)
                }
            }
            onProgress(copied, if (total > 0) total else copied)
        }
    }

    fun markEnhanced(id: String, newFile: File) {
        val item = get(id) ?: return
        videoFile(item).delete()
        val renamed = File(videosDir, item.fileName)
        if (newFile.absolutePath != renamed.absolutePath) newFile.copyTo(renamed, overwrite = true)
        update(item.copy(enhanced = true, fileSizeBytes = renamed.length(), copied = true))
    }

    @Synchronized
    fun addCollection(name: String): UserCollection {
        val collection = UserCollection(UUID.randomUUID().toString(), name.trim())
        save(snapshot().copy(collections = collections() + collection))
        return collection
    }

    @Synchronized
    fun deleteCollection(id: String) {
        val items = list().map { it.copy(collectionIds = it.collectionIds.filterNot { cid -> cid == id }) }
        save(snapshot().copy(items = items, collections = collections().filterNot { it.id == id }))
    }

    @Synchronized
    fun setItemCollections(itemId: String, collectionIds: List<String>) {
        val item = get(itemId) ?: return
        update(item.copy(collectionIds = collectionIds.distinct()))
    }

    @Synchronized
    fun rememberFolder(uri: String, name: String, volumeId: String? = null) {
        val folders = folders().filterNot { it.uri == uri || (volumeId != null && it.volumeId == volumeId) } +
            IndexedFolder(uri, name, volumeId)
        save(snapshot().copy(folders = folders))
    }

    fun setWatchProgress(id: String, positionSeconds: Int) {
        val item = get(id) ?: return
        update(
            item.copy(
                positionSeconds = positionSeconds.coerceAtLeast(0),
                lastWatchedAt = Instant.now().toString(),
            ),
        )
    }

    @Synchronized
    fun editMetadata(id: String, title: String, year: Int?, mediaType: String, overview: String? = get(id)?.overview) {
        val item = get(id) ?: return
        update(commitManualMetadata(item, title, year, mediaType, overview))
    }

    @Synchronized
    fun editMetadataDraft(base: LibraryItem, title: String, year: Int?, mediaType: String, overview: String?) {
        get(base.id)?.let { update(commitManualMetadataDraft(it, base, title, year, mediaType, overview)) }
    }

    @Synchronized
    fun setCatalogIdentity(id: String, catalogId: String) {
        get(id)?.let { update(it.copy(catalogItemId = catalogId)) }
    }

    @Synchronized
    fun acceptMetadata(sent: LibraryItem, result: MetadataSyncResult) {
        val current = get(sent.id) ?: return
        when (result) {
            is MetadataSyncResult.Saved -> update(acknowledgeManualMetadata(current, sent, result.item))
            is MetadataSyncResult.Conflict -> if (current.metadataEditId == sent.metadataEditId) update(current.copy(
                metadataConflict = true, metadataRevision = result.item.metadataRevision,
            ))
            MetadataSyncResult.Failed -> Unit
        }
    }

    @Synchronized
    fun mergeRemoteMetadata(id: String, remote: RemoteCatalogItem) {
        get(id)?.let { update(mergeManualMetadata(it, remote)) }
    }

    @Synchronized
    fun clearPosters() {
        postersDir.listFiles()?.forEach { it.delete() }
        save(snapshot().copy(items = list().map { it.copy(posterPath = null) }))
    }

    fun phoneStorage(): PhoneStorageStats {
        val media = libraryMediaBytes(list())
        return runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            PhoneStorageStats(total = total, free = free, used = total - free, media = media)
        }.getOrDefault(PhoneStorageStats(total = 0, free = 0, used = 0, media = media))
    }

    @Synchronized
    private fun registerCopied(
        id: String,
        dest: File,
        storedName: String,
        displayName: String,
        sourceUri: String?,
        metadata: CatalogMetadata?,
    ): LibraryItem {
        val duration = probeDuration(dest)
        val poster = extractPoster(dest, id)
        val embedded = probeEmbeddedTitle(dest)
        val item = LibraryItem(
            id = id,
            title = metadata?.title?.ifBlank { null }
                ?: firstCatalogHint(embedded, displayName)
                ?: displayName.substringBeforeLast('.'),
            filename = displayName,
            fileName = storedName,
            durationSeconds = duration,
            fileSizeBytes = dest.length(),
            genres = metadata?.genres ?: listOf("Personal Videos"),
            year = metadata?.year,
            overview = metadata?.overview,
            posterPath = poster?.absolutePath,
            artworkOrigin = if (poster != null) ARTWORK_FRAME else ARTWORK_PLACEHOLDER,
            mediaType = metadata?.mediaType ?: guessMediaType(displayName),
            importedAt = Instant.now().toString(),
            sourceUri = sourceUri,
            copied = true,
            storageState = STORAGE_LOCAL,
            resolution = probeResolution(dest),
        )
        save(snapshot().copy(items = list() + item))
        return item
    }

    @Synchronized
    private fun save(file: LibraryFile) {
        val atomic = android.util.AtomicFile(indexFile)
        val output = atomic.startWrite()
        try {
            output.write(json.encodeToString(file).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
        writes.value += 1
    }

    fun probeEmbeddedTitle(file: File): String? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
        retriever.release()
        title?.takeIf { !isWeakCatalogLabel(it) }
    }.getOrNull()

    private fun probeDuration(file: File): Int? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.release()
        ms?.let { (it / 1000L).toInt() }
    }.getOrNull()

    private fun probeResolution(file: File): String? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        retriever.release()
        if (w != null && h != null) "${w}x$h" else null
    }.getOrNull()

    private fun extractPoster(file: File, id: String): File? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        if (bitmap == null) return@runCatching null
        val out = File(postersDir, "$id.jpg")
        writeScaledJpeg(bitmap, out)
        out
    }.getOrNull()

    private fun extractPoster(uri: Uri, id: String): File? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@runCatching null
            val out = File(postersDir, "$id.jpg")
            writeScaledJpeg(bitmap, out)
            out
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun writeScaledJpeg(bitmap: Bitmap, out: File, maxEdge: Int = 480) {
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val scale = maxEdge.toFloat() / maxOf(w, h)
        val framed = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        out.outputStream().use { framed.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        if (framed !== bitmap) framed.recycle()
        bitmap.recycle()
    }

    private fun guessMediaType(name: String): String {
        val lower = name.lowercase()
        return if (Regex("""s\d{1,2}e\d{1,2}""").containsMatchIn(lower) || lower.contains("season")) "tv" else "movie"
    }
}

data class PhoneStorageStats(
    val total: Long,
    val free: Long,
    val used: Long,
    val media: Long,
)
