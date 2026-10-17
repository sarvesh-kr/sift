package si.sarvesh.sift

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

internal data class ContentPreview(val bitmap: Bitmap? = null, val text: String? = null)
/** Private memory cache only. Scans never decode content; visible composables request it. */
internal object PhonePreviews {
    private val slots = Semaphore(3)
    private val cache = object : LruCache<String, ContentPreview>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ContentPreview) = key.length * 2 + 128 + (value.bitmap?.allocationByteCount ?: ((value.text?.length ?: 0) * 2))
    }
    private fun validate(context: Context, entry: Entry): File {
        val file = File(entry.path)
        require(file.canonicalPath == file.absolutePath)
        val root = storageSources(context).map {it.root.toPath()}.firstOrNull {file.toPath().startsWith(it)} ?: error("Storage device is no longer mounted.")
        val trash = context.getExternalFilesDirs(null).filterNotNull().map { File(it,"SiftTrash").canonicalFile.toPath() }.firstOrNull {file.toPath().startsWith(it)}
        require(file.toPath().startsWith(root))
        val first = root.relativize(file.toPath()).firstOrNull()?.toString()
        require(first != "Android" || file.parentFile?.toPath() == trash)
        val st = Os.lstat(file.path)
        require(OsConstants.S_ISREG(st.st_mode) && st.st_dev == entry.device && st.st_ino == entry.inode && st.st_size == entry.size && file.lastModified() == entry.modified)
        return file
    }
    suspend fun load(context: Context, source: Entry, name: String): ContentPreview? = withContext(Dispatchers.IO) {
        slots.withPermit {
            coroutineContext.ensureActive()
            val key = "${source.path}\u0000${source.device}:${source.inode}:${source.size}:${source.modified}"
            cache.get(key)?.let { return@withPermit it }
            val result = runCatching {
                val file = validate(context, source)
                val extension = name.substringAfterLast('.', "").lowercase()
                val preview = when {
                    extension in setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif","avif") && source.size <= 64L * 1024 * 1024 -> {
                        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                            require(info.size.width in 1..32768 && info.size.height in 1..32768)
                            val scale = minOf(1f, 384f / max(info.size.width, info.size.height))
                            decoder.setTargetSize(max(1,(info.size.width * scale).roundToInt()), max(1,(info.size.height * scale).roundToInt()))
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        }
                        ContentPreview(bitmap = bitmap)
                    }
                    extension in setOf("mp4","m4v","mov","mkv","webm","3gp","avi") -> ContentPreview(bitmap = ThumbnailUtils.createVideoThumbnail(file, Size(384,384), null))
                    extension == "pdf" && source.size <= 64L * 1024 * 1024 -> {
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                require(renderer.pageCount > 0)
                                renderer.openPage(0).use { page ->
                                    require(page.width > 0 && page.height > 0)
                                    val scale = 384f / max(page.width, page.height)
                                    val bitmap = Bitmap.createBitmap(max(1,(page.width * scale).roundToInt()), max(1,(page.height * scale).roundToInt()), Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    ContentPreview(bitmap = bitmap)
                                }
                            }
                        }
                    }
                    extension in setOf("txt","md","csv","log","json","yaml","yml","toml","rs","ts","tsx","js","jsx","py","css","html","xml","ini","sh","sql") -> {
                        val data = FileInputStream(file).use { val buffer=ByteArray(4096); val count=it.read(buffer); buffer.copyOf(count.coerceAtLeast(0)) }
                        if(data.contains(0)) ContentPreview() else ContentPreview(text=data.toString(Charsets.UTF_8).take(2000))
                    }
                    else -> ContentPreview()
                }
                validate(context, source)
                preview
            }.getOrElse { ContentPreview() }
            coroutineContext.ensureActive()
            cache.put(key, result)
            result
        }
    }
}

@Composable internal fun LayoutOptions(grid: Boolean, onGrid: (Boolean) -> Unit) {
    Row(horizontalArrangement=Arrangement.spacedBy(6.dp), verticalAlignment=Alignment.CenterVertically) {
        FilterChip(selected=!grid,onClick={onGrid(false)},label={Text("List view",fontSize=11.sp)},leadingIcon={Icon(Icons.AutoMirrored.Filled.List,null,Modifier.size(16.dp))})
        FilterChip(selected=grid,onClick={onGrid(true)},label={Text("Grid view",fontSize=11.sp)},leadingIcon={Canvas(Modifier.size(16.dp)) { for(x in 0..1) for(y in 0..1) drawRect(Color(0xFF315D47),Offset(x*size.width*.58f,y*size.height*.58f),DrawSize(size.width*.4f,size.height*.4f)) }})
    }
}

@Composable internal fun FilePreview(entry: Entry, source: Entry = entry, large: Boolean = false, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var preview by remember(source) { mutableStateOf<ContentPreview?>(null) }
    LaunchedEffect(source) { preview = PhonePreviews.load(context, source, entry.name) }
    val frame = if(large) modifier.fillMaxWidth().height(145.dp) else modifier.size(54.dp)
    Box(frame.clip(RoundedCornerShape(10.dp)).background(Color(0xFFF0F3EB)), contentAlignment=Alignment.Center) {
        val bitmap=preview?.bitmap
        val text=preview?.text
        when {
            bitmap!=null -> Image(bitmap.asImageBitmap(), "Preview of ${entry.name}", Modifier.fillMaxSize(), contentScale=ContentScale.Fit)
            text!=null -> Text(text, fontSize=if(large)9.sp else 5.sp, lineHeight=if(large)14.sp else 7.sp, color=Color(0xFF56674F), modifier=Modifier.fillMaxSize().background(Color(0xFFFFFDF5)).padding(if(large)10.dp else 4.dp).semantics { contentDescription="Text preview of ${entry.name}" }, overflow=TextOverflow.Clip)
            else -> Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(5.dp)) {
                Canvas(Modifier.size(if(large)34.dp else 22.dp)) {
                    val ink=Color(0xFF7C9771); val w=size.width;val h=size.height
                    if(entry.category=="Videos" || entry.category=="Audio") {
                        val triangle=Path().apply{moveTo(w*.25f,h*.1f);lineTo(w*.9f,h*.5f);lineTo(w*.25f,h*.9f);close()};drawPath(triangle,ink)
                    } else {
                        drawRoundRect(ink,topLeft=Offset(w*.1f,h*.05f),size=DrawSize(w*.8f,h*.9f),cornerRadius=androidx.compose.ui.geometry.CornerRadius(w*.08f),style=Stroke(w*.06f))
                        if(entry.category=="Images") {
                            drawCircle(ink,w*.08f,Offset(w*.68f,h*.28f)); val mountain=Path().apply{moveTo(w*.2f,h*.75f);lineTo(w*.4f,h*.4f);lineTo(w*.75f,h*.75f)};drawPath(mountain,ink,style=Stroke(w*.06f))
                        } else for(y in listOf(.35f,.5f,.65f)) drawLine(ink,Offset(w*.25f,h*y),Offset(w*.75f,h*y),w*.05f)
                    }
                }
                Text(entry.name.substringAfterLast('.',"FILE").uppercase().take(7),fontSize=if(large)10.sp else 7.sp,color=Color(0xFF75856D))
            }
        }
    }
}
