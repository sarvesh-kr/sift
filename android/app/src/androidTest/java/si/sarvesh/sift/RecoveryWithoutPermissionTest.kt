package si.sarvesh.sift

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately with All files access already denied; never revoke a running test process. */
@RunWith(AndroidJUnit4::class)
class RecoveryWithoutPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun aDocumentImageCanBeScannedWithoutAllFilesAccess() {
        assertFalse(Environment.isExternalStorageManager())
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val authority = "si.sarvesh.sift.recovery-fixtures"
        resolver.call(Uri.parse("content://$authority"), "sift.test.reset", null, null)
        compose.onNodeWithText("Recover data").performClick()
        var model: RecoveryViewModel? = null
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[RecoveryViewModel::class.java]
            model!!.scan(
                DocumentsContract.buildDocumentUri(authority, "sample.img"),
                carving = true,
            )
        }
        compose.waitUntil(15_000) { !model!!.state.value.busy }
        assertNull(model!!.state.value.error)
        assertEquals(6, model!!.state.value.report!!.files.size)
        compose.onNodeWithTag("recovery-files").performScrollToNode(hasText("6 candidate files"))
        compose.onNodeWithText("6 candidate files").assertIsDisplayed()
        compose.onAllNodesWithText("Allow shared storage access").assertCountEquals(0)
    }
}
