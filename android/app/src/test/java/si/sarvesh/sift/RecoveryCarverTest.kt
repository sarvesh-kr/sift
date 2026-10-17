package si.sarvesh.sift

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class RecoveryCarverTest {
    private val sample = javaClass.classLoader!!.getResourceAsStream("sample.img")!!.readBytes()

    private fun withImage(bytes: ByteArray = sample, run: (RecoveryCarver, File) -> Unit) {
        val file = File.createTempFile("sift-evidence-", ".img")
        try {
            file.writeBytes(bytes)
            val size = file.length()
            val modified = Files.getLastModifiedTime(file.toPath())
            RandomAccessFile(file, "r").use { source ->
                run(
                    RecoveryCarver(source.channel, file.name) {
                        check(
                            file.length() == size &&
                                Files.getLastModifiedTime(file.toPath()) == modified
                        ) {
                            "Source changed."
                        }
                    },
                    file,
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun sixFormatsMatchSharedNativeGoldenOffsetsLengthsAndSha256() = withImage { carver, file ->
        val report = carver.scan {}
        val expected =
            javaClass.classLoader!!
                .getResourceAsStream("expected.tsv")!!
                .bufferedReader()
                .readLines()
        assertEquals(6, report.files.size)
        report.files.zip(expected).forEach { (actual, line) ->
            val values = line.split('\t')
            assertEquals(values[0], actual.format)
            assertEquals(values[1].toLong(), actual.offset)
            assertEquals(values[2].toLong(), actual.size)
            assertEquals(values[3], actual.sha256)
        }
        assertTrue(report.rejectedHeaders >= 3)
        assertEquals(sample.size.toLong(), report.scannedBytes)
        assertArrayEquals(sample, file.readBytes())
    }

    @Test
    fun signatureCrossingScannerBufferIsFoundExactlyOnce() {
        val bytes = ByteArray(RecoveryCarver.BLOCK - 3 - 258) + sample
        withImage(bytes) { carver, _ ->
            val report = carver.scan {}
            assertEquals(6, report.files.size)
            assertEquals((RecoveryCarver.BLOCK - 3).toLong(), report.files.first().offset)
        }
    }

    @Test
    fun copyingIsAllowlistedExactAndDoesNotWriteTheSource() = withImage { carver, file ->
        val report = carver.scan {}
        for (candidate in report.files) {
            val output = java.io.ByteArrayOutputStream()
            carver.copy(candidate.id, { bytes, n -> output.write(bytes, 0, n) }, {})
            assertEquals(
                candidate.sha256,
                MessageDigest.getInstance("SHA-256").digest(output.toByteArray()).hex(),
            )
            assertArrayEquals(
                sample.copyOfRange(
                    candidate.offset.toInt(),
                    (candidate.offset + candidate.size).toInt(),
                ),
                output.toByteArray(),
            )
        }
        assertThrows(IllegalStateException::class.java) {
            carver.copy("forged", { _, _ -> fail("No bytes should be written") }, {})
        }
        assertArrayEquals(sample, file.readBytes())
    }

    @Test
    fun corruptPngCrcAndOversizedRiffAreExcluded() {
        val broken = sample.copyOf()
        broken[258 + 29] = (broken[258 + 29].toInt() xor 16).toByte()
        withImage(broken) { carver, _ ->
            val report = carver.scan {}
            assertEquals(5, report.files.size)
            assertFalse(report.files.any { it.format == "png" })
        }
    }

    @Test
    fun cancellationStopsCopyAndScanWithoutAlteringEvidence() = withImage { carver, file ->
        val report = carver.scan {}
        carver.cancel.set(true)
        assertThrows(IllegalStateException::class.java) {
            carver.copy(
                report.files.first().id,
                { _, _ -> fail("Cancelled copy must not write") },
                {},
            )
        }
        val stopped = carver.scan {}
        assertTrue(stopped.cancelled)
        assertTrue(stopped.files.isEmpty())
        assertArrayEquals(sample, file.readBytes())
    }

    @Test
    fun stuffedJpegMarkersReuseSearchWindowsInsteadOfExhaustingTheBudget() {
        val line =
            javaClass.classLoader!!
                .getResourceAsStream("expected.tsv")!!
                .bufferedReader()
                .readLines()[1]
                .split('\t')
        val offset = line[1].toInt()
        val size = line[2].toInt()
        val dense =
            sample.copyOfRange(offset, offset + size - 2) +
                ByteArray(200_000) { if (it % 2 == 0) 255.toByte() else 0 } +
                byteArrayOf(-1, -39)
        withImage(dense) { carver, _ ->
            val report = carver.scan {}
            assertEquals(1, report.files.size)
            assertEquals(dense.size.toLong(), report.files.single().size)
            assertFalse(report.limited)
        }
    }

    @Test
    fun changedSourceIsRefusedBeforeWriting() = withImage { carver, file ->
        val report = carver.scan {}
        file.writeText("changed")
        assertThrows(IllegalStateException::class.java) {
            carver.copy(
                report.files.first().id,
                { _, _ -> fail("Changed image must not write") },
                {},
            )
        }
    }
}
