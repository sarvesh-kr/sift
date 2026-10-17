package si.sarvesh.sift

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val MAX_FILES = 100_000
val categories = listOf("Videos", "Images", "Documents", "Audio", "Archives", "Applications", "Code", "Other")
fun category(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mov", "mkv", "webm", "avi", "3gp" -> "Videos"
    "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "raw", "dng" -> "Images"
    "pdf", "doc", "docx", "txt", "md", "xls", "xlsx", "ppt", "pptx", "csv", "epub" -> "Documents"
    "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus" -> "Audio"
    "zip", "rar", "7z", "tar", "gz", "bz2" -> "Archives"
    "apk", "aab", "apks", "xapk", "exe", "msi", "dmg" -> "Applications"
    "js", "ts", "kt", "java", "rs", "py", "html", "css", "json", "xml" -> "Code"
    else -> "Other"
}
fun confirmation(count: Int) = "DELETE $count ${if (count == 1) "FILE" else "FILES"}"

data class Entry(val path: String, val name: String, val size: Long, val modified: Long,
                 val device: Long, val inode: Long, val category: String = category(name), val group: String? = null)
data class Inventory(val files: List<Entry> = emptyList(), val skipped: Int = 0,
                     val truncated: Boolean = false, val cancelled: Boolean = false, val elapsedMs: Long = 0)
data class Failure(val path: String, val error: String)
data class Outcome(val removed: List<String>, val failed: List<Failure>, val verb: String)
data class TrashEntry(val id: String, val original: Entry, val stored: Entry, val trashedAt: Long)
enum class Action { TRASH, DELETE }

