package si.sarvesh.sift

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.FileDescriptor
import java.io.FileInputStream

/**
 * Transfer an Os.open descriptor into a stream that actually closes it on Android.
 * FileInputStream(FileDescriptor) alone does not own/close the supplied descriptor.
 */
internal fun takeReadDescriptor(fd: FileDescriptor): FileInputStream {
    val owner = ParcelFileDescriptor.dup(fd)
    try {
        Os.close(fd)
        return ParcelFileDescriptor.AutoCloseInputStream(owner)
    } catch (e: Exception) {
        owner.close()
        throw e
    }
}
