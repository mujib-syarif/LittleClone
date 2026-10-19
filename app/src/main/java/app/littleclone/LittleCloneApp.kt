package app.littleclone

import android.app.Application
import app.littleclone.clone.CloneEngine
import app.littleclone.clone.PackageInstallerGateway
import app.littleclone.clone.SigningStore
import app.littleclone.data.AppScanner
import app.littleclone.data.CloneStore
import java.io.File

/** Tiny service locator: one instance of each long-lived collaborator. */
class LittleCloneApp : Application() {
    lateinit var cloneStore: CloneStore
        private set
    lateinit var scanner: AppScanner
        private set
    lateinit var engine: CloneEngine
        private set

    override fun onCreate() {
        super.onCreate()
        cloneStore = CloneStore(File(filesDir, "clones.json"))
        scanner = AppScanner(this)
        engine = CloneEngine(
            context = this,
            signing = SigningStore(noBackupFilesDir),
            installer = PackageInstallerGateway(this),
        )
    }
}