/** All calls are serialized by SiftViewModel. Paths always come from this session's inventory. */
class StorageEngine(private val context: Context,
                    root: File = Environment.getExternalStorageDirectory(),
                    bin: File = File(requireNotNull(context.getExternalFilesDir(null)), "SiftTrash"),
                    index: File = File(context.filesDir, "trash-index")) {
    val root = root.canonicalFile
    private val bin = bin.canonicalFile
    private val index = index.canonicalFile
    val cancel = AtomicBoolean(false)
    private var inventory = Inventory()
    var unreadableTrashRecords: Int = 0
        private set
    private val rootPath = root.canonicalFile.toPath()
    private val binPath = bin.canonicalFile.toPath()
    private val indexPath = index.canonicalFile.toPath()

    private fun checked(file: File): Entry {
        require(file.absoluteFile.toPath().normalize() == file.toPath()) { "Noncanonical path; rescan." }
        require(file.canonicalPath == file.absolutePath) { "Symbolic links are not followed." }
        val st = Os.lstat(file.path)
        require(OsConstants.S_ISREG(st.st_mode)) { "Only regular files are supported." }
        return Entry(file.path, file.name, st.st_size, file.lastModified(), st.st_dev, st.st_ino)
    }
    private fun insideRoot(file: File) {
        require(file.toPath().startsWith(rootPath) && file.toPath() != rootPath) { "Outside scanned storage." }
        val rel = rootPath.relativize(file.toPath())
        require(rel.first().toString() != "Android") { "Android app-private folders are excluded." }
        require(!file.toPath().startsWith(binPath) && !file.toPath().startsWith(indexPath)) { "Use Sift trash to manage this file." }
    }
    private fun unchanged(entry: Entry, inTrash: Boolean = false): File {
        val file = File(entry.path)
        if (inTrash) require(file.parentFile?.canonicalFile == bin.canonicalFile) { "Invalid trash path." }
        else insideRoot(file)
        val now = checked(file)
        require(now.copy(group = entry.group) == entry) { "File changed since scanning. Rescan before cleanup." }
        return file
    }
    private fun stopped() { check(!cancel.get()) { "Cleanup stopped. Completed moves remain available in Sift trash." } }
    private fun open(entry: Entry): FileInputStream {
        unchanged(entry)
        val fd = Os.open(entry.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        try {
            val st = Os.fstat(fd)
            require(st.st_dev == entry.device && st.st_ino == entry.inode && st.st_size == entry.size && OsConstants.S_ISREG(st.st_mode)) { "Opened file differs from inventory." }
            return takeReadDescriptor(fd)
        } catch (e: Exception) { if(fd.valid()) Os.close(fd); throw e }
    }
    @Synchronized fun scan(progress: (Int, String) -> Unit): Inventory {
        val start = System.nanoTime()
        val files = ArrayList<Entry>()
        val stack = ArrayDeque<File>(); stack.add(root.canonicalFile)
        var skipped = 0; var last = 0L
        while (stack.isNotEmpty() && !cancel.get() && files.size < MAX_FILES) {
            val dir = stack.removeLast()
            val children = dir.listFiles()
            if (children == null) { skipped++; continue }
            for (file in children) {
                if (cancel.get() || files.size >= MAX_FILES) break
                try {
                    insideRoot(file)
                    if (file.canonicalPath != file.absolutePath) { skipped++; continue }
                    val st = Os.lstat(file.path)
                    if (OsConstants.S_ISDIR(st.st_mode)) stack.add(file)
                    else if (OsConstants.S_ISREG(st.st_mode)) files.add(checked(file))
                    else skipped++
                } catch (_: Exception) { skipped++ }
                if (System.currentTimeMillis() - last > 150) { progress(files.size, "Indexing shared storage"); last = System.currentTimeMillis() }
            }
        }
        inventory = Inventory(files.sortedByDescending { it.size }, skipped, files.size >= MAX_FILES, cancel.get(), (System.nanoTime() - start) / 1_000_000)
        return inventory
    }
    private fun digest(entry: Entry): String {
        val md = MessageDigest.getInstance("SHA-256")
        open(entry).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) { stopped(); val count = input.read(buffer); if (count < 0) break; md.update(buffer, 0, count) }
        }
        unchanged(entry)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun streamsEqual(left: FileInputStream, right: FileInputStream, length: Long): Boolean {
        val x = ByteArray(256 * 1024); val y = ByteArray(x.size)
        var remaining = length
        while (remaining > 0) {
            stopped()
            val count = minOf(remaining, x.size.toLong()).toInt()
            fun readBlock(input: FileInputStream, buffer: ByteArray) {
                var read = 0
                while (read < count) { val n = input.read(buffer, read, count-read); check(n > 0) { "File changed during comparison." }; read += n }
            }
            readBlock(left, x); readBlock(right, y)
            for (i in 0 until count) if (x[i] != y[i]) return false
            remaining -= count
        }
        check(left.read() == -1 && right.read() == -1) { "File changed during comparison." }
        return true
    }
    private fun same(a: Entry, b: Entry): Boolean {
        if (a.size != b.size) return false
        val equal = open(a).use { left -> open(b).use { right -> streamsEqual(left, right, a.size) } }
        unchanged(a); unchanged(b)
        return equal
    }
    @Synchronized fun compare(progress: (Int, String) -> Unit): Inventory {
        val groups = mutableMapOf<String, String>()
        val candidates = duplicateCandidates(inventory.files)
        var processed = 0; var skipped = 0
        outer@ for (candidatesBySize in candidates) {
            val hashes = mutableMapOf<String, MutableList<Entry>>()
            for (entry in candidatesBySize) {
                if (cancel.get()) break@outer
                try { hashes.getOrPut(digest(entry)) { mutableListOf() }.add(entry) } catch (_: Exception) { skipped++ }
                progress(++processed, "Comparing file contents")
            }
            for ((hash, matches) in hashes) if (matches.size > 1) {
                // Hashes are only a filter: a separate byte comparison proves each group.
                val partitions = mutableListOf<MutableList<Entry>>()
                for (entry in matches) {
                    if (cancel.get()) break@outer
                    try {
                        val partition = partitions.find { same(it.first(), entry) }
                        if (partition == null) partitions.add(mutableListOf(entry)) else partition.add(entry)
                    } catch (_: Exception) { skipped++ }
                }
                partitions.filter { it.size > 1 }.forEachIndexed { i, part -> part.forEach { groups[it.path] = "$hash-$i" } }
            }
        }
        inventory = inventory.copy(files = inventory.files.map { it.copy(group = groups[it.path]) }, skipped = inventory.skipped + skipped, cancelled = cancel.get())
        return inventory
    }
    private fun validatePaths(paths: Set<String>): List<Entry> {
        require(paths.isNotEmpty()) { "Select at least one file." }
        val lookup = inventory.files.associateBy { it.path }
        require(paths.all { it in lookup }) { "Unscanned file in selection. Nothing was changed." }
        return paths.map { lookup.getValue(it) }
    }
    private fun keeper(entry: Entry, selected: Set<String>) {
        val group = entry.group ?: return
        val retained = inventory.files.firstOrNull { it.group == group && it.path !in selected }
            ?: error("Keep at least one duplicate copy, or explicitly acknowledge deleting all copies.")
        require(same(entry, retained)) { "Retained copy changed. Compare files again." }
    }
    @Synchronized fun cleanup(paths: Set<String>, action: Action, phrase: String, allowAllCopies: Boolean,
                              progress: (Int, String) -> Unit): Outcome {
        val entries = validatePaths(paths)
        if (action == Action.DELETE) require(phrase == confirmation(entries.size)) { "Typed confirmation does not match the current selection." }
        if (action == Action.TRASH) require(!allowAllCopies) { "Sift trash always retains a duplicate copy." }
        val removed = mutableListOf<String>(); val failed = mutableListOf<Failure>()
        for ((i, entry) in entries.withIndex()) {
            try {
                stopped(); val file = unchanged(entry)
                if (!allowAllCopies) keeper(entry, paths)
                stopped(); unchanged(entry)
                if (action == Action.TRASH) moveToTrash(file, entry) else {
                    check(file.delete()) { "Android did not delete this file. It may be locked or access was denied." }
                    check(!file.exists()) { "Deletion could not be confirmed. Rescan." }
                }
                removed.add(entry.path)
            } catch (e: Exception) { failed.add(Failure(entry.path, e.message ?: "Android refused the operation.")) }
            if (i % 25 == 0 || i == entries.lastIndex) progress(i+1, if (action == Action.TRASH) "Moving to Sift trash" else "Deleting selected files")
        }
        val removedSet = removed.toSet()
        inventory = inventory.copy(files = clearSingletons(inventory.files.filterNot { it.path in removedSet }))
        if (removed.isNotEmpty()) MediaScannerConnection.scanFile(context, removed.toTypedArray(), null, null)
        return Outcome(removed, failed, if (action == Action.TRASH) "moved to Sift trash" else "permanently deleted")
    }
    private fun safeDirectories() {
        for (dir in listOf(bin, index)) {
            check(dir.mkdirs() || dir.isDirectory) { "Cannot create Sift trash." }
            require(dir.canonicalPath == dir.absolutePath) { "Trash directory was redirected. Nothing was moved." }
        }
        File(bin, ".nomedia").createNewFile()
    }
    private fun encode(e: Entry) = JSONObject().put("path", e.path).put("name", e.name).put("size", e.size).put("modified", e.modified).put("device", e.device).put("inode", e.inode)
    private fun decode(j: JSONObject) = Entry(j.getString("path"), j.getString("name"), j.getLong("size"), j.getLong("modified"), j.getLong("device"), j.getLong("inode"))
    private fun writeIndex(id: String, json: JSONObject) {
        val atomic = AtomicFile(File(index, "$id.json"))
        val out = atomic.startWrite()
        try { out.write(json.toString().toByteArray()); atomic.finishWrite(out) }
        catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    private fun moveToTrash(file: File, entry: Entry) {
        safeDirectories()
        val id = UUID.randomUUID().toString(); val destination = File(bin, id)
        val json = JSONObject().put("id", id).put("original", encode(entry)).put("trashedAt", System.currentTimeMillis())
        // Persist the intent, then revalidate immediately before Android's native
        // rename. Media FUSE may change identity/mtime and invalidate old open
        // handles after a successful move into app-owned storage. The rename
        // acknowledgement and its postconditions are authoritative for a move;
        // no copy/delete fallback or old-handle reads are used here.
        writeIndex(id, json)
        try {
            unchanged(entry)
            check(file.renameTo(destination)) { "Could not move to Sift trash. No permanent-delete fallback was attempted." }
        } catch (error: Exception) {
            if (!destination.exists()) AtomicFile(File(index, "$id.json")).delete()
            throw error
        }
        check(!file.exists() && destination.isFile) { "Move not confirmed. Check Sift trash before retrying." }
        val stored = checked(destination)
        require(stored.size == entry.size) { "Moved file size changed. File retained in Sift trash for review." }
        writeIndex(id, json.put("stored", encode(stored)))
    }
    @Synchronized fun trash(): List<TrashEntry> {
        unreadableTrashRecords = 0
        if (!index.exists()) return emptyList()
        require(index.canonicalPath == index.absolutePath && bin.canonicalPath == bin.absolutePath) { "Trash location was redirected." }
        return index.listFiles().orEmpty().filter { it.name.endsWith(".json") }.mapNotNull { manifest ->
            runCatching {
                val id = manifest.name.removeSuffix(".json")
                require(UUID.fromString(id).toString() == id)
                val json = JSONObject(AtomicFile(manifest).openRead().use { it.readBytes().toString(Charsets.UTF_8) })
                require(json.getString("id") == id)
                val original = decode(json.getJSONObject("original")); insideRoot(File(original.path))
                val destination = File(bin, id)
                if (!destination.exists()) return@runCatching null
                val current = checked(destination)
                require(current.size == original.size) { "Trash file size changed." }
                if (json.has("stored")) {
                    val stored = decode(json.getJSONObject("stored"))
                    require(stored.path == destination.path && current.size == stored.size && current.modified == stored.modified) { "Trash file changed." }
                }
                // Mount/device and FUSE inode IDs are not persistent across
                // reboots. The private UUID path, size and mtime identify the
                // journaled item; use freshly checked IDs for this operation.
                unchanged(current, true)
                TrashEntry(id, original, current, json.getLong("trashedAt"))
            }.getOrElse { unreadableTrashRecords++; null }
        }.sortedByDescending { it.trashedAt }
    }
    @Synchronized fun manageTrash(ids: Set<String>, restore: Boolean, phrase: String, progress: (Int, String) -> Unit): Outcome {
        val lookup = trash().associateBy { it.id }
        require(ids.isNotEmpty() && ids.all { it in lookup }) { "Trash changed. Reload before proceeding." }
        if (!restore) require(phrase == confirmation(ids.size)) { "Type the exact deletion confirmation." }
        val removed = mutableListOf<String>(); val failed = mutableListOf<Failure>()
        for ((i, id) in ids.withIndex()) {
            val entry = lookup.getValue(id)
            try {
                stopped(); val stored = unchanged(entry.stored, true)
                if (restore) {
                    val target = File(entry.original.path); insideRoot(target)
                    require(target.canonicalPath == target.absolutePath) { "Original folder was redirected." }
                    require(!target.exists()) { "A file already exists at the original path. It was not overwritten." }
                    require(target.parentFile?.isDirectory == true) { "Original folder is missing. Recreate it before restoring." }
                    check(stored.renameTo(target)) { "Android could not restore this file." }
                    check(!stored.exists() && target.isFile) { "Restore not confirmed. Rescan." }
                    MediaScannerConnection.scanFile(context, arrayOf(target.path), null, null)
                } else { check(stored.delete() && !stored.exists()) { "Android could not confirm permanent deletion." } }
                AtomicFile(File(index, "$id.json")).delete()
                removed.add(id)
            } catch (e: Exception) { failed.add(Failure(entry.original.path, e.message ?: "Operation refused.")) }
            progress(i+1, if (restore) "Restoring files" else "Emptying Sift trash")
        }
        // Restoring changes the live folder. A fresh scan is required by the UI.
        return Outcome(removed, failed, if (restore) "restored" else "permanently deleted from Sift trash")
    }
    fun space(): Pair<Long, Long> = StatFs(root.path).let { it.totalBytes to it.availableBytes }
}
fun clearSingletons(files: List<Entry>): List<Entry> {
    val counts = files.mapNotNull { it.group }.groupingBy { it }.eachCount()
    return files.map { if (it.group != null && counts[it.group] == 1) it.copy(group = null) else it }
}

/** Empty files and multiple names for one filesystem object are not extra copies. */
fun duplicateCandidates(files: List<Entry>): List<List<Entry>> = files.filter { it.size > 0 }
    .distinctBy { it.device to it.inode }.groupBy { it.size }.values.filter { it.size > 1 }
