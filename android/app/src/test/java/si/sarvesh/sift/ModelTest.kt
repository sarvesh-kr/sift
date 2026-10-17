package si.sarvesh.sift

import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    @Test fun classificationUsesCaseInsensitiveExtensions() {
        assertEquals("Images", category("PHOTO.HEIC"))
        assertEquals("Applications", category("Sift.APK"))
        assertEquals("Other", category("important-file"))
    }
    @Test fun permanentConfirmationIncludesExactActionCountAndPlural() {
        assertEquals("DELETE 1 FILE", confirmation(1))
        assertEquals("DELETE 23 FILES", confirmation(23))
    }
    @Test fun singletonDuplicateMarkersAreClearedAfterRemoval() {
        val a = Entry("/one", "one", 4, 0, 1, 1, group="a")
        val b = a.copy(path="/two", name="two", inode=2)
        assertEquals("a", clearSingletons(listOf(a,b))[0].group)
        assertNull(clearSingletons(listOf(a))[0].group)
    }
}
