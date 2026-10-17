package si.sarvesh.sift

import android.app.Application
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.Closeable
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class RecoveredCopy(val file: RecoveryFile, val uri: String)

internal data class RecoveryFailure(val id: String, val error: String)

internal data class RecoveryExportResult(
    val directory: String,
    val exported: List<RecoveredCopy>,
    val failed: List<RecoveryFailure>,
    val cancelled: Boolean,
    val manifest: String?,
    val warning: String?,
)

internal data class RecoveryState(
    val report: RecoveryReport? = null,
    val busy: Boolean = false,
    val progress: RecoveryProgress? = null,
    val selected: Set<String> = emptySet(),
    val error: String? = null,
    val result: RecoveryExportResult? = null,
    val session: String = "",
    val storageId: String = "primary",
)

/** A retained, read-only document descriptor. No copying the image onto the affected storage. */
internal interface RecoveryInput : Closeable {
    val uri: Uri?
    val carver: RecoveryReader

    fun sameFile(fd: java.io.FileDescriptor): Boolean

    fun validateDestination(tree: Uri) {}
}

internal class RecoveryDocument(
    resolver: ContentResolver,
    override val uri: Uri,
    carving: Boolean = false,
) : RecoveryInput {
    private val descriptor =
        resolver.openFileDescriptor(uri, "r") ?: error("Image could not be opened.")
    private val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    private val before =
        try {
            Os.fstat(descriptor.fileDescriptor).also {
                require(OsConstants.S_ISREG(it.st_mode)) {
                    "Choose a local, seekable image file; live disks and streaming providers are unsupported."
                }
            }
        } catch (e: Exception) {
            stream.close()
            throw e
        }
    val name =
        runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }
            .getOrNull() ?: "Disk image"
    override val carver: RecoveryReader =
        try {
            if (carving) RecoveryCarver(stream.channel, name, ::unchanged)
            else NtfsRecoveryReader(descriptor.fd, name, before.st_size, ::unchanged)
        } catch (e: Exception) {
            stream.close()
            throw e
        }

    private fun unchanged() {
        val now = Os.fstat(descriptor.fileDescriptor)
        check(
            now.st_dev == before.st_dev &&
                now.st_ino == before.st_ino &&
                now.st_size == before.st_size &&
                now.st_mtim.tv_sec == before.st_mtim.tv_sec &&
                now.st_mtim.tv_nsec == before.st_mtim.tv_nsec &&
                now.st_ctim.tv_sec == before.st_ctim.tv_sec &&
                now.st_ctim.tv_nsec == before.st_ctim.tv_nsec
        ) {
            "Image changed. Scan a stable image again."
        }
    }

    override fun sameFile(fd: java.io.FileDescriptor): Boolean {
        val stat = Os.fstat(fd)
        return stat.st_dev == before.st_dev && stat.st_ino == before.st_ino
    }

    override fun close() {
        carver.setCancelled(true)
        carver.close()
        stream.close()
    }
}

internal fun RecoveryFile.json() =
    JSONObject()
        .put("id", id)
        .put("name", name)
        .put("category", category)
        .put("format", format)
        .put("offset", offset)
        .put("size", size)
        .put("evidence", evidence)
        .put("sha256", sha256)
        .put("originalPath", originalPath)
        .put("recoverable", recoverable)
        .put("origin", origin)
        .put("width", width)
        .put("height", height)

