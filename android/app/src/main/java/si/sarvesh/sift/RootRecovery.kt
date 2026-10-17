package si.sarvesh.sift

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicit user-triggered root consent. The privileged helper can only read /data. */
internal class RootRecoveryInput(
    context: Context,
    private val sourceId: String,
    started: (RootBlockChannel) -> Unit,
) : RecoveryInput {
    override val uri: Uri? = null
    private val channel = RootBlockChannel(context, sourceId).also(started)
    override val carver: RecoveryReader

    init {
        try {
            channel.connect()
            carver = RootRecoveryReader(channel)
        } catch (e: Exception) {
            channel.close()
            throw e
        }
    }

    override fun sameFile(fd: java.io.FileDescriptor) = Os.fstat(fd).st_dev == channel.device

    override fun validateDestination(tree: Uri) {
        check(
            tree.authority == "com.android.externalstorage.documents" &&
                DocumentsContract.getTreeDocumentId(tree).substringBefore(':').let { dest ->
                    dest != sourceId &&
                        (dest == "primary" || dest.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}")))
                }
        ) {
            "Choose a different storage device. Saving rooted recovery to the source could destroy remaining deleted data."
        }
    }

    override fun close() {
        carver.setCancelled(true)
        channel.close()
    }
}

internal class RootBlockChannel(context: Context, private val sourceId: String) : RecoveryChannel {
    private val helper =
        File(context.applicationInfo.nativeLibraryDir, "libsift_root_reader.so").canonicalFile
    @Volatile private var process: Process? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private var length = 0L
    var device = 0L
        private set

    private var position = 0L
    @Volatile private var closed = false

    fun connect() {
        check(!closed && helper.isFile && helper.canExecute()) {
            "Read-only root helper is unavailable on this installation."
        }
        check(sourceId == "primary" || sourceId.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"))) {
            "Unsupported storage identifier."
        }
        val command = "exec '" + helper.path.replace("'", "'\\''") + "' --storage-id '$sourceId'"
        val next =
            try {
                ProcessBuilder("su", "-c", command).start()
            } catch (e: Exception) {
                error("Root access is unavailable. Use the non-root phone scan instead.")
            }
        process = next
        if (closed) {
            next.destroy()
            error("Recovery stopped.")
        }
        val timer = Executors.newSingleThreadScheduledExecutor()
        val timeout = timer.schedule({ next.destroy() }, 45, TimeUnit.SECONDS)
        // Drain bounded diagnostic output so the read-only helper cannot block on stderr.
        val diagnostic = StringBuilder()
        Thread(
                {
                    next.errorStream.bufferedReader().use { reader ->
                        val chars = CharArray(512)
                        while (true) {
                            val n = reader.read(chars)
                            if (n < 0) break
                            synchronized(diagnostic) {
                                if (diagnostic.length < 4096)
                                    diagnostic.append(chars, 0, minOf(n, 4096 - diagnostic.length))
                            }
                        }
                    }
                },
                "sift-root-diagnostic",
            )
            .apply {
                isDaemon = true
                start()
            }
        try {
            input = DataInputStream(next.inputStream)
            output = DataOutputStream(next.outputStream)
            val magic = ByteArray(8)
            input.readFully(magic)
            check(String(magic, Charsets.US_ASCII) == "SIFTRD01") {
                "Unexpected root helper response."
            }
            length = input.readLong()
            device = input.readLong()
            check(length in 4096..9_007_199_254_740_991L) { "Unsupported userdata device size." }
        } catch (e: Exception) {
            val details = synchronized(diagnostic) { diagnostic.toString().trim() }
            error(
                "Rooted storage access was denied, timed out, or is unsupported. ${details.take(512)}"
            )
        } finally {
            timeout.cancel(false)
            timer.shutdownNow()
        }
    }

    override fun size() = length

    override fun position(value: Long) {
        check(value in 0..length)
        position = value
    }

    @Synchronized
    override fun read(target: ByteBuffer): Int {
        check(!closed) { "Root recovery source is closed." }
        val count = minOf(target.remaining().toLong(), length - position, 1024L * 1024).toInt()
        if (count == 0) return -1
        output.writeByte(1)
        output.writeLong(position)
        output.writeInt(count)
        output.flush()
        val bytes = ByteArray(count)
        input.readFully(bytes)
        target.put(bytes)
        position += count
        return count
    }

    fun close() {
        closed = true
        process?.destroy()
    }
}

internal class RootRecoveryReader(private val channel: RootBlockChannel) : RecoveryReader {
    private val delegate =
        RecoveryCarver(channel, "This phone · rooted read-only userdata scan", {})
    override val size
        get() = delegate.size

    override val cancel
        get() = delegate.cancel

    override var report =
        RecoveryReport(
            "This phone · rooted read-only userdata scan",
            size,
            method = "android-root-signature-scan-v1",
        )
        private set

    override fun setCancelled(value: Boolean) {
        delegate.setCancelled(value)
    }

    override fun scan(progress: (RecoveryProgress) -> Unit): RecoveryReport {
        report =
            delegate
                .scan(progress)
                .copy(
                    method = "android-root-signature-scan-v1",
                    files =
                        delegate.report.files.map {
                            it.copy(
                                evidence =
                                    "Rooted raw-storage signature candidate · deletion status unknown · ${it.evidence}",
                                origin = "Raw userdata @ 0x${it.offset.toString(16)}",
                            )
                        },
                )
        return report
    }

    override fun previewBytes(id: String) = delegate.previewBytes(id)

    override fun copy(id: String, write: (ByteArray, Int) -> Unit, progress: (Long) -> Unit) =
        delegate.copy(id, write, progress)

    override fun close() = channel.close()
}
