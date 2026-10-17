package si.sarvesh.sift

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SiftState(
    val access: Boolean = false, val busy: Boolean = false, val phase: String = "", val processed: Int = 0,
    val inventory: Inventory = Inventory(), val scanned: Boolean = false, val compared: Boolean = false,
    val selected: Set<String> = emptySet(), val trashSelected: Set<String> = emptySet(), val trash: List<TrashEntry> = emptyList(),
    val result: Outcome? = null, val error: String? = null, val total: Long = 0, val available: Long = 0,
    val needsScan: Boolean = false
)
class SiftViewModel(application: Application) : AndroidViewModel(application) {
    private val engine = StorageEngine(application)
    private val mutable = MutableStateFlow(SiftState())
    val state = mutable.asStateFlow()
    init { refresh() }
    fun refresh() {
        mutable.update { it.copy(access = Environment.isExternalStorageManager()) }
        if (!mutable.value.busy) work("Reading storage") { updateStorage() }
    }
    private fun updateStorage() {
        val trash = engine.trash(); val (total, available) = engine.space()
        mutable.update { it.copy(trash = trash, total = total, available = available,
            trashSelected = it.trashSelected.intersect(trash.map { f -> f.id }.toSet())) }
    }
    private fun progress(files: Int, phase: String) { mutable.update { it.copy(processed = files, phase = phase) } }
    private fun work(label: String, action: () -> Unit) {
        if (mutable.value.busy) return
        engine.cancel.set(false)
        mutable.update { it.copy(busy = true, phase = label, processed = 0, error = null) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "Android could not complete this operation.") } }
            finally { mutable.update { it.copy(busy = false, phase = "") } }
        }
    }
    private fun access() { check(Environment.isExternalStorageManager()) { "Allow shared storage access in Android settings first." } }
    fun scan() = work("Indexing shared storage") {
        access(); val report = engine.scan(::progress)
        mutable.update { it.copy(inventory = report, scanned = true, compared = false, selected = emptySet(), needsScan = false, result = null) }
        updateStorage()
    }
    fun compare() = work("Comparing contents") {
        access(); check(!mutable.value.needsScan) { "Rescan after restoring files." }
        val report = engine.compare(::progress)
        mutable.update { it.copy(inventory = report, compared = !report.cancelled, selected = emptySet()) }
    }
    fun select(paths: Set<String>, trash: Boolean = false) { if (!mutable.value.busy) mutable.update { if (trash) it.copy(trashSelected = paths) else it.copy(selected = paths) } }
    fun toggle(id: String, trash: Boolean = false) {
        val current = if (trash) mutable.value.trashSelected else mutable.value.selected
        select(if (id in current) current-id else current+id, trash)
    }
    fun cleanup(action: Action, phrase: String, allCopies: Boolean) {
        val paths = mutable.value.selected
        work("Reviewing selected files") {
            access(); check(!mutable.value.needsScan) { "Rescan before more cleanup." }
            val result = engine.cleanup(paths, action, phrase, allCopies, ::progress)
            val removed = result.removed.toSet()
            mutable.update { it.copy(result = result, selected = it.selected-removed,
                inventory = it.inventory.copy(files = clearSingletons(it.inventory.files.filterNot { f -> f.path in removed }))) }
            updateStorage()
        }
    }
    fun manageTrash(restore: Boolean, phrase: String) {
        val ids = mutable.value.trashSelected
        work(if (restore) "Restoring files" else "Emptying Sift trash") {
            access(); val result = engine.manageTrash(ids, restore, phrase, ::progress)
            mutable.update { it.copy(result = result, needsScan = it.needsScan || (restore && result.removed.isNotEmpty()),
                selected = if (restore) emptySet() else it.selected) }
            updateStorage()
        }
    }
    fun stop() { engine.cancel.set(true) }
    fun dismissResult() { mutable.update { it.copy(result = null, error = null) } }
    override fun onCleared() { engine.cancel.set(true); super.onCleared() }
}
