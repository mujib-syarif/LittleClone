package app.littleclone.clone

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

sealed interface InstallOutcome {
    object Success : InstallOutcome
    object Timeout : InstallOutcome
    data class Failure(val status: Int, val message: String?) : InstallOutcome
}

/** Hands install results from [InstallResultReceiver] to whoever is waiting on that session. */
object InstallRegistry {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<InstallOutcome>>()

    fun register(sessionId: Int): CompletableDeferred<InstallOutcome> {
        val d = CompletableDeferred<InstallOutcome>()
        pending[sessionId] = d
        return d
    }

    fun complete(sessionId: Int, outcome: InstallOutcome) {
        pending.remove(sessionId)?.complete(outcome)
    }

    fun forget(sessionId: Int) {
        pending.remove(sessionId)
    }
}

/** Installs a set of signed APKs (base + splits) as one PackageInstaller session. */
class PackageInstallerGateway(private val context: Context) {

    suspend fun install(files: List<File>, onProgress: (Float) -> Unit): InstallOutcome {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val total = files.sumOf { it.length() }.coerceAtLeast(1L)
        params.setSize(total)
        val sessionId = installer.createSession(params)
        val outcome = InstallRegistry.register(sessionId)
        try {
            installer.openSession(sessionId).use { session ->
                var written = 0L
                val buffer = ByteArray(256 * 1024)
                for (f in files) {
                    f.inputStream().use { input ->
                        session.openWrite(f.name, 0, f.length()).use { out ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                written += n
                                onProgress(written.toFloat() / total)
                            }
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                    .setAction(InstallResultReceiver.ACTION)
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            val result = withTimeoutOrNull(INSTALL_TIMEOUT_MS) { outcome.await() }
            if (result == null) {
                abandon(sessionId)
                return InstallOutcome.Timeout
            }
            return result
        } catch (e: CancellationException) {
            abandon(sessionId)
            throw e
        } catch (e: IOException) {
            abandon(sessionId)
            return InstallOutcome.Failure(PackageInstaller.STATUS_FAILURE, e.message)
        } finally {
            InstallRegistry.forget(sessionId)
        }
    }

    private fun abandon(sessionId: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(sessionId)
        } catch (e: Exception) {
            // Already finished or gone; nothing to clean up.
        }
    }

    private companion object {
        const val INSTALL_TIMEOUT_MS = 5 * 60 * 1000L
    }
}
