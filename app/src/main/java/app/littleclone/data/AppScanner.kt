package app.littleclone.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lists the user's launchable, non-system apps. */
class AppScanner(private val context: Context) {

    suspend fun scan(excluded: Set<String>): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION")
        val launchable = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }

        launchable.asSequence()
            .filter { it.packageName != context.packageName && it.packageName !in excluded }
            .filter { !isPlainSystemApp(it) }
            .mapNotNull { toApp(pm, it) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun isInstalled(packageName: String): Boolean = CloneStore.isInstalled(context.packageManager, packageName)

    private fun isPlainSystemApp(info: ApplicationInfo): Boolean {
        val system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updated = info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return system && !updated
    }

    private fun toApp(pm: PackageManager, info: ApplicationInfo): InstalledApp? {
        val paths = ArrayList<String>()
        info.sourceDir?.let { paths.add(it) }
        info.splitSourceDirs?.let { paths.addAll(it) }
        if (paths.isEmpty()) return null
        val version = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(info.packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return InstalledApp(
            packageName = info.packageName,
            label = pm.getApplicationLabel(info).toString(),
            versionName = version ?: "",
            sizeBytes = paths.sumOf { File(it).length() },
            sourcePaths = paths,
        )
    }
}
