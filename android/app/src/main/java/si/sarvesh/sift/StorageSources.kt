package si.sarvesh.sift

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import java.io.File

internal data class StorageSource(
    val id: String,
    val label: String,
    val root: File,
    val removable: Boolean,
)

internal fun storageSources(context: Context): List<StorageSource> =
    context
        .getSystemService(StorageManager::class.java)
        .storageVolumes
        .mapNotNull { volume ->
            val directory = volume.directory ?: return@mapNotNull null
            if (
                volume.state != Environment.MEDIA_MOUNTED &&
                    volume.state != Environment.MEDIA_MOUNTED_READ_ONLY
            )
                return@mapNotNull null
            StorageSource(
                volume.uuid ?: "primary",
                if (volume.isPrimary) "Internal storage" else volume.getDescription(context),
                directory.canonicalFile,
                volume.isRemovable,
            )
        }
        .distinctBy { it.root.path }

@Composable
internal fun StorageSourcePicker(
    context: Context,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val sources = remember(context, expanded) { storageSources(context) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Storage: ${sources.firstOrNull {it.id==selected}?.label ?: "Choose a mounted device"}"
            )
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            sources.forEach { source ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(source.label)
                            Text(source.root.path, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(source.id)
                    },
                )
            }
            if (sources.isEmpty())
                DropdownMenuItem(
                    text = { Text("No mounted shared storage") },
                    onClick = { expanded = false },
                    enabled = false,
                )
        }
    }
}
