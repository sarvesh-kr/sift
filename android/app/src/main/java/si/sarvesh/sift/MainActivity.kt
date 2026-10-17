package si.sarvesh.sift

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

private val Forest = Color(0xFF315D47)
private val Paper = Color(0xFFF7F9F4)
private val Muted = Color(0xFF6E7D70)
private val Palette = listOf(0xFF608D78,0xFF9CBD9C,0xFFBACDA8,0xFFD1BA8F,0xFFA8B8CC,0xFFBBA9CA,0xFF8EBABC,0xFFD7DFD1).map { Color(it) }
fun sizeLabel(bytes: Long): String {
    var value = bytes.toDouble(); var unit = 0
    while (value >= 1000 && unit < 4) { value /= 1000; unit++ }
    return String.format(Locale.US, if (unit == 0) "%.0f %s" else "%.1f %s", value, arrayOf("B","KB","MB","GB","TB")[unit])
}
class MainActivity : ComponentActivity() {
    private val model: SiftViewModel by viewModels()
    private val recovery: RecoveryViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT), navigationBarStyle=SystemBarStyle.light(android.graphics.Color.TRANSPARENT,android.graphics.Color.TRANSPARENT))
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            SiftTheme { SiftRoot(state, model, recovery, ::requestStorage) }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    private fun requestStorage() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }
}
@Composable fun SiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, background = Paper, surface = Color.White,
        onPrimary = Color.White, onBackground = Color(0xFF203F30), onSurface = Color(0xFF203F30),
        secondaryContainer = Color(0xFFE9F0E1), onSecondaryContainer = Forest), content = content)
}
@Composable private fun Label(text: String) { Text(text, fontSize = 10.sp, letterSpacing = 1.8.sp, color = Muted, fontWeight = FontWeight.Medium) }
@Composable private fun Note(text: String) { Text(text, fontSize = 12.sp, color = Muted, lineHeight = 19.sp) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SiftScreen(state: SiftState, model: SiftViewModel, requestStorage: () -> Unit, initialTab: Int = 0, recoveryTrash: Boolean = false, onHome: () -> Unit = {}) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("sift-ui", android.content.Context.MODE_PRIVATE) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("file-grid", false)) }
    LaunchedEffect(grid) { preferences.edit().putBoolean("file-grid",grid).apply() }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    var review by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<Entry?>(null) }
    var help by remember { mutableStateOf(false) }
    var licenses by remember { mutableStateOf(false) }
    val visible = remember(state.inventory.files, query, filter) { state.inventory.files.filter {
        (filter == "All" || (filter == "Large" && it.size >= 100_000_000) || (filter == "Duplicates" && it.group != null) || it.category == filter) &&
            (it.name.contains(query, true) || it.path.contains(query, true))
    } }
    var failureLimit by remember(state.result) { mutableStateOf(10) }
    val selected = if (tab == 2) state.trashSelected else state.selected
    Scaffold(containerColor = Paper, modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick=onHome,enabled=!state.busy) { Icon(Icons.Default.Home,"Switch workspace") }
                Canvas(Modifier.size(26.dp)) { for (i in 0..2) drawLine(Forest, androidx.compose.ui.geometry.Offset(i*3f, i*size.height/3+3), androidx.compose.ui.geometry.Offset(size.width-i*3f, i*size.height/3+3), strokeWidth=5f, cap=StrokeCap.Round) }
                Text("sift.", Modifier.padding(start=10.dp).weight(1f), fontSize=29.sp, fontWeight=FontWeight.SemiBold, letterSpacing=(-1).sp)
                Text(if(recoveryTrash) "RECOVER DATA" else "CLEAN DATA", fontSize=9.sp, letterSpacing=1.sp, color=Muted)
                IconButton(onClick={help=true}) { Icon(Icons.Default.Info, "About Sift") }
            }
        }, bottomBar = {
            Column {
                if (selected.isNotEmpty()) Surface(color = Forest, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal=20.dp, vertical=8.dp), verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("${selected.size} selected", fontWeight=FontWeight.Medium); TextButton(onClick={model.select(emptySet(), tab==2)}, enabled=!state.busy) { Text("Clear", color=Color.White) } }
                        if (tab==2) TextButton(onClick={review="restore"}, enabled=!state.busy&&state.access) { Text("Restore", color=Color.White) }
                        Button(onClick={review=if(tab==2) "empty" else "live"}, enabled=!state.busy&&state.access&&(tab==2||!state.needsScan),
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFDCE9CC),contentColor=Forest)) { Text(if(tab==2) "Delete" else "Review cleanup") }
                    }
                }
                if(!recoveryTrash) NavigationBar(containerColor=Paper) {
                    listOf("Space" to Icons.Default.Home, "Files" to Icons.AutoMirrored.Filled.List, "Trash" to Icons.Default.Delete).forEachIndexed { i, item ->
                        NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(item.second,null)},label={Text(item.first)})
                    }
                }
            }
        }) { padding ->
        LazyVerticalGrid(columns=GridCells.Adaptive(145.dp),modifier=Modifier.fillMaxSize().padding(padding), horizontalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(start=22.dp,end=22.dp,bottom=24.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            if (state.busy) fullRow { Surface(shape=RoundedCornerShape(16.dp),color=Color(0xFFE9F0E1)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("${state.phase} · ${state.processed}",Modifier.weight(1f).padding(top=8.dp),fontSize=12.sp)
                    TextButton(onClick=model::stop) { Text("Stop") }
                } }
            } }
            if (state.error != null) fullRow { Surface(color=Color(0xFFFAEDDF),shape=RoundedCornerShape(14.dp)) { Column(Modifier.padding(16.dp)) { Text("Could not complete this action",fontWeight=FontWeight.Medium); Note(state.error); TextButton(onClick=model::dismissResult){Text("Dismiss")} } } }
            state.result?.let { result -> fullRow { Surface(shape=RoundedCornerShape(16.dp)) { Column(Modifier.padding(18.dp)) {
                Text("${result.removed.size} ${if(result.removed.size==1) "file" else "files"} ${result.verb}",fontWeight=FontWeight.SemiBold)
                Note(if(result.failed.isEmpty()) "Operation complete. Sift never switches cleanup modes after failure." else "${result.failed.size} not confirmed. Failed files remain selected.")
                result.failed.take(failureLimit).forEach { Note(it.path); Text(it.error,fontSize=12.sp,color=Color(0xFF8D5832)) }
                if(result.failed.size>failureLimit) TextButton(onClick={failureLimit+=25}){Text("Show more failures (${result.failed.size-failureLimit} remaining)")}
                TextButton(onClick=model::dismissResult){Text("Dismiss results")}
            } } } }
            if (state.needsScan) fullRow { Note("Files were restored. Rescan shared storage before further cleanup."); Button(onClick=model::scan,enabled=!state.busy&&state.access){Text("Rescan storage")} }
            if (!state.access) fullRow { AccessCard(requestStorage, state.busy) }
            if (tab==0) {
                fullRow { Column(Modifier.padding(top=12.dp,bottom=8.dp)) { Label("SPACE, THOUGHTFULLY RECLAIMED"); Spacer(Modifier.height(14.dp)); Text("Your phone,\na little lighter.",fontSize=36.sp,lineHeight=42.sp,letterSpacing=(-1.4).sp,fontWeight=FontWeight.SemiBold); Spacer(Modifier.height(12.dp)); Note("Understand what fills your shared storage. Keep what matters; review the rest.") } }
                fullRow { StorageCard(state) }
                fullRow { Button(onClick=model::scan, enabled=state.access&&!state.busy,modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp)) { Icon(Icons.Default.Refresh,null); Spacer(Modifier.width(9.dp)); Text(if(state.scanned) "Scan again" else "Scan shared storage") } }
                if (state.scanned) {
                    fullRow { Label("A LITTLE CLARITY"); Note("${state.inventory.files.size} files indexed · ${state.inventory.skipped} excluded or unreadable · ${state.inventory.elapsedMs/1000.0}s"); if(state.inventory.truncated) Note("Inventory limit reached (100,000 files). Cleanup only uses indexed files."); if(state.inventory.cancelled) Note("Stopped early. This is a partial inventory.") }
                    items(categories.filter { c -> state.inventory.files.any { it.category==c } },span={GridItemSpan(maxLineSpan)}) { c ->
                        val group=state.inventory.files.filter { it.category==c }
                        Surface(shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().clickable{filter=c;tab=1}) { Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(Palette[categories.indexOf(c)])); Column(Modifier.padding(start=14.dp).weight(1f)){Text(c,fontWeight=FontWeight.Medium); Note("${group.size} files")}; Text(sizeLabel(group.sumOf{it.size}),fontWeight=FontWeight.Medium)
                        } }
                    }
                    fullRow { Surface(shape=RoundedCornerShape(16.dp),color=Color(0xFFE9F0E1)) { Column(Modifier.padding(20.dp)) { Text("Keep the memories.\nLose the copies.",fontSize=23.sp,fontWeight=FontWeight.Medium); Spacer(Modifier.height(8.dp)); Note("Matches use SHA-256 and a separate full byte comparison. Hard links are excluded."); TextButton(onClick={model.compare(); filter="Duplicates";tab=1},enabled=!state.busy&&!state.needsScan){Text("Compare exact duplicates")} } } }
                }
                fullRow { Note("100% on-device. No account, uploads, ads, or internet permission. Other apps’ private data, Android/data, and Android/obb are excluded.") }
            } else if (tab==1) {
                fullRow { Text("Everything in its place.",fontSize=28.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.8).sp); Note("Tap a file to inspect its full path. Suggestions are not proof that a file is disposable.") }
                fullRow { OutlinedTextField(query,{query=it},label={Text("Search files")},singleLine=true,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),leadingIcon={Icon(Icons.Default.Search,null)}) }
                fullRow { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) { (listOf("All","Large","Duplicates")+categories).forEach { c -> FilterChip(selected=filter==c,onClick={filter=c},label={Text(c)}) } } }
                fullRow { Row(verticalAlignment=Alignment.CenterVertically) { Note("${visible.size} matches"); Spacer(Modifier.weight(1f)); TextButton(onClick={model.select(state.selected+visible.map{it.path})},enabled=visible.isNotEmpty()&&!state.busy&&!state.needsScan&&state.access){Text("Select all matches")} } }
                if(filter=="Duplicates") fullRow { TextButton(onClick=model::compare,enabled=state.scanned&&!state.busy&&!state.needsScan&&state.access){Text(if(state.compared) "Compare again" else "Compare file contents")}; if(!state.compared) Note("No complete comparison yet. A stopped comparison may show partial groups.") }
                if(visible.isEmpty()) fullRow { EmptyCard(if(state.scanned) "No files match this view." else "Start with a shared-storage scan.") }
                fullRow { LayoutOptions(grid){grid=it} }
                items(visible,key={it.path},span={GridItemSpan(if(grid)1 else maxLineSpan)}) { entry -> FileRow(entry,entry.path in state.selected,!state.busy&&!state.needsScan,{model.toggle(entry.path)},{details=entry},grid=grid) }
            } else {
                fullRow { Text("A second chance.",fontSize=30.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1).sp); Note("Sift trash holds files moved by this app. It is separate from your Gallery’s trash and still uses storage. Restore files, or permanently delete selected items."); Note("Uninstalling Sift removes its trash. Restore anything you need first.") }
                if(state.trashWarnings>0) fullRow { Note("${state.trashWarnings} trash records could not be safely read and were left unchanged. Do not uninstall Sift until you have recovered any files you need.") }
                if(state.trash.isEmpty()) fullRow { EmptyCard(if(state.trashWarnings==0) "Sift trash is empty." else "No validated trash entries to show.") }
                else fullRow { Row(verticalAlignment=Alignment.CenterVertically) { Note("${state.trash.size} files · ${sizeLabel(state.trash.sumOf{it.stored.size})}"); Spacer(Modifier.weight(1f)); TextButton(onClick={model.select(state.trash.map{it.id}.toSet(),true)},enabled=!state.busy){Text("Select all")} } }
                fullRow { LayoutOptions(grid){grid=it} }
                items(state.trash,key={it.id},span={GridItemSpan(if(grid)1 else maxLineSpan)}) { entry -> FileRow(entry.original,entry.id in state.trashSelected,!state.busy,{model.toggle(entry.id,true)},{details=entry.original},source=entry.stored,grid=grid) }
            }
        }
    }
    details?.let { entry -> AlertDialog(onDismissRequest={details=null},title={Text(entry.name)},text={Column(Modifier.verticalScroll(rememberScrollState())) { FilePreview(entry,source=if(tab==2)state.trash.firstOrNull{it.original==entry}?.stored?:entry else entry,large=true); Spacer(Modifier.height(14.dp)); Note(entry.category); Text(sizeLabel(entry.size),fontSize=25.sp); Spacer(Modifier.height(16.dp)); Text(entry.path,fontSize=13.sp); if(entry.group!=null) Note("Verified duplicate group") }},confirmButton={ if(tab!=2) TextButton(onClick={model.select(setOf(entry.path));details=null;review="live"},enabled=!state.busy&&!state.needsScan&&state.access){Text("Review this file only")} else TextButton(onClick={details=null}){Text("Close")} },dismissButton={if(tab!=2) TextButton(onClick={details=null}){Text("Close")}}) }
    if(help) AlertDialog(onDismissRequest={help=false},title={Text("Space, with care.")},text={Column(Modifier.verticalScroll(rememberScrollState())) { Note("Sift ${BuildConfig.VERSION_NAME} for Android 11+\n\nAllow shared-storage access in Android settings, then scan. Sift cannot access other apps’ private storage, system files, or protected Android folders. It does not clear RAM or other apps’ caches.\n\nChoose individual files or all filtered matches. Sift trash is restorable here; it still occupies storage. Uninstalling Sift deletes its trash. Permanent deletion requires typing the action and file count. No secure-wipe claim is made for phone flash storage.\n\nAn unchanged copy is retained for duplicate cleanup unless you explicitly acknowledge removing every selected copy with permanent deletion.\n\nEverything stays on your device. This app has no internet permission. See the repository’s LICENSE and Android notices for licenses.") }},confirmButton={TextButton(onClick={help=false}){Text("Got it")}},dismissButton={TextButton(onClick={help=false;licenses=true}){Text("Open source licenses")}})
    if(licenses) {
        val context=LocalContext.current
        val notice=remember { context.assets.open("OPEN_SOURCE_NOTICES.txt").bufferedReader().use{it.readText()} }
        AlertDialog(onDismissRequest={licenses=false},title={Text("Open source licenses")},text={Text(notice,Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={TextButton(onClick={licenses=false}){Text("Close")}})
    }
    review?.let { target -> CleanupDialog(state,target,grid,{grid=it},onDismiss={review=null},onConfirm={action,phrase,allow -> if(target=="live")model.cleanup(action,phrase,allow) else model.manageTrash(target=="restore",phrase); review=null}) }
}
@Composable private fun AccessCard(request: ()->Unit,busy:Boolean) {
    Surface(shape=RoundedCornerShape(18.dp),color=Color(0xFFE9F0E1)) { Column(Modifier.padding(20.dp)) { Text("Your storage. Your permission.",fontSize=20.sp,fontWeight=FontWeight.Medium); Spacer(Modifier.height(8.dp)); Note("To find large files and duplicates across shared phone storage, allow Sift’s “All files access” in Android settings. Protected app data stays out of reach. Nothing is deleted automatically."); Spacer(Modifier.height(10.dp)); Button(onClick=request,enabled=!busy){Text("Allow shared storage access")} } }
}
@Composable private fun StorageCard(state:SiftState) {
    Surface(shape=RoundedCornerShape(22.dp)) { Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Label("PHONE STORAGE")
        Row(Modifier.padding(vertical=22.dp),verticalAlignment=Alignment.CenterVertically) {
            val fraction=if(state.total>0)(state.total-state.available).toFloat()/state.total else 0f
            Canvas(Modifier.size(90.dp)) { val stroke=Stroke(12.dp.toPx(),cap=StrokeCap.Round); drawArc(Color(0xFFEDF2E8),0f,360f,false,style=stroke); drawArc(Forest,-90f,360f*fraction.coerceIn(0f,1f),false,style=stroke) }
            Column(Modifier.weight(1f).padding(start=20.dp)) { Text(sizeLabel(state.available),fontSize=29.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-1).sp); Note("available of ${sizeLabel(state.total)}"); Spacer(Modifier.height(8.dp)); Text("${sizeLabel(state.inventory.files.sumOf{it.size})} indexed",fontSize=12.sp,color=Forest) }
        }
        Note("Volume capacity includes space used by Android and apps. Sift indexes accessible shared files only.")
    } }
}
@Composable private fun FileRow(entry:Entry,checked:Boolean,enabled:Boolean,toggle:()->Unit,inspect:()->Unit,source:Entry=entry,grid:Boolean=false) {
    if(grid) {
        Surface(shape=RoundedCornerShape(14.dp),color=if(checked)Color(0xFFECF3E4) else Color.White) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Box { Box(Modifier.clickable(onClick=inspect)) { FilePreview(entry,source,large=true) }; Checkbox(checked=checked,onCheckedChange={toggle()},enabled=enabled,modifier=Modifier.semantics{contentDescription="Select ${entry.name}"}.align(Alignment.TopStart).background(Color.White.copy(alpha=.9f),RoundedCornerShape(8.dp))) }
            Column(Modifier.fillMaxWidth().clickable(onClick=inspect).padding(top=10.dp)) { Text(entry.name,fontSize=13.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis); Text(entry.path,fontSize=9.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis); Text("${entry.category} · ${sizeLabel(entry.size)}",fontSize=10.sp,color=Forest,modifier=Modifier.padding(top=6.dp)) }
            TextButton(onClick=inspect,contentPadding=PaddingValues(0.dp)){Text("Details",fontSize=11.sp)}
        } }; return
    }
    Surface(shape=RoundedCornerShape(14.dp),color=if(checked)Color(0xFFECF3E4) else Color.White) { Row(Modifier.fillMaxWidth().padding(start=2.dp,end=12.dp,top=10.dp,bottom=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=checked,onCheckedChange={toggle()},enabled=enabled,modifier=Modifier.semantics{contentDescription="Select ${entry.name}"})
        Box(Modifier.clickable(onClick=inspect).padding(end=10.dp)) { FilePreview(entry,source) }
        Column(Modifier.weight(1f).clickable(onClick=inspect).padding(vertical=6.dp)) { Text(entry.name,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis); Text(entry.path,fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis); Text("${entry.category} · ${sizeLabel(entry.size)}",fontSize=11.sp,color=Forest,modifier=Modifier.padding(top=6.dp)) }
        IconButton(onClick=inspect){Icon(Icons.Default.MoreVert,"Details for ${entry.name}")}
    } }
}
@Composable private fun EmptyCard(text:String) { Surface(shape=RoundedCornerShape(18.dp)){Column(Modifier.fillMaxWidth().padding(28.dp)){Text(text,fontSize=18.sp); Spacer(Modifier.height(8.dp)); Note("Choose Space to scan, or change the file filters.")}} }

