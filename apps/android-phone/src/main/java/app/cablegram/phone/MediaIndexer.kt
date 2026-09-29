package app.cablegram.phone

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns

data class IndexedCandidate(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val durationSeconds: Int? = null,
)

data class BrowseEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    val isVideo: Boolean,
    val sizeBytes: Long? = null,
    val mime: String = "",
    val uri: Uri? = null,
    val documentId: String? = null,
    val mediaPath: String? = null,
    val treeUri: Uri? = null,
)

data class BrowseCrumb(
    val name: String,
    val documentId: String? = null,
    val mediaPath: String? = null,
)

enum class BrowseSource { None, Roots, Folder, Phone, Cloud }

enum class StorageRootKind { Internal, Attached, Cloud }

data class StorageRoot(
    val id: String,
    val name: String,
    val kind: StorageRootKind,
    val detail: String,
    val volumeName: String? = null,
    val cloudKey: String? = null,
)

class MediaIndexer(private val context: Context) {
    fun listSafChildren(treeUri: Uri, documentId: String): List<BrowseEntry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val out = mutableListOf<BrowseEntry>()
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idCol) ?: continue
                val mime = cursor.getString(mimeCol).orEmpty()
                val name = cursor.getString(nameCol) ?: "file"
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR ||
                    mime.equals("resource/folder", ignoreCase = true) ||
                    mime.endsWith("/directory")
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                val isVideo = !isDir && isVideoFile(name, mime)
                out += BrowseEntry(
                    id = uri.toString(),
                    name = name,
                    isDirectory = isDir,
                    isVideo = isVideo,
                    sizeBytes = if (!isDir && sizeCol >= 0) cursor.getLong(sizeCol).takeIf { it > 0 } else null,
                    mime = mime,
                    uri = uri,
                    documentId = id,
                    treeUri = treeUri,
                )
            }
        }
        return out
    }

    fun listStorageVolumes(): List<StorageRoot> {
        val manager = context.getSystemService(android.os.storage.StorageManager::class.java) ?: return emptyList()
        val mediaNames = if (android.os.Build.VERSION.SDK_INT >= 29) {
            MediaStore.getExternalVolumeNames(context)
        } else {
            emptySet()
        }
        return manager.storageVolumes.map { volume ->
            val mediaName = when {
                android.os.Build.VERSION.SDK_INT >= 29 && volume.isPrimary -> MediaStore.VOLUME_EXTERNAL_PRIMARY
                volume.uuid != null -> mediaNames.firstOrNull { it.equals(volume.uuid, ignoreCase = true) } ?: volume.uuid
                else -> null
            }
            StorageRoot(
                id = "vol:${volume.uuid ?: "primary"}",
                name = if (volume.isPrimary) "Internal storage" else volume.getDescription(context).ifBlank { "Attached storage" },
                kind = if (volume.isPrimary) StorageRootKind.Internal else StorageRootKind.Attached,
                detail = when {
                    volume.isPrimary -> "This phone"
                    volume.isRemovable -> "SD card or USB"
                    else -> "Attached storage"
                },
                volumeName = mediaName,
            )
        }
    }

    fun listMediaStoreBrowse(pathPrefix: String, videosOnly: Boolean = true, volumeName: String? = null): List<BrowseEntry> {
        val resolver = context.contentResolver
        val useFiles = !videosOnly
        val collection = mediaCollection(useFiles, volumeName)
        val pathColumn = if (android.os.Build.VERSION.SDK_INT >= 29) {
            if (useFiles) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Video.Media.RELATIVE_PATH
        } else {
            null
        }
        val mimeColumn = if (useFiles) MediaStore.Files.FileColumns.MIME_TYPE else null
        val mediaTypeColumn = if (useFiles) MediaStore.Files.FileColumns.MEDIA_TYPE else null
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE)
            if (pathColumn != null) add(pathColumn)
            if (mimeColumn != null) add(mimeColumn)
            if (mediaTypeColumn != null) add(mediaTypeColumn)
        }.toTypedArray()
        val folders = linkedMapOf<String, BrowseEntry>()
        val files = mutableListOf<BrowseEntry>()
        val prefix = pathPrefix.trim('/')
        resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DISPLAY_NAME} ASC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val pathCol = pathColumn?.let { cursor.getColumnIndex(it) } ?: -1
            val mimeCol = mimeColumn?.let { cursor.getColumnIndex(it) } ?: -1
            val typeCol = mediaTypeColumn?.let { cursor.getColumnIndex(it) } ?: -1
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""
                val mediaType = if (typeCol >= 0) cursor.getInt(typeCol) else MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val isVideo = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                    isVideoFile(name, mime)
                if (videosOnly && !isVideo) continue
                val relative = if (pathCol >= 0) cursor.getString(pathCol).orEmpty().trim('/') else ""
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                when {
                    prefix.isEmpty() && relative.isEmpty() -> {
                        files += fileEntry(uri, name, cursor.getLong(sizeCol), isVideo, mime)
                    }
                    prefix.isEmpty() && relative.isNotEmpty() -> {
                        val folder = relative.substringBefore('/')
                        if (!isHiddenBrowseName(folder)) folders.putIfAbsent(folder, dirEntry(folder, folder))
                    }
                    relative == prefix -> {
                        files += fileEntry(uri, name, cursor.getLong(sizeCol), isVideo, mime)
                    }
                    prefix.isNotEmpty() && relative.startsWith("$prefix/") -> {
                        val rest = relative.removePrefix("$prefix/")
                        val folder = rest.substringBefore('/')
                        val childPath = "$prefix/$folder"
                        folders.putIfAbsent(childPath, dirEntry(folder, childPath))
                    }
                }
            }
        }
        return folders.values.toList() + files
    }

    fun rememberedFolderEntry(folder: IndexedFolder): BrowseEntry? {
        val uri = Uri.parse(folder.uri)
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return BrowseEntry(
            id = "saf:${folder.uri}",
            name = folder.name,
            isDirectory = true,
            isVideo = false,
            uri = uri,
            documentId = documentId,
            treeUri = uri,
        )
    }

    private fun dirEntry(name: String, mediaPath: String) = BrowseEntry(
        id = "media-dir:$mediaPath",
        name = name,
        isDirectory = true,
        isVideo = false,
        mediaPath = mediaPath,
    )

    private fun fileEntry(uri: Uri, name: String, size: Long, isVideo: Boolean, mime: String) = BrowseEntry(
        id = uri.toString(),
        name = name,
        isDirectory = false,
        isVideo = isVideo,
        sizeBytes = size.takeIf { it > 0 },
        mime = mime.ifBlank { if (isVideo) "video/*" else "application/octet-stream" },
        uri = uri,
    )

    private fun mediaCollection(useFiles: Boolean, volumeName: String?): android.net.Uri {
        val named = !volumeName.isNullOrBlank() && android.os.Build.VERSION.SDK_INT >= 29
        return when {
            named && useFiles -> MediaStore.Files.getContentUri(volumeName)
            named -> MediaStore.Video.Media.getContentUri(volumeName)
            useFiles -> MediaStore.Files.getContentUri("external")
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
    }

    fun listTreeVideos(treeUri: Uri, limit: Int = 2_000): List<IndexedCandidate> {
        val found = mutableListOf<IndexedCandidate>()
        walk(treeUri, DocumentsContract.getTreeDocumentId(treeUri), found, limit)
        return found
    }

    fun listMediaStoreVideos(limit: Int = 2_000): List<IndexedCandidate> {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
        )
        val out = mutableListOf<IndexedCandidate>()
        resolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: "video.mp4"
                if (!looksLikeVideo(name)) continue
                val durationMs = cursor.getLong(durCol)
                out += IndexedCandidate(
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = name,
                    sizeBytes = cursor.getLong(sizeCol).takeIf { it > 0 },
                    durationSeconds = if (durationMs > 0) (durationMs / 1000L).toInt() else null,
                )
            }
        }
        return out
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: "video.mp4"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    }

    fun sizeBytes(uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0) return cursor.getLong(index).takeIf { it > 0 }
            }
        }
        return null
    }

    private fun walk(treeUri: Uri, documentId: String, out: MutableList<IndexedCandidate>, limit: Int) {
        if (out.size >= limit) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getString(idCol) ?: continue
                val mime = cursor.getString(mimeCol).orEmpty()
                val name = cursor.getString(nameCol) ?: "video"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(treeUri, id, out, limit)
                    continue
                }
                if (mime.startsWith("video/") || looksLikeVideo(name)) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += IndexedCandidate(
                        uri = uri,
                        displayName = name,
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol).takeIf { it > 0 } else null,
                    )
                }
            }
        }
    }

    companion object {
        fun looksLikeVideo(name: String): Boolean =
            name.contains('.') && name.substringAfterLast('.').lowercase() in VIDEO_EXTS

        fun isVideoFile(name: String, mime: String): Boolean {
            val type = mime.lowercase()
            if (type.startsWith("video/") || type == "application/vnd.rn-realmedia") return true
            return looksLikeVideo(name)
        }
    }
}

val VIDEO_EXTS = setOf(
    "mp4", "mkv", "mov", "avi", "m4v", "webm", "ts", "m2ts", "wmv", "flv", "mpeg", "mpg", "3gp", "ogv",
)
