package si.sarvesh.sift

import android.graphics.BitmapFactory
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Non-root recovery of surviving shared cache contents. Never a bin or sector scan. */
internal class PhoneRecoveryInput(root: File, label: String = "Selected storage") : RecoveryInput {
    override val uri: Uri? = null
    override val carver = PhoneRecoveryReader(root, label)

    override fun sameFile(fd: FileDescriptor) = carver.sameFile(fd)

    override fun close() = carver.setCancelled(true)
}

internal class PhoneRecoveryReader(root: File, private val label: String = "Selected storage") :
    RecoveryReader {
    private val root = root.canonicalFile
    override val cancel = AtomicBoolean(false)
    override var size = 0L
        private set

    override var report =
        RecoveryReport(
            "$label · shared cache and hidden folders",
            0,
            method = "android-surviving-copies-v1",
        )
        private set

    private data class Plan(val path: String, val stat: StructStat, val file: RecoveryFile)

    private val plans = LinkedHashMap<String, Plan>()
    @Volatile private var active: RecoveryCarver? = null

    override fun setCancelled(value: Boolean) {
        cancel.set(value)
        active?.setCancelled(value)
    }

    private fun stopped() {
        check(!cancel.get()) { "Recovery stopped." }
    }

    private fun same(a: StructStat, b: StructStat) =
        a.st_dev == b.st_dev &&
            a.st_ino == b.st_ino &&
            a.st_size == b.st_size &&
            a.st_mtim.tv_sec == b.st_mtim.tv_sec &&
            a.st_mtim.tv_nsec == b.st_mtim.tv_nsec &&
            a.st_ctim.tv_sec == b.st_ctim.tv_sec &&
            a.st_ctim.tv_nsec == b.st_ctim.tv_nsec

    private fun open(path: String, before: StructStat): FileInputStream {
        val file = File(path)
        check(file.canonicalPath == file.absolutePath && file.toPath().startsWith(root.toPath())) {
            "Recovery source path changed."
        }
        val fd =
            Os.open(
                path,
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                0,
            )
        try {
            check(same(before, Os.fstat(fd)) && OsConstants.S_ISREG(before.st_mode)) {
                "Recovery source changed. Scan again."
            }
            return FileInputStream(fd)
        } catch (e: Exception) {
            Os.close(fd)
            throw e
        }
    }

    @Synchronized
    override fun scan(progress: (RecoveryProgress) -> Unit): RecoveryReport {
        val start = System.nanoTime()
        plans.clear()
        size = 0
        var visited = 0
        var skipped = 0L
        var limited = false
        val stack = ArrayDeque<Pair<File, Boolean>>()
        stack.add(root to false)
        val containers = ArrayList<Pair<File, StructStat>>()
        // No Android app-private folders, bins, symlinks, recovery outputs, or ordinary
        // visible gallery folders. Hidden/.nomedia/cache locations are candidates only.
        while (stack.isNotEmpty() && !cancel.get()) {
            val (folder, inherited) = stack.removeLast()
            val entries = folder.listFiles()
            if (entries == null) {
                skipped++
                continue
            }
            val hidden = inherited || File(folder, ".nomedia").isFile
            for (file in entries) {
                if (cancel.get()) break
                if (++visited > 100_000) {
                    limited = true
                    break
                }
                val name = file.name.lowercase()
                if (
                    name == "android" ||
                        "trash" in name ||
                        "recycle" in name ||
                        name.startsWith("sift-recovered-")
                )
                    continue
                val stat = runCatching { Os.lstat(file.path) }.getOrNull()
                if (stat == null) {
                    skipped++
                    continue
                }
                if (
                    file.canonicalPath != file.absolutePath ||
                        !file.toPath().startsWith(root.toPath())
                )
                    continue
                val cache = hidden || name.startsWith('.') || "thumbnail" in name || "cache" in name
                if (OsConstants.S_ISDIR(stat.st_mode)) {
                    if (root.toPath().relativize(file.toPath()).nameCount < 48)
                        stack.add(file to cache)
                } else if (cache && OsConstants.S_ISREG(stat.st_mode) && stat.st_size > 0) {
                    if (
                        stat.st_size > 256L * 1024 * 1024 ||
                            containers.size >= 1024 ||
                            size + stat.st_size > 2L * 1024 * 1024 * 1024
                    ) {
                        limited = true
                        continue
                    }
                    containers.add(file to stat)
                    size += stat.st_size
                }
            }
            progress(
                RecoveryProgress(visited.toLong(), 0, 0, "Finding shared cache and hidden folders")
            )
            if (visited > 100_000) break
        }
        var examined = 0L
        val hashes = HashSet<String>()
        for ((container, stat) in containers) {
            if (cancel.get()) break
            try {
                open(container.path, stat).use { input ->
                    val unchanged = {
                        check(
                            same(stat, Os.fstat(input.fd)) && same(stat, Os.lstat(container.path))
                        ) {
                            "Cache changed during scan."
                        }
                    }
                    val reader = RecoveryCarver(input.channel, container.path, unchanged)
                    active = reader
                    reader.setCancelled(cancel.get())
                    val found =
                        reader.scan { p ->
                            progress(
                                RecoveryProgress(
                                    examined + p.bytes,
                                    size,
                                    plans.size,
                                    "Searching surviving cache contents",
                                )
                            )
                        }
                    for (file in found.files) {
                        if (!hashes.add(file.sha256)) continue
                        val id = "phone-${plans.size.toString(16)}-${file.sha256.take(16)}"
                        val dimensions =
                            if (file.category == "Images" && file.size <= 16 * 1024 * 1024)
                                runCatching {
                                        val bytes = reader.previewBytes(file.id)!!
                                        BitmapFactory.Options().also {
                                            it.inJustDecodeBounds = true
                                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it)
                                        }
                                    }
                                    .getOrNull()
                            else null
                        val width = dimensions?.outWidth?.takeIf { it > 0 }
                        val height = dimensions?.outHeight?.takeIf { it > 0 }
                        val label =
                            if (width != null && height != null && maxOf(width, height) <= 1024)
                                "Thumbnail-sized surviving copy"
                            else "Surviving cached contents"
                        val candidate =
                            file.copy(
                                id = id,
                                name = "found-${file.sha256.take(12)}.${file.format}",
                                evidence = "$label · original deletion status unknown",
                                origin = "${container.path} @ 0x${file.offset.toString(16)}",
                                width = width,
                                height = height,
                            )
                        plans[id] = Plan(container.path, stat, candidate)
                        if (plans.size >= 10_000) {
                            limited = true
                            break
                        }
                    }
                    limited = limited || found.limited
                }
            } catch (e: Exception) {
                if (!cancel.get()) skipped++
            } finally {
                active = null
            }
            examined += stat.st_size
            if (plans.size >= 10_000) break
        }
        report =
            RecoveryReport(
                "$label · shared cache and hidden folders",
                size,
                examined,
                plans.values.map { it.file },
                skipped,
                cancel.get(),
                limited,
                (System.nanoTime() - start) / 1_000_000,
                "android-surviving-copies-v1",
            )
        return report
    }

    private fun plan(id: String) = plans[id] ?: error("File is not in this phone recovery scan.")

    @Synchronized
    override fun previewBytes(id: String): ByteArray? {
        val plan = plan(id)
        val file = plan.file
        if (file.category != "Images" || file.size > 16 * 1024 * 1024) return null
        val output = ByteArray(file.size.toInt())
        open(plan.path, plan.stat).use { input ->
            input.channel.position(file.offset)
            var at = 0
            while (at < output.size) {
                val n = input.read(output, at, output.size - at)
                check(n > 0)
                at += n
            }
            check(same(plan.stat, Os.fstat(input.fd)))
        }
        check(MessageDigest.getInstance("SHA-256").digest(output).hex() == file.sha256) {
            "Cache contents changed. Scan again."
        }
        return output
    }

    @Synchronized
    override fun copy(
        id: String,
        write: (ByteArray, Int) -> Unit,
        progress: (Long) -> Unit,
    ): String {
        val plan = plan(id)
        val file = plan.file
        stopped()
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(RecoveryCarver.BLOCK)
        open(plan.path, plan.stat).use { input ->
            input.channel.position(file.offset)
            var remaining = file.size
            while (remaining > 0) {
                stopped()
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(n > 0) { "Cache was truncated" }
                write(buffer, n)
                hash.update(buffer, 0, n)
                remaining -= n
                progress(n.toLong())
            }
            check(same(plan.stat, Os.fstat(input.fd)) && same(plan.stat, Os.lstat(plan.path))) {
                "Cache changed during recovery."
            }
        }
        val digest = hash.digest().hex()
        check(digest == file.sha256) { "Source no longer matches the phone scan." }
        return digest
    }

    fun sameFile(fd: FileDescriptor): Boolean {
        val stat = Os.fstat(fd)
        return plans.values.any { it.stat.st_dev == stat.st_dev && it.stat.st_ino == stat.st_ino }
    }
}
