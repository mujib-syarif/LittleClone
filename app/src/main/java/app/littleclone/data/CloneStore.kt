package app.littleclone.data

import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Persists the list of clones in a small JSON file and exposes it as a flow. */
class CloneStore(private val file: File) {
    private val state = MutableStateFlow(load())
    val records: StateFlow<List<CloneRecord>> = state.asStateFlow()

    @Synchronized
    fun add(record: CloneRecord) {
        save(state.value.filter { it.clonePackage != record.clonePackage } + record)
    }

    @Synchronized
    fun remove(clonePackage: String) {
        save(state.value.filter { it.clonePackage != clonePackage })
    }

    /** Drops records whose package was uninstalled outside this app. */
    @Synchronized
    fun reconcile(pm: PackageManager) {
        val alive = state.value.filter { isInstalled(pm, it.clonePackage) }
        if (alive.size != state.value.size) save(alive)
    }

    private fun save(list: List<CloneRecord>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject()
                    .put("originPackage", r.originPackage)
                    .put("originLabel", r.originLabel)
                    .put("clonePackage", r.clonePackage)
                    .put("label", r.label)
                    .put("index", r.index)
                    .put("createdAt", r.createdAt)
                    .put("sizeBytes", r.sizeBytes)
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
        state.value = list
    }

    private fun load(): List<CloneRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CloneRecord(
                    originPackage = o.getString("originPackage"),
                    originLabel = o.getString("originLabel"),
                    clonePackage = o.getString("clonePackage"),
                    label = o.getString("label"),
                    index = o.getInt("index"),
                    createdAt = o.getLong("createdAt"),
                    sizeBytes = o.getLong("sizeBytes"),
                )
            }
        } catch (e: Exception) {
            emptyList() // a corrupt file must never stop the app from starting
        }
    }

    companion object {
        fun isInstalled(pm: PackageManager, packageName: String): Boolean =
            try {
                pm.getPackageInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
    }
}
