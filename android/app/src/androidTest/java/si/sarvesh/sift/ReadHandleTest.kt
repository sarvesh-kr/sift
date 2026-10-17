package si.sarvesh.sift

import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadHandleTest {
    @Test
    fun scanningStreamsCloseEveryTransferredDescriptorIncludingTheirChannels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File.createTempFile("sift-read-handle-", ".txt", context.cacheDir)
        source.writeText("bounded local reads")
        try {
            repeat(128) {
                val original =
                    Os.open(
                        source.path,
                        OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC,
                        0,
                    )
                val stream = takeReadDescriptor(original)
                assertFalse(original.valid())
                val retained = stream.fd
                stream.use { assertEquals(7, it.channel.read(ByteBuffer.allocate(7))) }
                assertFalse(
                    "The owned read descriptor must close after every source",
                    retained.valid(),
                )
            }
            assertEquals("bounded local reads", source.readText())
        } finally {
            source.delete()
        }
    }
}
