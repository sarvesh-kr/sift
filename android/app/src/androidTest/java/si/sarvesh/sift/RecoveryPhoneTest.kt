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
        compose.runOnIdle { model.scan(source) }
        waitIdle(model)
        assertEquals(6, model.state.value.report!!.files.size)
    }

    @Test
    fun chooserRecoveryEvidenceAndSiftTrashNavigationAreAccessible() {
        compose.onNodeWithText("Recover data").performClick()
        compose.onNodeWithText("Some things deserve\na second chance.").assertIsDisplayed()
        compose.onNodeWithText("Open Sift trash").performScrollTo().performClick()
        compose.onNodeWithText("A second chance.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch workspace").performClick()
        compose.onNodeWithText("Choose disk image").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun safScanGridSelectionSingleRecoveryAndVerifiedManifestPreserveEvidence() {
        reset()
        val original = resolver.openInputStream(source)!!.use { it.readBytes() }
        val model = model()
        compose.onNodeWithText("Recover data").performClick()
        scan(model)
        compose.onNodeWithText("6 candidate files").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Grid view").performScrollTo().performClick()
        val first = model.state.value.report!!.files.first()
        compose
            .onNodeWithContentDescription("Select ${first.name}", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        compose.onNodeWithText("List view").performScrollTo().performClick()
        compose
            .onNodeWithContentDescription("Select ${first.name}", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOn()
        compose.onNode(hasText(first.name) and !hasSetTextAction()).performScrollTo().performClick()
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
    fun imageRecoveryNeedsNoAllFilesAccess() {
        reset()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation
            .executeShellCommand("appops set si.sarvesh.sift MANAGE_EXTERNAL_STORAGE deny")
            .let {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {
                    reader ->
                    reader.readText()
                }
            }
        try {
            assertFalse(android.os.Environment.isExternalStorageManager())
            val model = model()
            scan(model)
            assertEquals(6, model.state.value.report!!.files.size)
        } finally {
            automation
                .executeShellCommand("appops set si.sarvesh.sift MANAGE_EXTERNAL_STORAGE allow")
                .let {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {
                        reader ->
                        reader.readText()
                    }
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
