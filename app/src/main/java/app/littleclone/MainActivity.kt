package app.littleclone

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.littleclone.data.CloneRecord
import app.littleclone.ui.home.HomeActions
import app.littleclone.ui.home.HomeScreen
import app.littleclone.ui.home.HomeViewModel
import app.littleclone.ui.theme.LittleCloneTheme

class MainActivity : ComponentActivity() {
    private val vm: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val actions = HomeActions(
            onQuery = vm::setQuery,
            onTab = vm::setTab,
            onSelect = vm::select,
            onCreate = vm::startClone,
            onCancelJob = vm::cancelClone,
            onDismissMessage = vm::dismissMessage,
            onGrant = ::openInstallSettings,
            onOpen = ::openClone,
            onRemove = ::removeClone,
            defaultName = vm::defaultName,
            formatSize = vm::formatSize,
        )
        setContent {
            LittleCloneTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val busy = state.job != null
                // A clone can take a minute or two; don't let the screen sleep (and the app get killed) meanwhile.
                LaunchedEffect(busy) {
                    if (busy) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                HomeScreen(state, actions)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }

    private fun openInstallSettings() {
        val uri = Uri.parse("package:$packageName")
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, uri))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    private fun openClone(record: CloneRecord) {
        val intent = packageManager.getLaunchIntentForPackage(record.clonePackage) ?: return
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Launcher entry vanished; the next refresh drops the stale record.
        }
    }

    private fun removeClone(record: CloneRecord) {
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${record.clonePackage}")))
    }
}
