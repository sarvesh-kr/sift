package si.sarvesh.sift

import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File

/** Synthetic fixtures only, debug APK only. Never exposes real user files. */
class RecoveryTestProvider : DocumentsProvider() {
    private val root
        get() =
            File(requireNotNull(context).filesDir, "recovery-provider-fixtures")
                .apply { mkdirs() }
                .canonicalFile

    private var corruptReadback = false
    private var aliasSource = false
    private var aliasReadOnly = false

    private fun file(id: String): File {
        val result = if (id == "root") root else File(root, id).canonicalFile
        require(result.toPath().startsWith(root.toPath()))
        return result
    }

    private fun id(file: File) =
        if (file.canonicalFile == root) "root"
        else root.toPath().relativize(file.canonicalFile.toPath()).toString()

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method.startsWith("sift.test.")) {
            check(Binder.getCallingUid() == Process.myUid())
            if (method == "sift.test.reset") {
                root.deleteRecursively()
                root.mkdirs()
                requireNotNull(context).assets.open("sample.img").use { input ->
                    File(root, "sample.img").outputStream().use { input.copyTo(it) }
                }
                File(root, "destination").mkdir()
                corruptReadback = false
                aliasSource = false
                aliasReadOnly = false
            }
            if (method == "sift.test.source-alias") aliasSource = true
            if (method == "sift.test.source-alias-read-only") {
                aliasSource = true
                aliasReadOnly = true
            }
            if (method == "sift.test.corrupt-readback") corruptReadback = true
            return Bundle()
        }
        return super.call(method, arg, extras)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val columns =
            projection
                ?: arrayOf(
                    Root.COLUMN_ROOT_ID,
                    Root.COLUMN_DOCUMENT_ID,
                    Root.COLUMN_TITLE,
                    Root.COLUMN_FLAGS,
                    Root.COLUMN_MIME_TYPES,
                )
        return MatrixCursor(columns).also { cursor ->
            cursor.newRow().apply {
                columns.forEach { column ->
                    add(
                        when (column) {
                            Root.COLUMN_ROOT_ID -> "fixtures"
                            Root.COLUMN_DOCUMENT_ID -> "root"
                            Root.COLUMN_TITLE -> "Sift disposable test fixtures"
                            Root.COLUMN_FLAGS -> Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY
                            Root.COLUMN_MIME_TYPES -> "*/*"
                            else -> null
                        }
                    )
                }
            }
        }
    }

    private fun columns(projection: Array<out String>?) =
        projection
            ?: arrayOf(
                Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE,
                Document.COLUMN_FLAGS,
            )

    private fun row(cursor: MatrixCursor, file: File) {
        cursor.newRow().apply {
            cursor.columnNames.forEach { column ->
                add(
                    when (column) {
                        Document.COLUMN_DOCUMENT_ID -> id(file)
                        Document.COLUMN_DISPLAY_NAME -> file.name
                        Document.COLUMN_MIME_TYPE ->
                            if (file.isDirectory) Document.MIME_TYPE_DIR
                            else "application/octet-stream"
                        Document.COLUMN_SIZE -> file.length()
                        Document.COLUMN_FLAGS ->
                            if (file.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE
                            else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
                        Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        else -> null
                    }
                )
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?) =
        MatrixCursor(columns(projection)).also { row(it, file(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ) =
        MatrixCursor(columns(projection)).also { cursor ->
            file(parentDocumentId).listFiles()?.forEach { row(cursor, it) }
        }

    override fun isChildDocument(parentDocumentId: String, documentId: String) =
        file(documentId).toPath().startsWith(file(parentDocumentId).toPath())

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = file(documentId)
        if (aliasReadOnly && documentId == "sample.img" && mode != "r")
            throw java.io.FileNotFoundException("Synthetic source is read-only.")
        if (
            corruptReadback &&
                mode == "r" &&
                documentId.startsWith("destination/") &&
                file.extension != "json"
        )
            file.writeText("CORRUPTED TEST COPY")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        require(
            displayName.isNotBlank() &&
                !displayName.contains('/') &&
                !displayName.contains('\\') &&
                displayName != ".."
        )
        if (aliasSource && displayName.startsWith("recovered-")) return "sample.img"
        val parent = file(parentDocumentId)
        require(parent.isDirectory)
        val file = File(parent, displayName)
        check(if (mimeType == Document.MIME_TYPE_DIR) file.mkdir() else file.createNewFile()) {
            "Test output already exists."
        }
        return id(file)
    }

    override fun deleteDocument(documentId: String) {
        val file = file(documentId)
        check(file != root && file.delete())
    }
}
