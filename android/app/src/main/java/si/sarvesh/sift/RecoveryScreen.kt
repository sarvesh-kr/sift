package si.sarvesh.sift

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val RecoveryGreen = Color(0xFF315D47)
private val RecoveryPaper = Color(0xFFF7F9F4)

@Composable
private fun RecoveryNote(text: String) =
    Text(text, fontSize = 12.sp, lineHeight = 19.sp, color = Color(0xFF7D8D73))

@Composable
private fun RecoveryEyebrow(text: String) =
    Text(text, fontSize = 9.sp, letterSpacing = 1.5.sp, color = Color(0xFF899D7A))

@Composable
internal fun SiftRoot(
    state: SiftState,
    model: SiftViewModel,
    recovery: RecoveryViewModel,
    requestStorage: () -> Unit,
) {
    var mode by rememberSaveable { mutableStateOf("home") }
    val recoveryState by recovery.state.collectAsStateWithLifecycle()
    val holder = rememberSaveableStateHolder()
    BackHandler(mode != "home") { if (!state.busy && !recoveryState.busy) mode = "home" }
    when (mode) {
        "clean" ->
            holder.SaveableStateProvider("clean") {
                SiftScreen(
                    state,
                    model,
                    requestStorage,
                    onHome = { if (!state.busy) mode = "home" },
                )
            }
        "recover" ->
            holder.SaveableStateProvider("recover") {
                RecoveryScreen(
                    recoveryState,
                    recovery,
                    onHome = { if (!recoveryState.busy) mode = "home" },
                    requestStorage = requestStorage,
                )
            }
        else -> ModeHomePhone(onSelect = { mode = it })
    }
}

@Composable
private fun ModeHomePhone(onSelect: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(RecoveryPaper)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "sift.",
                fontSize = 38.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-2).sp,
                modifier = Modifier.weight(1f),
            )
            RecoveryEyebrow("ON YOUR DEVICE")
        }
        RecoveryEyebrow("YOUR SPACE. YOUR NEXT CHAPTER.")
        Text(
            "Make room.\nBring something back.",
            fontSize = 37.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-1.5).sp,
        )
        RecoveryNote(
            "One workspace for the files you have. Another for the ones you’re looking for."
        )
        ModeCard(
            "Clean data",
            "Explore storage, recognize files, compare copies, and choose what goes.",
            false,
        ) {
            onSelect("clean")
        }
        ModeCard(
            "Recover data",
            "Find surviving hidden copies, cached photos, and embedded files on this phone. Review previews and recover what remains.",
            true,
        ) {
            onSelect("recover")
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = RecoveryGreen)
            RecoveryNote("No account. No uploads. Every decision stays yours.")
        }
    }
}

@Composable
private fun ModeCard(title: String, note: String, recovery: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = if (recovery) Color(0xFFEAF0E0) else Color.White,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
    ) {
        Column(Modifier.padding(25.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                if (recovery) Icons.Default.Refresh else Icons.Default.Delete,
                null,
                Modifier.size(29.dp),
                tint = RecoveryGreen,
            )
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
            RecoveryNote(note)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (recovery) "Open recovery workspace" else "Open cleanup workspace",
                    fontSize = 12.sp,
                    color = RecoveryGreen,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        }
    }
}

