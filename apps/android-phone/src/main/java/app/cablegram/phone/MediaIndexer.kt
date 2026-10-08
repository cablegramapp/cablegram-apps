package app.cablegram.phone

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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

enum class BrowseSource { None, Roots, Folder, Cloud }

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

    fun listTreeVideos(treeUri: Uri, limit: Int = 2_000): List<IndexedCandidate> {
        val found = mutableListOf<IndexedCandidate>()
        walk(treeUri, DocumentsContract.getTreeDocumentId(treeUri), found, limit)
        return found
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
