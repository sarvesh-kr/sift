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
}
