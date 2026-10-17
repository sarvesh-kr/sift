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
    @Test fun aliasPathsDoNotCreateDuplicateCandidatesAndEmptyFilesAreExcluded() {
        val original=Entry("/one","one",123,0,1,42)
        val alias=original.copy(path="/alias",name="alias")
        val independent=original.copy(path="/two",name="two",inode=43)
        assertTrue(duplicateCandidates(listOf(original,alias)).isEmpty())
        val candidates=duplicateCandidates(listOf(original,alias,independent,original.copy(path="/empty",size=0)))
        assertEquals(1,candidates.size)
        assertEquals(setOf("/one","/two"),candidates.single().map{it.path}.toSet())
    }
}
