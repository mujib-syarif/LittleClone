package app.littleclone.ui.home

import app.littleclone.data.CloneRecord
import app.littleclone.data.InstalledApp
import app.littleclone.clone.CloneProgress

enum class HomeTab { Apps, Clones }

/** A thing the user must do before cloning works. Add entries to extend the setup card. */
enum class Requirement { InstallPermission }

data class UiMessage(val title: String, val body: String, val isError: Boolean)

data class CloneJob(val app: InstalledApp, val progress: CloneProgress)

data class HomeState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val clones: List<CloneRecord> = emptyList(),
    val missing: List<Requirement> = emptyList(),
    val freeBytes: Long = 0L,
    val query: String = "",
    val tab: HomeTab = HomeTab.Apps,
    val selected: InstalledApp? = null,
    val job: CloneJob? = null,
    val message: UiMessage? = null,
)

/** The home screen is a list of these; the UI only knows how to draw each kind. */
sealed interface HomeModule {
    val key: String

    data class Setup(val missing: List<Requirement>) : HomeModule {
        override val key: String get() = "setup"
    }

    data class Stats(val cloneCount: Int, val cloneBytes: Long, val freeBytes: Long) : HomeModule {
        override val key: String get() = "stats"
    }

    data class Tabs(val selected: HomeTab, val cloneCount: Int) : HomeModule {
        override val key: String get() = "tabs"
    }

    data class Apps(val items: List<InstalledApp>, val query: String, val loading: Boolean) : HomeModule {
        override val key: String get() = "apps"
    }

    data class Clones(val items: List<CloneRecord>) : HomeModule {
        override val key: String get() = "clones"
    }
}

fun buildModules(s: HomeState): List<HomeModule> {
    val list = ArrayList<HomeModule>()
    if (s.missing.isNotEmpty()) list.add(HomeModule.Setup(s.missing))
    list.add(HomeModule.Stats(s.clones.size, s.clones.sumOf { it.sizeBytes }, s.freeBytes))
    list.add(HomeModule.Tabs(s.tab, s.clones.size))
    when (s.tab) {
        HomeTab.Apps -> {
            val q = s.query.trim()
            val items = if (q.isEmpty()) s.apps else s.apps.filter { it.label.contains(q, ignoreCase = true) }
            list.add(HomeModule.Apps(items, s.query, s.loading))
        }
        HomeTab.Clones -> list.add(HomeModule.Clones(s.clones))
    }
    return list
}
