package si.sarvesh.sift

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32

/** Portable, bounded carving. No filesystem undelete or original-path inference. */
internal data class RecoveryFile(
    val id: String,
    val name: String,
    val category: String,
    val format: String,
    val offset: Long,
    val size: Long,
    val evidence: String,
    val sha256: String,
)

internal data class RecoveryReport(
    val source: String,
    val sourceBytes: Long,
    val scannedBytes: Long = 0,
    val files: List<RecoveryFile> = emptyList(),
    val rejectedHeaders: Long = 0,
    val cancelled: Boolean = false,
    val limited: Boolean = false,
    val elapsedMs: Long = 0,
    val method: String = "contiguous-signature-carving-v1",
)

internal data class RecoveryProgress(
    val bytes: Long,
    val total: Long,
    val found: Int,
    val phase: String,
)

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

internal class RecoveryCarver(
    private val channel: FileChannel,
    val source: String,
    private val unchanged: () -> Unit,
) {
    companion object {
        const val BLOCK = 1024 * 1024
        const val MAX_FILE = 512L * 1024 * 1024
        const val MAX_RESULTS = 10_000
        private val PNG = byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10)
        private val JPEG = byteArrayOf(-1, -40, -1)
    }

    val cancel = AtomicBoolean(false)
    private var budget = 0L
    private var searchCache = ByteArray(0)
    private var searchStart = 0L
    val size = channel.size()
    var report = RecoveryReport(source, size)
        private set

    init {
        require(size in 0..9_007_199_254_740_991L) { "Unsupported image size." }
    }

    private fun stopped() {
        check(!cancel.get()) { "Recovery stopped." }
    }

    private fun read(position: Long, bytes: ByteArray): Boolean = read(position, bytes, bytes.size)

    private fun read(position: Long, bytes: ByteArray, length: Int): Boolean {
        stopped()
        if (position < 0 || position > size || length > size - position) return false
        if (budget < length) {
            budget = 0
            error("Validation read budget reached.")
        }
        budget -= length
        channel.position(position)
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated image." }
        return true
    }

    private fun ByteArray.at(i: Int) = this[i].toInt() and 255

    private fun ByteArray.be16(i: Int) = (at(i) * 256 + at(i + 1)).toLong()

    private fun ByteArray.le16(i: Int) = (at(i) + at(i + 1) * 256).toLong()

    private fun ByteArray.be32(i: Int) = (0..3).fold(0L) { n, j -> n * 256 + at(i + j) }

    private fun ByteArray.le32(i: Int) =
        (0..3).fold(0L) { n, j -> n + (at(i + j).toLong() shl (j * 8)) }

    private fun ByteArray.matches(position: Int, value: ByteArray) =
        position >= 0 &&
            position + value.size <= this.size &&
            value.indices.all { this[position + it] == value[it] }

    private fun ByteArray.matches(position: Int, value: String) =
        position >= 0 &&
            position + value.length <= this.size &&
            value.indices.all { at(position + it) == value[it].code }

    private fun find(start: Long, end: Long, needle: ByteArray): Long? {
        var position = start
        while (position < end) {
            stopped()
            val cacheEnd = searchStart + searchCache.size
            if (position >= searchStart && position < cacheEnd) {
                var i = (position - searchStart).toInt()
                val stop = (minOf(end, cacheEnd) - searchStart).toInt()
                while (i + needle.size <= stop) {
                    if (
                        searchCache[i] == needle[0] &&
                            needle.indices.all { searchCache[i + it] == needle[it] }
                    )
                        return searchStart + i
                    i++
                }
                if (cacheEnd >= end) break
                val next = cacheEnd - needle.size + 1
                if (next > position) position = next
            }
            val n = minOf(end - position, (64 * 1024 + needle.size).toLong()).toInt()
            if (n < needle.size) break
            val bytes = ByteArray(n)
            if (!read(position, bytes)) return null
            searchStart = position
            searchCache = bytes
        }
        return null
    }

    private data class Match(
        val size: Long,
        val extension: String,
        val category: String,
        val evidence: String,
    )

    private fun identify(base: Long): Match? {
        val end = minOf(size, base + MAX_FILE)
        val head = ByteArray(12)
        if (!read(base, head)) return null
        if (head.matches(0, PNG)) {
            var position = base + 8
            var ihdr = false
            var idat = false
            var count = 0
            val bytes = ByteArray(64 * 1024)
            while (position + 12 <= end && count++ < 100_000) {
                val header = ByteArray(8)
                if (!read(position, header)) break
                val length = header.be32(0)
                if (length > 64 * 1024 * 1024 || position + 12 + length > end) break
                if (!ihdr && (!header.matches(4, "IHDR") || length != 13L)) break
                if (header.matches(4, "IHDR")) {
                    if (ihdr || length != 13L) break
                    ihdr = true
                }
                if (header.matches(4, "IDAT")) idat = true
                val crc = CRC32()
                crc.update(header, 4, 4)
                var offset = position + 8
                while (offset < position + 8 + length) {
                    val n = minOf(position + 8 + length - offset, bytes.size.toLong()).toInt()
                    if (!read(offset, bytes, n)) return null
                    crc.update(bytes, 0, n)
                    offset += n
                }
                val expected = ByteArray(4)
                if (!read(offset, expected) || crc.value != expected.be32(0)) break
                position += 12 + length
                if (header.matches(4, "IEND"))
                    return if (length == 0L && idat)
                        Match(
                            position - base,
                            "png",
                            "Images",
                            "PNG chunk CRCs and end marker checked",
                        )
                    else null
            }
        } else if (head.matches(0, JPEG)) {
            var position = base + 2
            var frame = false
            var scan = false
            var entropy = false
            while (position + 2 <= end) {
                if (entropy) position = find(position, end, byteArrayOf(-1)) ?: break
                val bytes = ByteArray(2)
                if (!read(position, bytes) || bytes.at(0) != 255) break
                val marker = bytes.at(1)
                if (marker == 255) {
                    position++
                    continue
                }
                if (entropy && (marker == 0 || marker in 0xd0..0xd7)) {
                    position += 2
                    continue
                }
                entropy = false
                if (marker == 0xd9)
                    return if (frame && scan)
                        Match(
                            position + 2 - base,
                            "jpg",
                            "Images",
                            "JPEG marker structure and end marker checked",
                        )
                    else null
                if (marker == 0 || marker == 0xd8 || marker in 0xd0..0xd7) break
                val lengths = ByteArray(2)
                if (!read(position + 2, lengths)) break
                val length = lengths.be16(0)
                if (length < 2 || position + 2 + length > end) break
                if (
                    marker in 0xc0..0xc3 ||
                        marker in 0xc5..0xc7 ||
                        marker in 0xc9..0xcb ||
                        marker in 0xcd..0xcf
                ) {
                    if (length < 8) break
                    frame = true
                }
                if (marker == 0xda) {
                    if (!frame || length < 6) break
                    scan = true
                    entropy = true
                }
                position += 2 + length
            }
        } else if (
            head.matches(0, "%PDF-") &&
                head.at(5) in 49..50 &&
                head.at(6) == 46 &&
                head.at(7) in 48..57
        ) {
            val footer = find(base + 8, end, "%%EOF".toByteArray())
            if (footer != null)
                return Match(
                    footer + 5 - base,
                    "pdf",
                    "Documents",
                    "PDF header/footer found; readability and later revisions unverified",
                )
        } else if (head.matches(0, "RIFF")) {
            val length = head.le32(4) + 8
            if (length < 20 || base + length > end) return null
            val extension =
                if (head.matches(8, "WEBP")) "webp"
                else if (head.matches(8, "WAVE")) "wav" else return null
            var position = base + 12
            var payload = false
            var fmt = false
            var chunks = 0
            while (position + 8 <= base + length && chunks++ < 100_000) {
                val chunk = ByteArray(8)
                if (!read(position, chunk)) break
                val n = chunk.le32(4)
                if (position + 8 + n + n % 2 > base + length) break
                if (extension == "wav") {
                    if (chunk.matches(0, "fmt ") && n >= 16) fmt = true
                    if (chunk.matches(0, "data") && n > 0) payload = true
                } else if (
                    (chunk.matches(0, "VP8 ") ||
                        chunk.matches(0, "VP8L") ||
                        chunk.matches(0, "ANMF")) && n > 0
                )
                    payload = true
                position += 8 + n + n % 2
            }
            if (position == base + length && payload && (extension != "wav" || fmt))
                return Match(
                    length,
                    extension,
                    if (extension == "wav") "Audio" else "Images",
                    "RIFF declared size and chunk boundaries checked",
                )
        } else if (head.matches(0, "PK\u0003\u0004")) {
            var search = base + 30
            while (true) {
                val eocd = find(search, end, "PK\u0005\u0006".toByteArray()) ?: break
                val record = ByteArray(22)
                if (!read(eocd, record)) break
                val entries = record.le16(10)
                val cdSize = record.le32(12)
                val cdOffset = record.le32(16)
                val comment = record.le16(20)
                if (
                    record.le16(4) == 0L &&
                        record.le16(6) == 0L &&
                        record.le16(8) == entries &&
                        entries in 1..65534 &&
                        base + cdOffset + cdSize == eocd &&
                        eocd + 22 + comment <= end
                ) {
                    var position = base + cdOffset
                    var valid = true
                    repeat(entries.toInt()) {
                        if (valid) {
                            val central = ByteArray(46)
                            if (
                                position + 46 > eocd ||
                                    !read(position, central) ||
                                    !central.matches(0, "PK\u0001\u0002")
                            )
                                valid = false
                            else {
                                val n = central.le16(28)
                                val extra = central.le16(30)
                                val c = central.le16(32)
                                val local = central.le32(42)
                                val localHead = ByteArray(30)
                                if (
                                    base + local + 30 > base + cdOffset ||
                                        !read(base + local, localHead) ||
                                        !localHead.matches(0, "PK\u0003\u0004")
                                )
                                    valid = false
                                else if (
                                    localHead.le16(26) != n ||
                                        base +
                                            local +
                                            30 +
                                            n +
                                            localHead.le16(28) +
                                            central.le32(20) > base + cdOffset ||
                                        position + 46 + n + extra + c > eocd
                                )
                                    valid = false
                                position += 46 + n + extra + c
                            }
                        }
                    }
                    if (valid && position == eocd)
                        return Match(
                            eocd + 22 + comment - base,
                            "zip",
                            "Archives",
                            "ZIP central directory and local entry bounds checked; payload CRCs unverified",
                        )
                }
                search = eocd + 4
            }
        }
        return null
    }

    private fun hash(offset: Long, length: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BLOCK)
        var position = offset
        while (position < offset + length) {
            val n = minOf(offset + length - position, BLOCK.toLong()).toInt()
            check(read(position, buffer, n))
            digest.update(buffer, 0, n)
            position += n
        }
        return digest.digest().hex()
    }

    @Synchronized
    fun scan(progress: (RecoveryProgress) -> Unit): RecoveryReport {
        unchanged()
        searchCache = ByteArray(0)
        searchStart = 0
        val start = System.nanoTime()
        budget = if (size > (Long.MAX_VALUE - MAX_FILE) / 4) Long.MAX_VALUE else size * 4 + MAX_FILE
        val bytes = ByteArray(BLOCK + 16)
        val files = ArrayList<RecoveryFile>()
        var position = 0L
        var skip = 0L
        var attempts = 0L
        var rejected = 0L
        var limited = false
        scan@ while (position < size && !cancel.get()) {
            val count = minOf(size - position, bytes.size.toLong()).toInt()
            channel.position(position)
            val buffer = ByteBuffer.wrap(bytes, 0, count)
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0) { "Truncated image." }
            val covered = minOf(count, BLOCK)
            var i = (skip - position).coerceIn(0, covered.toLong()).toInt()
            while (i < covered) {
                if (cancel.get()) break
                val b = bytes.at(i)
                val signature =
                    when (b) {
                        255 ->
                            i + 3 <= count &&
                                bytes[i + 1] == (-40).toByte() &&
                                bytes[i + 2] == (-1).toByte()
                        137 -> i + 8 <= count && bytes.matches(i, PNG)
                        37 -> i + 5 <= count && bytes.matches(i, "%PDF-")
                        80 -> i + 4 <= count && bytes.matches(i, "PK\u0003\u0004")
                        82 -> i + 4 <= count && bytes.matches(i, "RIFF")
                        else -> false
                    }
                if (signature) {
                    attempts++
                    if (attempts > 50_000 || files.size >= MAX_RESULTS || budget == 0L) {
                        limited = true
                        break@scan
                    }
                    val offset = position + i
                    try {
                        val found = identify(offset)
                        if (found != null) {
                            val sha = hash(offset, found.size)
                            val id = "%016x".format(offset)
                            files.add(
                                RecoveryFile(
                                    id,
                                    "recovered-$id.${found.extension}",
                                    found.category,
                                    found.extension,
                                    offset,
                                    found.size,
                                    found.evidence,
                                    sha,
                                )
                            )
                            skip = offset + found.size
                            i = (skip - position).coerceIn(0, covered.toLong()).toInt()
                            continue
                        } else rejected++
                    } catch (e: IllegalStateException) {
                        if (cancel.get()) break@scan
                        if (budget == 0L) {
                            limited = true
                            break@scan
                        }
                        throw e
                    }
                }
                i++
            }
            position += covered
            progress(
                RecoveryProgress(position, size, files.size, "Reading image · source unchanged")
            )
        }
        unchanged()
        report =
            RecoveryReport(
                source,
                size,
                position,
                files,
                rejected,
                cancel.get(),
                limited,
                (System.nanoTime() - start) / 1_000_000,
            )
        return report
    }

    @Synchronized
    fun previewBytes(id: String): ByteArray? {
        unchanged()
        val file = report.files.firstOrNull { it.id == id } ?: error("Candidate not in this scan.")
        if (file.category != "Images" || file.size > 16 * 1024 * 1024) return null
        val bytes = ByteArray(file.size.toInt())
        budget = Long.MAX_VALUE
        check(read(file.offset, bytes))
        check(MessageDigest.getInstance("SHA-256").digest(bytes).hex() == file.sha256) {
            "Source changed; scan again."
        }
        unchanged()
        return bytes
    }

    /** Writer receives only allowlisted contiguous bytes; never an arbitrary path. */
    @Synchronized
    fun copy(id: String, write: (ByteArray, Int) -> Unit, progress: (Long) -> Unit) {
        unchanged()
        val file = report.files.firstOrNull { it.id == id } ?: error("Candidate not in this scan.")
        stopped()
        budget = Long.MAX_VALUE
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BLOCK)
        var position = file.offset
        while (position < file.offset + file.size) {
            val n = minOf(file.offset + file.size - position, BLOCK.toLong()).toInt()
            check(read(position, buffer, n))
            write(buffer, n)
            digest.update(buffer, 0, n)
            position += n
            progress(n.toLong())
        }
        check(digest.digest().hex() == file.sha256) {
            "Source bytes differ from the recovery scan."
        }
        unchanged()
    }
}
