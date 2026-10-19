package app.littleclone.ui.home

import android.app.Application
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.littleclone.LittleCloneApp
import app.littleclone.R
import app.littleclone.clone.CloneException
import app.littleclone.clone.CloneProgress
import app.littleclone.clone.Stage
import app.littleclone.data.InstalledApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LittleCloneApp
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            app.cloneStore.records.collect { records -> _state.update { it.copy(clones = records) } }
        }
    }

    /** Called whenever the screen comes back to the foreground. */
    fun refresh() {
        val pm = app.packageManager
        app.cloneStore.reconcile(pm)
        _state.update { it.copy(missing = missingRequirements(), freeBytes = freeBytes()) }
        viewModelScope.launch {
            val excluded = app.cloneStore.records.value.map { it.clonePackage }.toSet()
            val apps = app.scanner.scan(excluded)
            _state.update { it.copy(apps = apps, loading = false) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setTab(tab: HomeTab) = _state.update { it.copy(tab = tab) }
    fun select(app: InstalledApp?) = _state.update { it.copy(selected = app) }
    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun defaultName(app: InstalledApp): String =
        this.app.getString(R.string.default_clone_name, app.label, nextIndex(app.packageName))

    fun startClone(source: InstalledApp, rawName: String) {
        if (job?.isActive == true) return
        val index = nextIndex(source.packageName)
        val name = rawName.trim().ifEmpty { defaultName(source) }
        _state.update { it.copy(selected = null, job = CloneJob(source, CloneProgress(Stage.Prepare, 0f))) }
        job = viewModelScope.launch {
            try {
                val record = app.engine.clone(source, name, index) { p ->
                    _state.update { s -> if (s.job == null) s else s.copy(job = CloneJob(source, p)) }
                }
                app.cloneStore.add(record)
                _state.update {
                    it.copy(
                        job = null,
                        tab = HomeTab.Clones,
                        message = UiMessage(
                            app.getString(R.string.done_title),
                            app.getString(R.string.done_body, record.label),
                            isError = false,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(job = null) }
                throw e
            } catch (e: CloneException) {
                fail(e.message ?: "")
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
            refresh()
        }
    }

    fun cancelClone() {
        job?.cancel()
    }


    private fun fail(text: String) {
        _state.update {
            it.copy(job = null, message = UiMessage(app.getString(R.string.error_title), text, isError = true))
        }
    }

    /** Lowest clone number (from 2) that is free both in our records and on the device. */
    private fun nextIndex(origin: String): Int {
        var i = 2
        val used = app.cloneStore.records.value.map { it.clonePackage }.toSet()
        while ("$origin.lc$i" in used || app.scanner.isInstalled("$origin.lc$i")) i++
        return i
    }

    private fun missingRequirements(): List<Requirement> {
        val list = ArrayList<Requirement>()
        if (!app.packageManager.canRequestPackageInstalls()) list.add(Requirement.InstallPermission)
        return list
    }

    private fun freeBytes(): Long =
        try {
            StatFs(Environment.getDataDirectory().path).availableBytes
        } catch (e: Exception) {
            0L
        }

    fun formatSize(bytes: Long): String = Formatter.formatShortFileSize(app, bytes)
}
