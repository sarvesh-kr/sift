package si.sarvesh.sift

import android.content.Context
import android.os.Environment
import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class StorageEngineTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test: (File, File, File, StorageEngine) -> Unit) {
        val base = File(context.cacheDir,"engine-${UUID.randomUUID()}").canonicalFile
        val root = File(base,"root").apply{mkdirs()}; val bin=File(base,"bin"); val index=File(base,"index")
        try { test(root,bin,index,StorageEngine(context,root,bin,index)) } finally { base.deleteRecursively() }
    }
    private fun scan(engine:StorageEngine) = engine.scan { _,_-> }
    private fun clean(engine:StorageEngine,paths:Set<String>,action:Action,phrase:String="",all:Boolean=false) = engine.cleanup(paths,action,phrase,all){_,_->}
    private fun expectFailure(block:()->Unit) { try { block(); fail("Expected the operation to be refused") } catch (_: IllegalArgumentException) { } }

    @Test fun scanSkipsProtectedFoldersAndSymlinkRedirection() = fixture { root,_,_,engine ->
        File(root,"Android/data").mkdirs(); File(root,"Android/data/private").writeText("never scan")
        File(root,"PHOTO.JPG").writeText("photo")
        val outside=File(root.parentFile,"outside").apply{writeText("keep")}
        Os.symlink(outside.path,File(root,"redirect").path)
        val result=scan(engine)
        assertEquals(listOf("PHOTO.JPG"),result.files.map{it.name})
        assertEquals("Images",result.files.single().category)
    }
    @Test fun trashRestoresUnicodeContentsAndRefusesDestinationCollisions() = fixture {root,_,_,engine ->
        val file=File(root,"memory 📷.txt").apply{writeText("precious")}
        scan(engine); val result=clean(engine,setOf(file.path),Action.TRASH)
        assertEquals(listOf(file.path),result.removed); assertTrue(result.failed.isEmpty()); assertFalse(file.exists())
        val entry=engine.trash().single(); file.writeText("new file")
        val blocked=engine.manageTrash(setOf(entry.id),true,""){_,_->}
        assertTrue(blocked.removed.isEmpty()); assertEquals("new file",file.readText())
        file.delete()
        val restored=engine.manageTrash(setOf(entry.id),true,""){_,_->}
        assertEquals(listOf(entry.id),restored.removed); assertEquals("precious",file.readText()); assertTrue(engine.trash().isEmpty())
    }
    @Test fun permanentDeleteRequiresExactConfirmationAndOnlyTouchesScannedSelection() = fixture {root,_,_,engine ->
        val keep=File(root,"keep").apply{writeText("keep")}; val selected=File(root,"selected").apply{writeText("selected")}
        scan(engine)
        expectFailure{clean(engine,setOf(selected.path),Action.DELETE,"DELETE 2 FILES")}
        expectFailure{clean(engine,setOf(selected.path,File(root,"unscanned").path),Action.DELETE,"DELETE 2 FILES")}
        assertTrue(selected.exists()); assertEquals("keep",keep.readText())
        val result=clean(engine,setOf(selected.path),Action.DELETE,"DELETE 1 FILE")
        assertEquals(listOf(selected.path),result.removed); assertFalse(selected.exists()); assertTrue(keep.exists())
    }
    @Test fun changesAndSymlinkReplacementAreRefusedWithoutFallback() = fixture {root,_,_,engine ->
        val file=File(root,"selected").apply{writeText("original")}; scan(engine)
        file.writeText("changed after scan")
        assertEquals(1,clean(engine,setOf(file.path),Action.DELETE,"DELETE 1 FILE").failed.size)
        assertEquals("changed after scan",file.readText())
        scan(engine); file.delete(); val outside=File(root.parentFile,"outside").apply{writeText("keep")}
        Os.symlink(outside.path,file.path)
        assertEquals(1,clean(engine,setOf(file.path),Action.TRASH).failed.size)
        assertEquals("keep",outside.readText())
    }
    @Test fun duplicatesKeepOneUnlessExplicitlyConfirmedAndHardLinksAreExcluded() = fixture {root,_,_,engine ->
        val one=File(root,"one").apply{writeBytes(ByteArray(600_123){it.toByte()})}
        val two=File(root,"two").apply{writeBytes(one.readBytes())}
        Os.link(one.path,File(root,"hardlink").path)
        scan(engine); val compared=engine.compare{_,_->}
        val duplicates=compared.files.filter{it.group!=null}
        assertEquals(2,duplicates.size)
        val paths=duplicates.map{it.path}.toSet()
        assertEquals(2,clean(engine,paths,Action.TRASH).failed.size)
        assertEquals(2,clean(engine,paths,Action.DELETE,"DELETE 2 FILES").failed.size)
        val removed=clean(engine,paths,Action.DELETE,"DELETE 2 FILES",true)
        assertEquals(2,removed.removed.size)
        assertTrue(listOf(one,two,File(root,"hardlink")).count{it.exists()}==1)
    }
    @Test fun cancellationDoesNotChangeTheNextFile() = fixture {root,_,_,engine ->
        val file=File(root,"keep").apply{writeText("keep")}; scan(engine); engine.cancel.set(true)
        assertEquals(1,clean(engine,setOf(file.path),Action.DELETE,"DELETE 1 FILE").failed.size)
        assertEquals("keep",file.readText())
    }
    @Test fun emptyTrashRequiresConfirmationAndPersistsAcrossSessions() = fixture {root,bin,index,engine ->
        val file=File(root,"selected").apply{writeText("content")};scan(engine);clean(engine,setOf(file.path),Action.TRASH)
        val reopened=StorageEngine(context,root,bin,index);val entry=reopened.trash().single()
        expectFailure{reopened.manageTrash(setOf(entry.id),false,"wrong"){_,_->}}
        assertTrue(reopened.trash().isNotEmpty())
        val result=reopened.manageTrash(setOf(entry.id),false,"DELETE 1 FILE"){_,_->}
        assertEquals(1,result.removed.size); assertTrue(reopened.trash().isEmpty())
    }
    @Test fun interruptedJournalIsRecoveredWithoutLosingMovedData() = fixture {root,_,index,engine ->
        val file=File(root,"selected").apply{writeText("recover")};scan(engine);clean(engine,setOf(file.path),Action.TRASH)
        val entry=engine.trash().single(); val manifest=File(index,"${entry.id}.json")
        val json=org.json.JSONObject(manifest.readText());json.remove("stored");manifest.writeText(json.toString())
        assertEquals(1,engine.trash().size)
        assertEquals(1,engine.manageTrash(setOf(entry.id),true,""){_,_->}.removed.size)
        assertEquals("recover",file.readText())
    }
    @Test fun corruptTrashIndexIsReportedAndNeverDeletesStoredFiles() = fixture {root,bin,index,engine ->
        val file=File(root,"selected").apply{writeText("keep safe")};scan(engine);clean(engine,setOf(file.path),Action.TRASH)
        val entry=engine.trash().single();File(index,"${entry.id}.json").writeText("broken")
        assertTrue(engine.trash().isEmpty());assertEquals(1,engine.unreadableTrashRecords)
        assertEquals("keep safe",File(bin,entry.id).readText())
    }
    @Test fun actualSharedStorageMovesIntoAppTrashAndRestores() {
        assertTrue("CI must grant All files access to this isolated emulator app",Environment.isExternalStorageManager())
        val id=UUID.randomUUID().toString()
        val root=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Sift-test-$id").canonicalFile.apply{mkdirs()}
        val bin=File(context.getExternalFilesDir(null),"trash-test-$id").canonicalFile
        val index=File(context.cacheDir,"index-test-$id").canonicalFile
        try {
            val engine=StorageEngine(context,root,bin,index)
            val file=File(root,"example 📷.txt").apply{writeText("same phone filesystem")}
            scan(engine);val result=clean(engine,setOf(file.path),Action.TRASH)
            assertTrue(result.failed.toString(),result.failed.isEmpty())
            val entry=engine.trash().single()
            assertTrue(engine.manageTrash(setOf(entry.id),true,""){_,_->}.failed.isEmpty())
            assertEquals("same phone filesystem",file.readText())
        } finally {root.deleteRecursively();bin.deleteRecursively();index.deleteRecursively()}
    }
}