@Composable private fun CleanupDialog(state:SiftState,target:String,grid:Boolean,onGrid:(Boolean)->Unit,onDismiss:()->Unit,onConfirm:(Action,String,Boolean)->Unit) {
    var action by remember { mutableStateOf(Action.TRASH) }
    var phrase by remember { mutableStateOf("") }
    var allow by remember { mutableStateOf(false) }
    val selectedEntries=if(target=="live")state.inventory.files.filter{it.path in state.selected} else state.trash.filter{it.id in state.trashSelected}.map{it.original}
    val count=selectedEntries.size
    val permanent=target=="empty"||(target=="live"&&action==Action.DELETE)
    val conflicts=if(target=="live") state.inventory.files.filter{it.group!=null}.groupBy{it.group}.values.count{group->group.all{it.path in state.selected}} else 0
    LaunchedEffect(action,state.selected,state.trashSelected){phrase="";allow=false}
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(target=="restore")"Restore these files?" else "Ready to let these go?")},
        text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("$count ${if(count==1)"selected file" else "selected files"} · ${sizeLabel(selectedEntries.sumOf{it.size})}",fontWeight=FontWeight.Medium)
            if(target=="live") {
                listOf(Action.TRASH to "Move to Sift trash",Action.DELETE to "Delete permanently").forEach{(mode,label)->Row(Modifier.fillMaxWidth().clickable{action=mode},verticalAlignment=Alignment.CenterVertically){RadioButton(selected=action==mode,onClick={action=mode});Text(label,fontSize=14.sp)}}
                Note(if(action==Action.TRASH)"Files stay on this phone and can be restored in Sift. Trash still occupies storage. Uninstalling Sift removes its trash." else "These files bypass Sift trash. Sift cannot undo this. Deletion is not secure erasure of phone flash or backups.")
            }
            if(target=="empty")Note("These files will be removed from Sift trash permanently. Sift cannot undo this.")
            if(target=="restore")Note("Files return to their original paths. Existing files are never overwritten. Rescan after restoring.")
            LayoutOptions(grid,onGrid)
            LazyVerticalGrid(columns=GridCells.Fixed(if(grid)2 else 1),modifier=Modifier.fillMaxWidth().heightIn(max=240.dp),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(selectedEntries.take(50)) { entry ->
                    val source=if(target=="live")entry else state.trash.firstOrNull{it.original==entry}?.stored?:entry
                    if(grid)Column { FilePreview(entry,source,large=true);Text(entry.name,fontSize=12.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text(entry.path,fontSize=9.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis) }
                    else Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) { FilePreview(entry,source);Column(Modifier.weight(1f)){Text(entry.name,fontSize=13.sp,fontWeight=FontWeight.Medium);Text(entry.path,fontSize=10.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)} }
                }
            }
            if(count>50)Note("Showing 50 of $count paths. Review the full selection in Files before continuing.")
            if(conflicts>0) {
                if(permanent)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top){Checkbox(checked=allow,onCheckedChange={allow=it});Text("Allow deletion of every selected copy in $conflicts duplicate group(s).",fontSize=12.sp)}
                else Text("Keep at least one unchanged copy in each duplicate group before moving files to trash.",color=Color(0xFF8D5832),fontSize=12.sp)
            }
            if(permanent)OutlinedTextField(value=phrase,onValueChange={phrase=it},label={Text("Type ${confirmation(count)}")},singleLine=true,modifier=Modifier.fillMaxWidth())
        }},
        confirmButton={TextButton(onClick={onConfirm(action,phrase,allow)},enabled=count>0&&(!permanent||phrase==confirmation(count))&&(conflicts==0||(permanent&&allow))){Text(if(target=="restore")"Restore files" else if(permanent)"Delete permanently" else "Move to Sift trash")}},
        dismissButton={TextButton(onClick=onDismiss){Text("Keep exploring")}})
}

private fun LazyGridScope.fullRow(content: @Composable ColumnScope.() -> Unit) {
    item(span={GridItemSpan(maxLineSpan)}) { Column(Modifier.fillMaxWidth(),content=content) }
}
