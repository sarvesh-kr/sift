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
    BackHandler(mode != "home") {
        if (!state.busy && !recoveryState.busy) mode = if (mode == "trash") "recover" else "home"
    }
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
                    onTrash = { if (!recoveryState.busy) mode = "trash" },
                )
            }
        "trash" ->
            holder.SaveableStateProvider("trash") {
                SiftScreen(
                    state,
                    model,
                    requestStorage,
                    initialTab = 2,
                    recoveryTrash = true,
                    onHome = { if (!state.busy) mode = "recover" },
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
            "Restore from Sift trash, or search a disk image for recognizable file contents.",
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
    onTrash: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("sift-ui", Context.MODE_PRIVATE) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("recovery-grid", false)) }
    LaunchedEffect(grid) { preferences.edit().putBoolean("recovery-grid", grid).apply() }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("All") }
    var help by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<RecoveryFile?>(null) }
    var review by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val imagePicker =
        rememberLauncherForActivityResult(LocalImagePicker()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                model.scan(uri)
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
    }
    val visible =
        remember(state.report, query, category) {
            state.report?.files?.filter {
                (category == "All" || it.category == category) &&
                    ("${it.name} ${it.format} ${it.offset.toString(16)}".contains(query, true))
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
            modifier = Modifier.fillMaxSize().padding(padding),
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
                    "01 / STILL IN SIFT TRASH?",
                    "Keep original names and paths.",
                    "Restore files previously moved to Sift trash. Gallery / Photos trash is separate; open that app to restore its files.",
                ) {
                    OutlinedButton(onClick = onTrash, enabled = !state.busy) {
                        Text("Open Sift trash")
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                RecoveryPanel(
                    "02 / SEARCH AN IMAGE",
                    "Look for file contents.",
                    "Choose a local raw image (.img, .dd, .raw). Supported contiguous files: JPEG, PNG, WebP, PDF, ZIP, WAV. No All files access is required for this scan.",
                ) {
                    Button(
                        onClick = { imagePicker.launch(arrayOf("*/*")) },
                        enabled = !state.busy,
                    ) {
                        Icon(Icons.Default.Search, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.report == null) "Choose disk image" else "Scan another image"
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
                                "${sizeLabel(state.progress?.bytes?:0)} / ${sizeLabel(state.progress?.total?:0)} · ${state.progress?.found?:0} files"
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
                        result.directory,
                    ) {
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
                            Text("Close image")
                        }
                        RecoveryNote(
                            "${sizeLabel(report.scannedBytes)} / ${sizeLabel(report.sourceBytes)} scanned · ${report.elapsedMs/1000.0}s · ${report.rejectedHeaders} incomplete or unrecognized headers excluded"
                        )
                        if (report.cancelled || report.limited)
                            Text(
                                "Partial scan: ${if(report.cancelled)"stopped" else "limit reached"}",
                                fontSize = 12.sp,
                                color = RecoveryGreen,
                            )
                        RecoveryNote(
                            "These byte ranges may include existing files. Deleted status and original names / folders are unknown. Names are generated from offsets. Format checks do not guarantee readable contents. PDF results stop at the first footer; later revisions may be missing."
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
                                onClick = { model.select(state.selected + visible.map { it.id }) },
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
                                "No supported boundaries found. This does not prove that no data is recoverable; unsupported formats, fragmentation, encryption, and scan limits affect results."
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
                    RecoveryNote("SHA-256\n${file.sha256}")
                    RecoveryNote("Original path and deletion status: unknown.")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        review = listOf(file.id)
                        details = null
                    },
                    enabled = !state.busy,
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
                        "Names are generated from offsets. No guarantee is made that a candidate was deleted or will open correctly. A JSON manifest records evidence and outcomes."
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
    if (help)
        AlertDialog(
            onDismissRequest = { help = false },
            title = { Text("What recovery can do.") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RecoveryNote(
                        "Restore files retained in Sift trash. Gallery / Photos trash is managed in those apps. Image scanning finds contiguous JPEG, PNG, WebP, PDF, ZIP, and WAV bytes in a local, user-picked image. It does not scan raw phone sectors.\n\nNo NTFS, FAT, exFAT, APFS, or ext metadata parsing; no original names, folder reconstruction, fragmented-file reassembly, decryption, or image creation. Incomplete files, ZIP64, split archives, and files above 512 MiB are unsupported. ZIP payload CRCs and PDF contents are not verified.\n\nLimits: 10,000 results, 50,000 header attempts, 512 MiB per file, and bounded validation reads. A scan may stop early. Selected copies get SHA-256 comparison and destination read-back.\n\nEncryption, overwriting, TRIM, or physical damage may make recovery impossible. No tool can promise to recover everything.\n\nUninstalling Sift removes Sift trash. Install updates over the existing app. Recovered files are never executed by Sift."
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
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = "Select ${file.name}" },
                    )
                }
                Column(
                    Modifier.clickable(onClick = onDetails),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RecoveryThumbnail(file, model, session, true, enabled)
                    Text(file.name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    RecoveryNote("${sizeLabel(file.size)} · 0x${file.offset.toString(16)}")
                }
            }
        else
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    selected,
                    onCheckedChange = { onSelect() },
                    enabled = enabled,
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
                        RecoveryNote(
                            "${file.category} · ${sizeLabel(file.size)}\nOffset 0x${file.offset.toString(16)}"
                        )
                    }
                }
            }
    }
}
