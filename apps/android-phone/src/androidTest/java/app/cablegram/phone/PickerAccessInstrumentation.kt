package app.cablegram.phone

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Run explicitly with CABLEGRAM_TEST_RUNNER on the isolated CAB-32 emulator package. */
class PickerAccessInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.packageName.endsWith(".cab32"))
            val resolver = targetContext.contentResolver
            val store = LibraryStore(targetContext)
            if (arguments.getString("folderCheck") == "true") {
                val folders = store.folders()
                check(folders.size == arguments.getString("folders", "2").toInt())
                check(store.list().filter { it.sourceUri != null && !it.householdOnly }.size == 2)
                val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }
                folders.forEach { folder ->
                    val tree = Uri.parse(folder.uri)
                    check(grants.any { it.uri == tree })
                    val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                    check(resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { true } == true)
                }
                arguments.getString("removedTree")?.let { value ->
                    val tree = Uri.parse(value)
                    check(folders.none { it.uri == value })
                    check(resolver.persistedUriPermissions.none { it.uri == tree })
                    val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
                    var denied = false
                    try {
                        resolver.query(children, null, null, null, null)?.close()
                    } catch (_: SecurityException) {
                        denied = true
                    }
                    check(denied)
                }
                result.putString("result", "PASS: selected folder count=" + folders.size + "; selected folders readable; removed tree checked=" + arguments.containsKey("removedTree") + "; library entries retained")
                finish(android.app.Activity.RESULT_OK, result)
                return
            }
            val items = store.list().filter { it.sourceUri != null && !it.householdOnly }
            check(items.size == arguments.getString("count", "2").toInt())
            check(items.none { it.filename == "UnselectedClip.mp4" })
            val grants = resolver.persistedUriPermissions.filter { it.isReadPermission }
            check(grants.isNotEmpty())
            items.forEach { item ->
                check(store.hasSource(item))
                store.openPfd(item)!!.use { descriptor ->
                    check(descriptor.statSize == 31_908L)
                    java.io.FileInputStream(descriptor.fileDescriptor).use { input ->
                        val header = ByteArray(12)
                        check(input.read(header) == 12)
                        check(String(header, 4, 4, Charsets.US_ASCII) == "ftyp")
                    }
                }
            }
            val control = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FUnselectedClip.mp4")
            check(runCatching { resolver.openFileDescriptor(control, "r")?.use { true } ?: false }.getOrDefault(false).not())
            check(store.selectedVideosByFingerprint().values.none { it.name == "UnselectedClip.mp4" })
            val folders = store.folders()
            folders.forEach { folder -> check(grants.any { it.uri.toString() == folder.uri }) }
            if (arguments.getString("revokeFolder") == "true") {
                val folder = folders.single()
                resolver.releasePersistableUriPermission(Uri.parse(folder.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            result.putString("result", "PASS: selected bytes readable, persisted grants, unselected control denied; folder revoked=" + arguments.getString("revokeFolder", "false"))
            finish(android.app.Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", "FAIL: " + error.javaClass.simpleName + "; " + error.stackTrace.firstOrNull { it.className == PickerAccessInstrumentation::class.java.name })
            finish(android.app.Activity.RESULT_CANCELED, result)
        }
    }
}
