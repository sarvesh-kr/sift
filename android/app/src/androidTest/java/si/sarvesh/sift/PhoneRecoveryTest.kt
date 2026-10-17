package si.sarvesh.sift

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(parent: File = Environment.getExternalStorageDirectory()): File {
        val root = File(parent, "Sift-phone-test-${UUID.randomUUID()}")
        root.mkdirs()
        context.assets.open("sample.img").use { input ->
            File(root, ".cache")
                .apply { mkdirs() }
                .resolve("thumbnail-database")
                .outputStream()
                .use { input.copyTo(it) }
        }
        return root
    }

    @Test
    fun nonRootPhoneScanFindsEmbeddedCachedFilesAndRecoversVerifiedCopies() {
        val root = fixture()
        try {
            val source = File(root, ".cache/thumbnail-database")
            val original = source.readBytes()
            val authority = "si.sarvesh.sift.recovery-fixtures"
            context.contentResolver.call(
                Uri.parse("content://$authority"),
                "sift.test.reset",
                null,
                null,
            )
            var model: RecoveryViewModel? = null
            compose.runOnIdle {
                model = ViewModelProvider(compose.activity)[RecoveryViewModel::class.java]
            }
            compose.onNodeWithText("Recover data").performClick()
            compose.onNodeWithTag("recovery-files").performScrollToNode(hasText("Scan this phone"))
            compose.onNodeWithText("Scan this phone").performClick()
            compose.waitUntil(30_000) {
                !model!!.state.value.busy && model!!.state.value.report != null
            }
            assertNull(model!!.state.value.error)
            val report = model!!.state.value.report!!
            assertEquals("android-surviving-copies-v1", report.method)
            val files = report.files.filter { it.origin?.startsWith(source.path) == true }
            assertEquals(6, files.size)
            val png = files.first { it.format == "png" }
            assertNotNull(png.width)
            assertNotNull(png.height)
            assertTrue(png.evidence.contains("deletion status unknown"))
            compose
                .onNodeWithTag("recovery-files")
                .performScrollToNode(hasContentDescription("Select ${png.name}"))
            compose.onNodeWithContentDescription("Select ${png.name}").performClick()
            compose.onNodeWithTag("recovery-files").performScrollToNode(hasText("Grid view"))
            compose.onNodeWithText("Grid view").performClick()
            compose.runOnIdle {
                model!!.export(
                    DocumentsContract.buildTreeDocumentUri(authority, "destination"),
                    setOf(png.id),
                )
            }
            compose.waitUntil(15_000) { !model!!.state.value.busy }
            assertNull(model!!.state.value.error)
            val result = model!!.state.value.result!!
            assertTrue(result.failed.toString(), result.failed.isEmpty())
            assertEquals(1, result.exported.size)
            val recovered =
                context.contentResolver
                    .openInputStream(Uri.parse(result.exported.single().uri))!!
                    .use { it.readBytes() }
            assertEquals(png.sha256, MessageDigest.getInstance("SHA-256").digest(recovered).hex())
            assertArrayEquals(original, source.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun phoneRecoverySkipsVisibleFoldersBinsAndLinksAndRejectsChangedSources() {
        // FUSE shared storage forbids symlink creation. Exercise no-follow on
        // an app-owned ext4 fixture using the same recovery reader.
        val root = fixture(context.cacheDir)
        try {
            val cache = File(root, ".cache/thumbnail-database")
            File(root, "Visible").mkdir()
            cache.copyTo(File(root, "Visible/live.img"))
            File(root, ".trash").mkdir()
            cache.copyTo(File(root, ".trash/bin.img"))
            android.system.Os.symlink(cache.path, File(root, ".linked").path)
            val reader = PhoneRecoveryReader(root)
            val report = reader.scan {}
            assertEquals(6, report.files.size)
            assertTrue(report.files.all { it.origin!!.startsWith(cache.path) })
            cache.appendText("changed")
            assertThrows(Exception::class.java) {
                reader.copy(report.files.first().id, { _, _ -> }, {})
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
