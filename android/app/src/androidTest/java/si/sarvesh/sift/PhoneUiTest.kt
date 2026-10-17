package si.sarvesh.sift

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun nativePhoneNavigationAndTrashExplanationAreAccessible() {
        compose.onNodeWithText("Your phone,\na little lighter.").assertIsDisplayed()
        compose.onNodeWithText("Files",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Everything in its place.").assertIsDisplayed()
        compose.onNodeWithText("Search files").performTextInput("example")
        compose.onNodeWithText("Trash",useUnmergedTree=true).performClick()
        compose.onNodeWithText("A second chance.").assertIsDisplayed()
        compose.onNodeWithText("Sift trash is empty.").assertIsDisplayed()
        compose.onNodeWithContentDescription("About Sift").performClick()
        compose.onNodeWithText("Space, with care.").assertIsDisplayed()
    }
    @Test fun gridAndListKeepSelectionAndDisplayRealLocalImagePreviews() {
        val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),"Sift-ui-${java.util.UUID.randomUUID()}.png")
        val bitmap=android.graphics.Bitmap.createBitmap(40,80,android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        try {
            compose.onNodeWithText("Scan shared storage").performScrollTo().performClick()
            compose.waitUntil(15_000){compose.onAllNodesWithText("files indexed",substring=true).fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Files",useUnmergedTree=true).performClick()
            compose.onNodeWithText("Search files").performTextInput(file.name)
            compose.runOnIdle { (compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(compose.activity.window.decorView.windowToken,0) }
            compose.onNodeWithText("Grid view").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Select ${file.name}").performScrollTo().performClick()
            compose.runOnIdle {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { screenshot ->
                    java.io.File(context.externalCacheDir,"sift-grid.png").outputStream().use{screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                }
            }
            compose.onNodeWithText("List view").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Select ${file.name}").performScrollTo().assertIsOn()
            compose.waitUntil(10_000){compose.onAllNodesWithContentDescription("Preview of ${file.name}").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Grid view").performScrollTo().performClick()
            compose.onNodeWithText(file.name).performScrollTo().performClick()
            compose.onNodeWithText("Review this file only").performClick()
            compose.onAllNodesWithContentDescription("Preview of ${file.name}").onLast().assertIsDisplayed()
            compose.onNodeWithText("Keep exploring").performClick()
        } finally {file.delete(); context.getSharedPreferences("sift-ui",android.content.Context.MODE_PRIVATE).edit().putBoolean("file-grid",false).commit()}
    }
}
