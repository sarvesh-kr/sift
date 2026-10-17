package si.sarvesh.sift

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryPhoneTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val resolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private val authority = "si.sarvesh.sift.recovery-fixtures"
    private val source
        get() = DocumentsContract.buildDocumentUri(authority, "sample.img")

    private val destination
        get() = DocumentsContract.buildTreeDocumentUri(authority, "destination")

    private fun reset() {
        resolver.call(Uri.parse("content://$authority"), "sift.test.reset", null, null)
    }

    private fun model(): RecoveryViewModel {
        var result: RecoveryViewModel? = null
        compose.runOnIdle {
            result = ViewModelProvider(compose.activity)[RecoveryViewModel::class.java]
        }
        return result!!
    }

    private fun waitIdle(model: RecoveryViewModel) {
        compose.waitUntil(15_000) { !model.state.value.busy }
        assertNull(model.state.value.error)
    }

    private fun scan(model: RecoveryViewModel) {
        compose.runOnIdle { model.scan(source, carving = true) }
        waitIdle(model)
        assertEquals(6, model.state.value.report!!.files.size)
    }

    @Test
    fun chooserOpensActualUndeleteAndKeepsTrashOutOfRecovery() {
        compose.onNodeWithText("Recover data").performClick()
        compose.onNodeWithText("Some things deserve\na second chance.").assertIsDisplayed()
        compose.onNodeWithText("Open Sift trash").assertDoesNotExist()
        compose
            .onNodeWithTag("recovery-files")
            .performScrollToNode(hasText("Look for what remains on your phone."))
        compose.onNodeWithText("Look for what remains on your phone.").assertIsDisplayed()
    }

    @Test
    fun deletedNtfsFilesRecoverThroughTheSameNativeEngineAsDesktop() {
        reset()
        val ntfsSource = DocumentsContract.buildDocumentUri(authority, "ntfs-deleted.img")
        val before =
            resolver.openInputStream(ntfsSource)!!.use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            }
        val model = model()
        compose.onNodeWithText("Recover data").performClick()
        compose.runOnIdle { model.scan(ntfsSource) }
        waitIdle(model)
        val report = model.state.value.report!!
        assertEquals("ntfs-deleted-records-v1", report.method)
        assertEquals(2, report.files.size)
        assertTrue(
            report.files.all { it.recoverable && it.originalPath!!.startsWith("/Projects/") }
        )
        compose.runOnIdle { model.export(destination, report.files.map { it.id }.toSet()) }
        waitIdle(model)
        val result = model.state.value.result!!
        assertTrue(result.failed.isEmpty())
        assertEquals(2, result.exported.size)
        result.exported.forEach { copy ->
            val bytes = resolver.openInputStream(Uri.parse(copy.uri))!!.use { it.readBytes() }
            assertEquals(copy.file.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).hex())
            if (copy.file.name == "meeting-notes.txt")
                assertEquals(
                    "These notes were permanently deleted, outside any recycle bin.\n",
                    String(bytes),
                )
            else
                assertArrayEquals(
                    ByteArray(4096) { ((it * 17 + 31) % 251).toByte() }
                        .let { block -> ByteArray(49152) { block[it % 4096] } },
                    bytes,
                )
        }
        assertArrayEquals(
            before,
            resolver.openInputStream(ntfsSource)!!.use {
                MessageDigest.getInstance("SHA-256").digest(it.readBytes())
            },
        )
    }

    @Test
    fun safScanGridSelectionSingleRecoveryAndVerifiedManifestPreserveEvidence() {
        reset()
        val original = resolver.openInputStream(source)!!.use { it.readBytes() }
        val model = model()
        compose.onNodeWithText("Recover data").performClick()
        scan(model)
        val grid = compose.onNodeWithTag("recovery-files")
        grid.performScrollToNode(hasText("6 candidate files"))
        compose.onNodeWithText("6 candidate files").assertIsDisplayed()
        grid.performScrollToNode(hasText("Grid view"))
        compose.onNodeWithText("Grid view").performClick()
        val first = model.state.value.report!!.files.first()
        grid.performScrollToNode(hasContentDescription("Select ${first.name}"))
        compose
            .onNodeWithContentDescription("Select ${first.name}", useUnmergedTree = true)
            .performClick()
        grid.performScrollToNode(hasText("List view"))
        compose.onNodeWithText("List view").performClick()
        grid.performScrollToNode(hasContentDescription("Select ${first.name}"))
        compose
            .onNodeWithContentDescription("Select ${first.name}", useUnmergedTree = true)
            .assertIsOn()
        compose.onNode(hasText(first.name) and !hasSetTextAction()).performClick()
        compose.onNodeWithText("Recover this file only").assertIsDisplayed()
        compose.onNodeWithText("Close", useUnmergedTree = true).performClick()
        compose.runOnIdle { model.export(destination) }
        waitIdle(model)
        val result = model.state.value.result!!
        assertEquals(1, result.exported.size)
        assertTrue(result.failed.isEmpty())
        assertNotNull(result.manifest)
        val copied =
            resolver.openInputStream(Uri.parse(result.exported.single().uri))!!.use {
                it.readBytes()
            }
        assertEquals(first.sha256, MessageDigest.getInstance("SHA-256").digest(copied).hex())
        val manifest =
            resolver.openInputStream(Uri.parse(result.manifest))!!.bufferedReader().use {
                it.readText()
            }
        assertTrue(manifest.contains(first.sha256))
        assertTrue(manifest.contains("contiguous-signature-carving-v1"))
        assertArrayEquals(original, resolver.openInputStream(source)!!.use { it.readBytes() })
        compose.runOnIdle {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap
                ->
                java.io
                    .File(
                        InstrumentationRegistry.getInstrumentation().targetContext.externalCacheDir,
                        "sift-recovery.png",
                    )
                    .outputStream()
                    .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
    }

    @Test
    fun providerSourceAliasIsRefusedWithoutTruncatingOrDeletingTheImage() {
        reset()
        val original = resolver.openInputStream(source)!!.use { it.readBytes() }
        val model = model()
        scan(model)
        resolver.call(Uri.parse("content://$authority"), "sift.test.source-alias", null, null)
        compose.runOnIdle {
            model.select(setOf(model.state.value.report!!.files.first().id))
            model.export(DocumentsContract.buildTreeDocumentUri(authority, "root"))
        }
        waitIdle(model)
        assertTrue(model.state.value.result!!.exported.isEmpty())
        assertEquals(1, model.state.value.result!!.failed.size)
        assertArrayEquals(original, resolver.openInputStream(source)!!.use { it.readBytes() })
    }

    @Test
    fun aFailedDestinationOpenCannotDeleteAReadOnlySourceAlias() {
        reset()
        val original = resolver.openInputStream(source)!!.use { it.readBytes() }
        val model = model()
        scan(model)
        resolver.call(
            Uri.parse("content://$authority"),
            "sift.test.source-alias-read-only",
            null,
            null,
        )
        compose.runOnIdle {
            model.select(setOf(model.state.value.report!!.files.first().id))
            model.export(DocumentsContract.buildTreeDocumentUri(authority, "root"))
        }
        waitIdle(model)
        assertTrue(model.state.value.result!!.exported.isEmpty())
        assertEquals(1, model.state.value.result!!.failed.size)
        assertArrayEquals(original, resolver.openInputStream(source)!!.use { it.readBytes() })
    }

    @Test
    fun corruptedDestinationIsRejectedAndDeletedWithoutChangingSource() {
        reset()
        val original = resolver.openInputStream(source)!!.use { it.readBytes() }
        val model = model()
        scan(model)
        resolver.call(Uri.parse("content://$authority"), "sift.test.corrupt-readback", null, null)
        compose.runOnIdle {
            model.select(setOf(model.state.value.report!!.files.first().id))
            model.export(destination)
        }
        waitIdle(model)
        val result = model.state.value.result!!
        assertTrue(result.exported.isEmpty())
        assertEquals(1, result.failed.size)
        assertTrue(result.failed.single().error.contains("read-back"))
        assertArrayEquals(original, resolver.openInputStream(source)!!.use { it.readBytes() })
    }
}
