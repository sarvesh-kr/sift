package si.sarvesh.sift

import androidx.annotation.Keep
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

@Keep
internal object NativeUndelete {
    init {
        System.loadLibrary("sift_undelete")
    }

    @JvmStatic external fun open(fd: Int): Long

    @JvmStatic external fun scan(handle: Long, callback: ProgressCallback): String

    @JvmStatic external fun validate(handle: Long, id: String)

    @JvmStatic external fun read(handle: Long, id: String, offset: Long, count: Int): ByteArray

    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun reset(handle: Long)

    @JvmStatic external fun close(handle: Long)

    @Keep
    class ProgressCallback(private val action: (Long, Long, Int) -> Unit) {
        @Keep fun progress(done: Long, total: Long, found: Int) = action(done, total, found)
    }
}

/** Same read-only NTFS undelete engine as desktop. SAF only supplies the image FD. */
internal class NtfsRecoveryReader(
    fd: Int,
    private val source: String,
    override val size: Long,
    private val unchanged: () -> Unit,
) : RecoveryReader {
    private var handle = NativeUndelete.open(fd)
    override val cancel = AtomicBoolean(false)
    override var report = RecoveryReport(source, size, method = "ntfs-deleted-records-v1")
        private set

    override fun setCancelled(value: Boolean) {
        cancel.set(value)
        if (handle != 0L) {
            if (value) NativeUndelete.cancel(handle) else NativeUndelete.reset(handle)
        }
    }

    @Synchronized
    override fun scan(progress: (RecoveryProgress) -> Unit): RecoveryReport {
        unchanged()
        val start = System.nanoTime()
        val json =
            JSONObject(
                NativeUndelete.scan(
                    handle,
                    NativeUndelete.ProgressCallback { done, total, found ->
                        progress(
                            RecoveryProgress(
                                done,
                                total,
                                found,
                                "Reading NTFS deleted file records",
                            )
                        )
                    },
                )
            )
        val entries = json.getJSONArray("files")
        val files =
            (0 until entries.length()).map { index ->
                val f = entries.getJSONObject(index)
                val name = f.getString("name")
                RecoveryFile(
                    f.getString("id"),
                    name,
                    category(name),
                    name.substringAfterLast('.', "").lowercase(),
                    f.getLong("offset"),
                    f.getLong("size"),
                    f.getString("evidence"),
                    "",
                    f.getString("originalPath"),
                    f.getBoolean("recoverable"),
                )
            }
        unchanged()
        report =
            RecoveryReport(
                source,
                size,
                json.getLong("recordsExamined"),
                files,
                json.getLong("skipped"),
                json.getBoolean("cancelled"),
                json.getBoolean("limited"),
                (System.nanoTime() - start) / 1_000_000,
                "ntfs-deleted-records-v1",
            )
        return report
    }

    private fun file(id: String) =
        report.files.firstOrNull { it.id == id && it.recoverable }
            ?: error("Deleted file is not recoverable in this scan.")

    @Synchronized
    override fun previewBytes(id: String): ByteArray? {
        val file = file(id)
        if (file.category != "Images" || file.size > 16 * 1024 * 1024) return null
        unchanged()
        NativeUndelete.validate(handle, id)
        val result = ByteArray(file.size.toInt())
        var offset = 0
        while (offset < result.size) {
            val chunk =
                NativeUndelete.read(
                    handle,
                    id,
                    offset.toLong(),
                    minOf(RecoveryCarver.BLOCK, result.size - offset),
                )
            check(chunk.isNotEmpty())
            chunk.copyInto(result, offset)
            offset += chunk.size
        }
        NativeUndelete.validate(handle, id)
        unchanged()
        return result
    }

    @Synchronized
    override fun copy(
        id: String,
        write: (ByteArray, Int) -> Unit,
        progress: (Long) -> Unit,
    ): String {
        val file = file(id)
        unchanged()
        NativeUndelete.validate(handle, id)
        val hash = MessageDigest.getInstance("SHA-256")
        var offset = 0L
        while (offset < file.size) {
            check(!cancel.get()) { "Recovery stopped." }
            val chunk =
                NativeUndelete.read(
                    handle,
                    id,
                    offset,
                    minOf(RecoveryCarver.BLOCK.toLong(), file.size - offset).toInt(),
                )
            check(chunk.isNotEmpty()) { "Incomplete deleted data stream" }
            write(chunk, chunk.size)
            hash.update(chunk)
            offset += chunk.size
            progress(chunk.size.toLong())
        }
        NativeUndelete.validate(handle, id)
        unchanged()
        return hash.digest().hex()
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeUndelete.close(handle)
            handle = 0
        }
    }
}