internal class RecoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val resolver = application.contentResolver
    private val mutable = MutableStateFlow(RecoveryState())
    val state = mutable.asStateFlow()
    @Volatile private var document: RecoveryInput? = null
    @Volatile private var rootOpening: RootBlockChannel? = null
    private val stopRequested = java.util.concurrent.atomic.AtomicBoolean(false)
    private val previewSlots = Semaphore(2)
    private val cache =
        object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) =
                value.allocationByteCount + key.length * 2
        }

    private fun work(action: () -> Unit) {
        if (mutable.value.busy) return
        stopRequested.set(false)
        document?.carver?.setCancelled(false)
        mutable.update { it.copy(busy = true, error = null, progress = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: Exception) {
                mutable.update { it.copy(error = e.message ?: "Recovery could not complete.") }
            } finally {
                document?.carver?.setCancelled(false)
                mutable.update { it.copy(busy = false, progress = null) }
            }
        }
    }

    fun scan(uri: Uri, carving: Boolean = false) = work {
        document?.close()
        document = null
        cache.evictAll()
        mutable.update {
            it.copy(
                report = null,
                result = null,
                selected = emptySet(),
                session = UUID.randomUUID().toString(),
            )
        }
        val next = RecoveryDocument(resolver, uri, carving)
        document = next
        next.carver.setCancelled(stopRequested.get())
        val report =
            next.carver.scan { progress -> mutable.update { it.copy(progress = progress) } }
        mutable.update { it.copy(report = report) }
    }

    fun scanPhone() = work {
        check(android.os.Environment.isExternalStorageManager()) {
            "Allow storage access to search this phone's shared cache and hidden folders."
        }
        document?.close()
        document = null
        cache.evictAll()
        mutable.update {
            it.copy(
                report = null,
                result = null,
                selected = emptySet(),
                session = UUID.randomUUID().toString(),
            )
        }
        val source =
            storageSources(getApplication()).firstOrNull { it.id == mutable.value.storageId }
                ?: error("Choose a mounted storage device.")
        val next = PhoneRecoveryInput(source.root, source.label)
        document = next
        next.carver.setCancelled(stopRequested.get())
        val report =
            next.carver.scan { progress -> mutable.update { it.copy(progress = progress) } }
        mutable.update { it.copy(report = report) }
    }

    fun selectStorage(id: String) = work {
        check(storageSources(getApplication()).any { it.id == id }) {
            "That device is no longer mounted."
        }
        document?.close()
        document = null
        cache.evictAll()
        mutable.value = RecoveryState(busy = true, storageId = id)
    }

    fun scanRootedPhone() = work {
        document?.close()
        document = null
        cache.evictAll()
        mutable.update {
            it.copy(
                report = null,
                result = null,
                selected = emptySet(),
                session = UUID.randomUUID().toString(),
                progress = RecoveryProgress(0, 0, 0, "Waiting for root consent · read-only access"),
            )
        }
        try {
            val next =
                RootRecoveryInput(getApplication(), mutable.value.storageId) { channel ->
                    rootOpening = channel
                    if (stopRequested.get()) channel.close()
                }
            document = next
            rootOpening = null
            next.carver.setCancelled(stopRequested.get())
            val report = next.carver.scan { p -> mutable.update { it.copy(progress = p) } }
            mutable.update { it.copy(report = report) }
        } finally {
            rootOpening?.close()
            rootOpening = null
        }
    }

    fun closeImage() = work {
        document?.close()
        document = null
        cache.evictAll()
        mutable.value = RecoveryState(busy = true, storageId = mutable.value.storageId)
    }

    fun select(ids: Set<String>) {
        if (!mutable.value.busy) {
            val allowed =
                mutable.value.report?.files?.filter { it.recoverable }?.map { it.id }?.toSet()
                    ?: emptySet()
            mutable.update { it.copy(selected = ids.intersect(allowed)) }
        }
    }

    fun toggle(id: String) {
        val selected = mutable.value.selected
        select(if (id in selected) selected - id else selected + id)
    }

    fun stop() {
        stopRequested.set(true)
        rootOpening?.close()
        document?.carver?.setCancelled(true)
    }

    fun dismiss() {
        mutable.update { it.copy(error = null, result = null) }
    }

    suspend fun preview(id: String): Bitmap? =
        withContext(Dispatchers.IO) {
            previewSlots.withPermit {
                if (mutable.value.busy) return@withPermit null
                val key = "${mutable.value.session}:$id"
                cache.get(key)?.let {
                    return@withPermit it
                }
                val source = document ?: return@withPermit null
                runCatching {
                        val bytes = source.carver.previewBytes(id) ?: return@withPermit null
                        val bitmap =
                            ImageDecoder.decodeBitmap(
                                ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                            ) { decoder, info, _ ->
                                require(info.size.width in 1..32768 && info.size.height in 1..32768)
                                val scale = minOf(1f, 384f / max(info.size.width, info.size.height))
                                decoder.setTargetSize(
                                    max(1, (info.size.width * scale).roundToInt()),
                                    max(1, (info.size.height * scale).roundToInt()),
                                )
                                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            }
                        if (source !== document || mutable.value.busy) null
                        else bitmap.also { cache.put(key, it) }
                    }
                    .getOrNull()
            }
        }

    private class ExistingDestination :
        Exception("Destination was not a new empty regular file; left unchanged.")

    private fun newOutput(uri: Uri, source: RecoveryInput): ParcelFileDescriptor {
        // "rw" does not truncate. Verify the fresh descriptor before the first write,
        // including when a provider returns a different URI alias for the source.
        val fd = resolver.openFileDescriptor(uri, "rw") ?: error("Destination is not writable.")
        try {
            val stat = Os.fstat(fd.fileDescriptor)
            if (
                !OsConstants.S_ISREG(stat.st_mode) ||
                    stat.st_size != 0L ||
                    source.sameFile(fd.fileDescriptor)
            )
                throw ExistingDestination()
            return fd
        } catch (e: Exception) {
            fd.close()
            throw e
        }
    }

    fun export(tree: Uri, ids: Set<String> = mutable.value.selected) = work {
        mutable.update { it.copy(result = null) }
        val source = document ?: error("Scan an image first.")
        source.validateDestination(tree)
        val carver = source.carver
        val candidates = carver.report.files.filter { it.id in ids }
        require(ids.isNotEmpty() && candidates.size == ids.size) {
            "Select candidates from the current recovery scan."
        }
        check(!carver.cancel.get()) { "Recovery stopped." }
        val treeRoot =
            DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
        val directory =
            DocumentsContract.createDocument(
                resolver,
                treeRoot,
                DocumentsContract.Document.MIME_TYPE_DIR,
                "Sift-recovered-${UUID.randomUUID()}",
            ) ?: error("Destination provider could not create a recovery folder.")
        val folders = mutableMapOf<String, Uri>()
        val exported = ArrayList<RecoveredCopy>()
        val failed = ArrayList<RecoveryFailure>()
        val total = candidates.sumOf { it.size }
        var copied = 0L
        for (file in candidates) {
            if (carver.cancel.get()) break
            var output: Uri? = null
            try {
                val folder =
                    folders.getOrPut(file.category) {
                        DocumentsContract.createDocument(
                            resolver,
                            directory,
                            DocumentsContract.Document.MIME_TYPE_DIR,
                            file.category,
                        ) ?: error("Could not create category folder.")
                    }
                val safeName =
                    file.name
                        .map { if (it.isISOControl() || it in "<>:\"/\\|?*") '_' else it }
                        .joinToString("")
                        .take(140)
                        .trimEnd(' ', '.')
                val outputName =
                    if (file.originalPath != null) "${file.id}-$safeName" else file.name
                val created =
                    DocumentsContract.createDocument(resolver, folder, file.mimeType, outputName)
                        ?: error("Could not create destination file.")
                require(created != source.uri && created != directory && created != folder) {
                    "Invalid destination document."
                }
                // Cleanup is permitted only after proving that the opened descriptor
                // is a new empty file distinct from the source. Failed opens must not
                // cause deletion through a provider-supplied source URI alias.
                val fd = newOutput(created, source)
                output = created
                var copiedHash = ""
                ParcelFileDescriptor.AutoCloseOutputStream(fd).use { stream ->
                    copiedHash =
                        carver.copy(
                            file.id,
                            { bytes, n -> stream.write(bytes, 0, n) },
                            { n ->
                                copied += n
                                mutable.update {
                                    it.copy(
                                        progress =
                                            RecoveryProgress(
                                                copied,
                                                total,
                                                exported.size,
                                                "Copying and verifying recovered files",
                                            )
                                    )
                                }
                            },
                        )
                    stream.flush()
                    fd.fileDescriptor.sync()
                }
                val digest = MessageDigest.getInstance("SHA-256")
                resolver.openInputStream(output)?.use { stream ->
                    val buffer = ByteArray(RecoveryCarver.BLOCK)
                    while (true) {
                        check(!carver.cancel.get()) { "Recovery stopped." }
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        digest.update(buffer, 0, n)
                    }
                } ?: error("Cannot read back the destination file.")
                check(digest.digest().hex() == copiedHash) {
                    "Destination read-back did not match."
                }
                exported.add(RecoveredCopy(file.copy(sha256 = copiedHash), output.toString()))
            } catch (e: Exception) {
                if (e is ExistingDestination) output = null
                val cleanup =
                    output?.let {
                        runCatching { DocumentsContract.deleteDocument(resolver, it) }
                            .getOrDefault(false)
                    } ?: true
                failed.add(
                    RecoveryFailure(
                        file.id,
                        (e.message ?: "Copy failed.") +
                            (if (cleanup) ""
                            else
                                " An incomplete destination file could not be removed; inspect the recovery folder."),
                    )
                )
            }
        }
        val manifest =
            JSONObject()
                .put("schemaVersion", 2)
                .put("requestedIds", JSONArray(ids.toList()))
                .put("method", carver.report.method)
                .put("source", carver.report.source)
                .put("sourceBytes", carver.size)
                .put(
                    "notice",
                    if (carver.report.method == "android-surviving-copies-v1")
                        "Surviving bytes from accessible shared cache/hidden folders, not deleted-sector recovery. Original deletion status is unknown. Thumbnail dimensions and source evidence recorded when available. Private Android folders and bins excluded. Source read-only; hash-checked copies read back."
                    else if (carver.report.method == "ntfs-deleted-records-v1")
                        "Deleted NTFS records; original paths are metadata evidence. Unallocated data may be overwritten or TRIMmed. Source read-only; copies hashed and destination read back. Readability is not guaranteed."
                    else
                        "Contiguous carving. Original names, folders, deleted status, and readability are unknown. Source read-only; exports SHA-256 checked and read back.",
                )
                .put("cancelled", carver.cancel.get())
                .put(
                    "exported",
                    JSONArray().also { array ->
                        exported.forEach {
                            array.put(
                                JSONObject().put("candidate", it.file.json()).put("uri", it.uri)
                            )
                        }
                    },
                )
                .put(
                    "failed",
                    JSONArray().also { array ->
                        failed.forEach {
                            array.put(JSONObject().put("id", it.id).put("error", it.error))
                        }
                    },
                )
        var manifestUri: String? = null
        var warning: String? = null
        try {
            val uri =
                DocumentsContract.createDocument(
                    resolver,
                    directory,
                    "application/json",
                    "recovery-manifest.json",
                ) ?: error("Cannot create manifest.")
            require(uri != source.uri && uri != directory) { "Invalid manifest document." }
            val fd = newOutput(uri, source)
            ParcelFileDescriptor.AutoCloseOutputStream(fd).use {
                it.write(manifest.toString(2).toByteArray())
                it.flush()
                fd.fileDescriptor.sync()
            }
            manifestUri = uri.toString()
        } catch (e: Exception) {
            warning = "Files copied, but the manifest could not be saved: ${e.message}"
        }
        val result =
            RecoveryExportResult(
                directory.toString(),
                exported,
                failed,
                carver.cancel.get(),
                manifestUri,
                warning,
            )
        mutable.update {
            it.copy(
                result = result,
                selected = it.selected - exported.map { f -> f.file.id }.toSet(),
            )
        }
    }

    override fun onCleared() {
        rootOpening?.close()
        stopRequested.set(true)
        runCatching { document?.close() }
        cache.evictAll()
        super.onCleared()
    }
}
