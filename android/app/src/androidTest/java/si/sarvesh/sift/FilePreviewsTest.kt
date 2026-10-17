package si.sarvesh.sift

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FilePreviewsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(test:(File,StorageEngine)->Unit) {
        val root=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"Sift-preview-${UUID.randomUUID()}").canonicalFile.apply{mkdirs()}
        try { test(root,StorageEngine(context,root)) } finally {root.deleteRecursively()}
    }
    @Test fun imagesHaveBoundedPreviewsAndRemainVisibleInPrivateTrash() = fixture { root,engine ->
        val file=File(root,"portrait.png")
        val bitmap=Bitmap.createBitmap(800,1200,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.GREEN)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val entry=engine.scan{_,_->}.files.single()
        val preview=runBlocking{PhonePreviews.load(context,entry,entry.name)}!!.bitmap!!
        assertEquals(384,preview.height);assertTrue(preview.width<=384)
        assertTrue(engine.cleanup(setOf(entry.path),Action.TRASH,"",false){_,_->}.failed.isEmpty())
        val trashed=engine.trash().first{it.original.path==entry.path}
        try {
            val stored=runBlocking{PhonePreviews.load(context,trashed.stored,trashed.original.name)}!!.bitmap!!
            assertEquals(preview.width,stored.width);assertEquals(preview.height,stored.height)
        } finally {engine.manageTrash(setOf(trashed.id),true,""){_,_->}}
        assertTrue(file.exists())
    }
    @Test fun realVideoFramesAndPdfFirstPagesAreDecodedLocally() = fixture {root,engine ->
        val video=File(root,"clip.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("preview-fixture.mp4").use{input->video.outputStream().use{input.copyTo(it)}}
        val pdf=PdfDocument()
        try {val page=pdf.startPage(PdfDocument.PageInfo.Builder(200,300,1).create());page.canvas.drawColor(Color.BLUE);pdf.finishPage(page);File(root,"document.pdf").outputStream().use{pdf.writeTo(it)}} finally {pdf.close()}
        val entries=engine.scan{_,_->}.files
        for(entry in entries) {
            val bitmap=runBlocking{PhonePreviews.load(context,entry,entry.name)}!!.bitmap
            assertNotNull(entry.name,bitmap);assertTrue(bitmap!!.width<=384 && bitmap.height<=384)
        }
    }
    @Test fun textIsBoundedAndMalformedOrRedirectedFilesUseFallback() = fixture {root,engine ->
        File(root,"note.html").writeText("<script>not executed</script>".repeat(1000))
        File(root,"binary.txt").writeBytes(byteArrayOf(1,0,2))
        File(root,"broken.png").writeText("not an image")
        val entries=engine.scan{_,_->}.files
        for(entry in entries) {
            val preview=runBlocking{PhonePreviews.load(context,entry,entry.name)}!!
            if(entry.name=="note.html") {assertTrue(preview.text!!.startsWith("<script>"));assertTrue(preview.text.length<=2000)}
            else {assertNull(preview.bitmap);assertNull(preview.text)}
        }
        val stale=entries.first{it.name=="broken.png"};File(stale.path).writeText("changed")
        assertNull(runBlocking{PhonePreviews.load(context,stale.copy(size=999),stale.name)}!!.bitmap)
    }
}