private class LocalImagePicker : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class LocalDestinationPicker : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?) =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryScreen(
    state: RecoveryState,
    model: RecoveryViewModel,
    onHome: () -> Unit,
    requestStorage: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("sift-ui", Context.MODE_PRIVATE) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("recovery-grid", false)) }
    LaunchedEffect(grid) { preferences.edit().putBoolean("recovery-grid", grid).apply() }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var help by remember { mutableStateOf(false) }
    var rootReview by remember { mutableStateOf(false) }
    var folderHelp by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<RecoveryFile?>(null) }
    var review by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var carving by rememberSaveable { mutableStateOf(false) }
    val imagePicker =
        rememberLauncherForActivityResult(LocalImagePicker()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                model.scan(uri, carving)
            }
        }
    val destinationPicker =
        rememberLauncherForActivityResult(LocalDestinationPicker()) { uri ->
            if (uri != null) {
                val ids = review?.toSet() ?: emptySet()
                review = null
                model.export(uri, ids)
            }
        }
    LaunchedEffect(state.session) {
        query = ""
        category = "All"
        folderHelp = false
    }
    val visible =
        remember(state.report, query, category) {
            state.report?.files?.filter {
                (category == "All" || it.category == category) &&
                    ("${it.name} ${it.originalPath.orEmpty()} ${it.origin.orEmpty()} ${it.format} ${it.offset.toString(16)}"
                        .contains(query, true))
            } ?: emptyList()
        }
    Scaffold(
        containerColor = RecoveryPaper,
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onHome, enabled = !state.busy) {
                    Icon(Icons.Default.Home, "Switch workspace")
                }
                Text(
                    "sift.",
                    fontSize = 29.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                RecoveryEyebrow("RECOVER DATA")
                IconButton(onClick = { help = true }) { Icon(Icons.Default.Info, "Recovery help") }
            }
        },
        bottomBar = {
            if (state.selected.isNotEmpty())
                Surface(color = RecoveryGreen, contentColor = Color.White) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${state.selected.size} selected")
                            TextButton(
                                onClick = { model.select(emptySet()) },
                                enabled = !state.busy,
                            ) {
                                Text("Clear selection", color = Color.White)
                            }
                        }
                        Button(
                            onClick = { review = state.selected.toList() },
                            enabled = !state.busy,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFDFEACF),
                                    contentColor = RecoveryGreen,
                                ),
                        ) {
                            Text("Recover selected")
                        }
                    }
                }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.fillMaxSize().padding(padding).testTag("recovery-files"),
            contentPadding = PaddingValues(22.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = Color(0xFF203F30), shape = RoundedCornerShape(22.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(25.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        RecoveryEyebrow("LOOK BACK. MOVE FORWARD.")
                        Text(
                            "Some things deserve\na second chance.",
                            fontSize = 31.sp,
                            lineHeight = 37.sp,
                            letterSpacing = (-1).sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5E8),
                        )
                        Text(
                            "Find what remains. Understand the evidence. Save a copy without changing the source.",
                            fontSize = 12.sp,
                            lineHeight = 20.sp,
                            color = Color(0xFFB5C7A7),
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecoveryPanel(
                    "RECOVERY ON THIS PHONE",
                    "Look for what remains on your phone.",
                    "Search accessible shared cache, hidden and .nomedia folders for surviving files and embedded photos. Preview results and see their dimensions and source evidence. A thumbnail may be smaller than your original photo. No bin or disk image is required.",
                ) {
                    StorageSourcePicker(context, state.storageId, !state.busy, model::selectStorage)
                    Button(
                        onClick = {
                            if (android.os.Environment.isExternalStorageManager()) model.scanPhone()
                            else requestStorage()
                        },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.storageId == "primary") "Scan this phone"
                            else "Scan selected storage"
                        )
                    }
                    RecoveryNote(
                        "This searches surviving copies; original deletion status is unknown. It does not read deleted internal-storage sectors or other apps’ private data. Windows provides a separate direct NTFS undelete scan with administrator consent."
                    )
                    TextButton(onClick = { rootReview = true }, enabled = !state.busy) {
                        Text("Rooted deep scan · experimental")
                    }
                    TextButton(onClick = { advanced = !advanced }, enabled = !state.busy) {
                        Text(if (advanced) "Hide advanced image tools" else "Advanced image tools")
                    }
                }
            }
            if (advanced) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecoveryPanel(
                        "ADVANCED / NTFS IMAGE RECOVERY",
                        "Find deleted files and original paths.",
                        "Choose an NTFS volume or disk image (.img, .dd, .raw). Read deleted filesystem records, original filenames and folder paths; reconstruct surviving resident and fragmented contents. No All files access is required.",
                    ) {
                        Button(
                            onClick = {
                                carving = false
                                imagePicker.launch(arrayOf("*/*"))
                            },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Default.Search, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (state.report == null) "Choose disk image"
                                else "Scan another image"
                            )
                        }
                        TextButton(
                            onClick = {
                                carving = true
                                imagePicker.launch(arrayOf("*/*"))
                            },
                            enabled = !state.busy,
                        ) {
                            Text("Advanced: search file signatures")
                        }
                        RecoveryNote(
                            "Signature search is separate: six contiguous formats, generated names, unknown deletion status. It can include existing files."
                        )
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecoveryNote(
                    "Android does not expose deleted internal-storage sectors through normal app permissions. Sift cannot undelete encrypted phone storage or reverse TRIM. Stop using the affected drive; create an image with a disk-imaging tool. Sift does not create images. Save recovered copies to another physical drive."
                )
            }
            if (state.busy)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(color = Color(0xFFE9F0E1), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(17.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(
                                state.progress?.phase ?: "Opening source or destination",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            RecoveryNote(
                                when (state.progress?.unit) {
                                    "records" ->
                                        "${state.progress?.bytes?:0} / ${state.progress?.total?:0} records · ${state.progress?.found?:0} files"
                                    "entries" -> "${state.progress?.bytes?:0} entries examined"
                                    else ->
                                        "${sizeLabel(state.progress?.bytes?:0)} / ${sizeLabel(state.progress?.total?:0)} · ${state.progress?.found?:0} files"
                                }
                            )
                            TextButton(onClick = model::stop) { Text("Stop recovery") }
                        }
                    }
                }
            if (state.error != null)
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecoveryPanel(
                        "ACTION NOT COMPLETED",
                        "Could not complete recovery",
                        state.error,
                    ) {
                        TextButton(onClick = model::dismiss) { Text("Dismiss recovery message") }
                    }
                }
            state.result?.let { result ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecoveryPanel(
                        "SAVED COPIES",
                        "${result.exported.size} verified ${if(result.exported.size==1)"copy" else "copies"} saved${if(result.cancelled)" · stopped" else ""}",
                        "Saved in " +
                            (Uri.parse(result.directory)
                                .lastPathSegment
                                ?.substringAfterLast('/')
                                ?.takeIf { it.startsWith("Sift-recovered-") }
                                ?: "your selected recovery folder"),
                    ) {
                        TextButton(
                            onClick = {
                                runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW)
                                                .setDataAndType(
                                                    Uri.parse(result.directory),
                                                    "vnd.android.document/directory",
                                                )
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        )
                                    }
                                    .onFailure { folderHelp = true }
                            }
                        ) {
                            Text("Open recovered files")
                        }
                        if (folderHelp)
                            RecoveryNote(
                                "Open your Files app and find the Sift-recovered folder in the destination you selected."
                            )
                        RecoveryNote(
                            if (result.manifest != null)
                                "recovery-manifest.json records offsets, evidence, SHA-256 hashes, and per-file outcomes."
                            else "No manifest saved."
                        )
                        result.warning?.let { RecoveryNote(it) }
                        result.failed.take(20).forEach { RecoveryNote("${it.id}: ${it.error}") }
                        if (result.failed.size > 20)
                            RecoveryNote(
                                "${result.failed.size-20} more failures are recorded in the manifest."
                            )
                        TextButton(onClick = model::dismiss) { Text("Dismiss recovery results") }
                    }
                }
            }
            state.report?.let { report ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        RecoveryEyebrow("WHAT THE IMAGE CONTAINS")
                        Text(
                            "${report.files.size} candidate files",
                            fontSize = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        RecoveryNote(report.source)
                        TextButton(onClick = model::closeImage, enabled = !state.busy) {
                            Text("Close scan")
                        }
                        RecoveryNote(
                            if (report.method == "ntfs-deleted-records-v1")
                                "${report.scannedBytes} file records examined · ${report.elapsedMs/1000.0}s · ${report.rejectedHeaders} unreadable/unused records skipped"
                            else
                                "${sizeLabel(report.scannedBytes)} / ${sizeLabel(report.sourceBytes)} scanned · ${report.elapsedMs/1000.0}s · ${report.rejectedHeaders} skipped sources/headers"
                        )
                        if (report.cancelled || report.limited)
                            Text(
                                "Partial scan: ${if(report.cancelled)"stopped" else "limit reached"}",
                                fontSize = 12.sp,
                                color = RecoveryGreen,
                            )
                        RecoveryNote(
                            if (report.method == "android-root-signature-scan-v1")
                                "Rooted raw-storage signature scan. Existing files may appear. Deleted status and original paths are unknown; encryption and TRIM may prevent recovery."
                            else if (report.method == "android-surviving-copies-v1")
                                "Surviving contents found in accessible shared cache and hidden folders. Original deletion status is unknown; some may be thumbnails or copies of files you still have. No bin was scanned."
                            else if (report.method == "ntfs-deleted-records-v1")
                                "Entries are marked deleted in NTFS. Original paths come from surviving records. Reused clusters are blocked; unallocated bytes can still be overwritten or TRIMmed. Contents are not guaranteed intact."
                            else
                                "Signature search: deleted status and original paths are unknown. Existing files may appear. Format checks do not guarantee readable contents."
                        )
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        query,
                        { query = it },
                        label = { Text("Search recovery results") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${visible.size} matches",
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    model.select(
                                        state.selected +
                                            visible.filter { it.recoverable }.map { it.id }
                                    )
                                },
                                enabled = !state.busy && visible.isNotEmpty(),
                            ) {
                                Text("Select all matches")
                            }
                        }
                        LayoutOptions(grid) { grid = it }
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) {
                                Text("Recovery category: $category")
                            }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                (listOf("All") + report.files.map { it.category }.distinct())
                                    .forEach { c ->
                                        DropdownMenuItem(
                                            text = { Text(c) },
                                            onClick = {
                                                category = c
                                                expanded = false
                                            },
                                        )
                                    }
                            }
                        }
                    }
                }
                items(
                    visible,
                    key = { it.id },
                    span = { GridItemSpan(if (grid) 1 else maxLineSpan) },
                ) { file ->
                    RecoveryFileCard(
                        file,
                        file.id in state.selected,
                        grid,
                        !state.busy,
                        state.session,
                        model,
                        { model.toggle(file.id) },
                        { details = file },
                    )
                }
                if (visible.isEmpty())
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        RecoveryNote(
                            if (report.files.isEmpty())
                                "No matching deleted entries found. This does not prove that no data is recoverable; overwritten records, unsupported filesystem features, encryption, and limits affect results."
                            else "No candidates match this view."
                        )
                    }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { help = true }) { Text("Capabilities & limits") }
                RecoveryNote(
                    "Contiguous carving preview · no source writes · no internet permission"
                )
            }
        }
    }
    details?.let { file ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(file.name) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RecoveryThumbnail(file, model, state.session, true, !state.busy)
                    RecoveryNote(
                        "${file.category} · ${sizeLabel(file.size)}\nOffset ${file.offset} bytes / 0x${file.offset.toString(16)}"
                    )
                    Text(file.evidence, fontSize = 12.sp)
                    RecoveryNote(
                        "SHA-256\n${file.sha256.ifEmpty { "Calculated and verified when recovering" }}"
                    )
                    file.origin?.let { RecoveryNote("Found in: $it") }
                    if (file.width != null && file.height != null)
                        RecoveryNote("${file.width} × ${file.height} pixels")
                    RecoveryNote(
                        file.originalPath?.let { "$it · marked deleted in NTFS" }
                            ?: if (file.origin != null)
                                "Surviving cached contents; original deletion status unknown."
                            else "Original path and deletion status: unknown (signature search)."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        review = listOf(file.id)
                        details = null
                    },
                    enabled = !state.busy && file.recoverable,
                ) {
                    Text("Recover this file only")
                }
            },
            dismissButton = { TextButton(onClick = { details = null }) { Text("Close") } },
        )
    }
    review?.let { ids ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) review = null },
            title = { Text("Save a second copy.") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("${ids.size} selected candidates")
                    RecoveryNote(
                        "Choose a destination on another physical drive when recovering from an affected disk. Sift creates a new recovery folder, groups files by category, compares SHA-256 hashes, and reads copies back. Existing files are never intentionally replaced. The source stays read-only."
                    )
                    RecoveryNote(
                        "A JSON manifest records original paths, deleted-record evidence, hashes and outcomes. Output names include a record ID to prevent collisions. Readable or complete contents cannot be guaranteed."
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { destinationPicker.launch(null) }, enabled = !state.busy) {
                    Text("Choose destination & save")
                }
            },
            dismissButton = {
                TextButton(onClick = { review = null }, enabled = !state.busy) {
                    Text("Keep reviewing")
                }
            },
        )
    }
    if (rootReview)
        AlertDialog(
            onDismissRequest = { rootReview = false },
            title = { Text("Read-only rooted storage scan") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecoveryNote(
                        "This requires an already rooted phone and your root manager’s consent. Sift does not root or unlock your device. A small helper reads the selected mounted storage device (internal userdata or a supported removable card); it has no source write or delete command. Mounted ext4, F2FS, FAT, exFAT and NTFS devices may be accessed when your root manager permits it.\n\nIt searches six contiguous formats (JPEG, PNG, WebP, PDF, ZIP, WAV). Existing files may appear; original names, folders and deletion status are unknown. Per-file encryption, TRIM and overwrites may leave no recoverable contents even with root. Files above 512 MiB are unsupported.\n\nSave recovered files to a different storage device. Same-device destinations are refused before creating output."
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rootReview = false
                        model.scanRootedPhone()
                    }
                ) {
                    Text("Request root & scan read-only")
                }
            },
            dismissButton = { TextButton(onClick = { rootReview = false }) { Text("Cancel") } },
        )
    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("What recovery can do.") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecoveryNote(
                        "NTFS undelete reads deleted master-file-table records from a local image. It retains original names and surviving folder paths and follows fragmented or sparse runs. Reused clusters are blocked. No bin is used for recovery.\n\nAndroid does not expose raw encrypted internal-storage sectors to ordinary apps. Sift cannot undelete internal phone storage, reverse TRIM, or decrypt missing encryption keys. The desktop Windows app can scan an unlocked NTFS drive with administrator consent.\n\nFAT/exFAT, APFS, ext, locked BitLocker, compressed/EFS data, and NTFS extension records are unsupported. Limits: 10,000 entries, 2 million MFT records, 64 GiB per file, 128 MiB bitmap. Original parent paths may be unknown.\n\nAdvanced signature search remains separate and supports six contiguous formats only, with unknown deletion status. Save recovered copies to another physical drive. No recovery result is guaranteed intact. Recovered files are never executed by Sift."
                    )
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("Got it") } },
        )
}

