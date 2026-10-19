package app.littleclone.clone

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import app.littleclone.R
import app.littleclone.data.CloneRecord
import app.littleclone.data.InstalledApp
import app.littleclone.engine.ApkTransformer
import app.littleclone.engine.CloneSpec
import app.littleclone.engine.MalformedApkException
import com.android.apksig.ApkSigner
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The pipeline, as data: each stage owns a slice of the overall progress bar. */
enum class Stage(val weight: Float, val labelRes: Int) {
    Prepare(0.04f, R.string.stage_prepare),
    Patch(0.36f, R.string.stage_patch),
    Sign(0.24f, R.string.stage_sign),
    Install(0.36f, R.string.stage_install);

    /** Overall progress (0..1) when this stage is [fraction] done. */
    fun overall(fraction: Float): Float {
        var before = 0f
        for (s in values()) {
            if (s == this) break
            before += s.weight
        }
        return before + weight * fraction.coerceIn(0f, 1f)
    }
}

data class CloneProgress(val stage: Stage, val fraction: Float) {
    val overall: Float get() = stage.overall(fraction)
}

/** An error whose message is already fit to show to the user. */
class CloneException(message: String) : Exception(message)

class CloneEngine(
    private val context: Context,
    private val signing: SigningStore,
    private val installer: PackageInstallerGateway,
) {

    suspend fun clone(
        app: InstalledApp,
        label: String,
        index: Int,
        report: (CloneProgress) -> Unit,
    ): CloneRecord = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "clone-work")
        work.deleteRecursively()
        try {
            report(CloneProgress(Stage.Prepare, 0f))
            checkSpace(app, work)
            val sources = app.sourcePaths.map { File(it) }
            if (sources.any { !it.canRead() }) throw CloneException(context.getString(R.string.error_source_missing))

            val clonePackage = "${app.packageName}.lc$index"
            val spec = CloneSpec(app.packageName, clonePackage, "lc$index", label, Build.SUPPORTED_ABIS.toList())
            val identity = signing.identity()
            report(CloneProgress(Stage.Prepare, 1f))

            val patchedDir = File(work, "patched").apply { mkdirs() }
            val signedDir = File(work, "signed").apply { mkdirs() }

            // Patch every APK (base + splits) so the clone gets a new identity.
            val totalBytes = sources.sumOf { it.length() }.coerceAtLeast(1L)
            var doneBytes = 0L
            val patched = ArrayList<File>()
            for (src in sources) {
                currentCoroutineContext().ensureActive()
                val out = File(patchedDir, src.name)
                val base = doneBytes
                ApkTransformer.transform(src, out, spec) { done, total ->
                    val soFar = base + (src.length() * (done.toFloat() / total.coerceAtLeast(1L))).toLong()
                    report(CloneProgress(Stage.Patch, soFar.toFloat() / totalBytes))
                }
                doneBytes += src.length()
                patched.add(out)
            }
            report(CloneProgress(Stage.Patch, 1f))

            // Sign every APK with the same key; all parts of one app must share a signer.
            val signer = ApkSigner.SignerConfig.Builder("littleclone", identity.privateKey, listOf(identity.certificate))
                .build()
            val signed = ArrayList<File>()
            for ((i, p) in patched.withIndex()) {
                currentCoroutineContext().ensureActive()
                val out = File(signedDir, p.name)
                ApkSigner.Builder(listOf(signer))
                    .setInputApk(p)
                    .setOutputApk(out)
                    .setMinSdkVersion(Build.VERSION.SDK_INT)
                    .setV1SigningEnabled(false)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .build()
                    .sign()
                p.delete() // free space as we go
                signed.add(out)
                report(CloneProgress(Stage.Sign, (i + 1).toFloat() / patched.size))
            }

            val outcome = installer.install(signed) { report(CloneProgress(Stage.Install, it)) }
            when (outcome) {
                InstallOutcome.Success -> Unit
                InstallOutcome.Timeout -> throw CloneException(context.getString(R.string.error_install_timeout))
                is InstallOutcome.Failure -> {
                    if (outcome.status == android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED) {
                        throw CloneException(context.getString(R.string.error_install_cancelled))
                    }
                    throw CloneException(
                        context.getString(R.string.error_install, outcome.message ?: outcome.status.toString())
                    )
                }
            }
            report(CloneProgress(Stage.Install, 1f))

            CloneRecord(
                originPackage = app.packageName,
                originLabel = app.label,
                clonePackage = clonePackage,
                label = spec.label ?: app.label,
                index = index,
                createdAt = System.currentTimeMillis(),
                sizeBytes = app.sizeBytes,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: CloneException) {
            throw e
        } catch (e: MalformedApkException) {
            throw CloneException(e.message ?: e.toString())
        } catch (e: IOException) {
            throw CloneException(e.message ?: e.toString())
        } catch (e: Exception) {
            throw CloneException("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            work.deleteRecursively()
        }
    }

    /** Patched + signed copies plus the installer's staging copy: roughly three times the app. */
    private fun checkSpace(app: InstalledApp, work: File) {
        work.mkdirs()
        val need = app.sizeBytes * 3 + 64L * 1024 * 1024
        val free = StatFs(work.path).availableBytes
        if (free < need) {
            throw CloneException(
                context.getString(
                    R.string.error_space,
                    Formatter.formatShortFileSize(context, need),
                    Formatter.formatShortFileSize(context, free),
                )
            )
        }
    }
}