@Composable
private fun RecoveryPanel(
    eyebrow: String,
    title: String,
    note: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(21.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RecoveryEyebrow(eyebrow)
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Medium)
            RecoveryNote(note)
            content()
        }
    }
}

@Composable
private fun RecoveryThumbnail(
    file: RecoveryFile,
    model: RecoveryViewModel,
    session: String,
    large: Boolean,
    enabled: Boolean,
) {
    var bitmap by remember(session, file.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(session, file.id, enabled) {
        bitmap = if (enabled) model.preview(file.id) else null
    }
    val frame = if (large) Modifier.fillMaxWidth().height(135.dp) else Modifier.size(54.dp)
    Box(
        frame.clip(RoundedCornerShape(10.dp)).background(Color(0xFFEDF2E6)),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                it.asImageBitmap(),
                "Preview of ${file.name}",
                Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
            ?: Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Info, null, tint = Color(0xFF90A27E))
                Text(file.format.uppercase(), fontSize = 9.sp, color = Color(0xFF819672))
            }
    }
}

@Composable
private fun RecoveryFileCard(
    file: RecoveryFile,
    selected: Boolean,
    grid: Boolean,
    enabled: Boolean,
    session: String,
    model: RecoveryViewModel,
    onSelect: () -> Unit,
    onDetails: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = if (selected) Color(0xFFEDF4E3) else Color.White,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (grid)
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.format.uppercase(), fontSize = 10.sp, modifier = Modifier.weight(1f))
                    Checkbox(
                        selected,
                        onCheckedChange = { onSelect() },
                        enabled = enabled && file.recoverable,
                        modifier = Modifier.semantics { contentDescription = "Select ${file.name}" },
                    )
                }
                Column(
                    Modifier.clickable(onClick = onDetails),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RecoveryThumbnail(file, model, session, true, enabled)
                    Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    RecoveryNote(
                        file.originalPath
                            ?: "${sizeLabel(file.size)} · 0x${file.offset.toString(16)}"
                    )
                    if (!file.recoverable) RecoveryNote("Unavailable: ${file.evidence}")
                    file.origin?.let { RecoveryNote(file.evidence) }
                    if (file.width != null && file.height != null)
                        RecoveryNote("${file.width} × ${file.height} pixels")
                }
            }
        else
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    selected,
                    onCheckedChange = { onSelect() },
                    enabled = enabled && file.recoverable,
                    modifier = Modifier.semantics { contentDescription = "Select ${file.name}" },
                )
                Row(
                    Modifier.weight(1f).clickable(onClick = onDetails),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RecoveryThumbnail(file, model, session, false, enabled)
                    Column(
                        Modifier.padding(start = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        file.origin?.let { RecoveryNote(file.evidence) }
                        if (file.width != null && file.height != null)
                            RecoveryNote("${file.width} × ${file.height} pixels")
                        RecoveryNote(
                            "${file.category} · ${sizeLabel(file.size)}\n${file.originalPath ?: "Offset 0x${file.offset.toString(16)}"}"
                        )
                    }
                }
            }
    }
}
