package app.littleclone.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.littleclone.R
import app.littleclone.clone.Stage
import app.littleclone.data.CloneRecord
import app.littleclone.data.InstalledApp
import app.littleclone.ui.components.ButtonKind
import app.littleclone.ui.components.GlassBackdrop
import app.littleclone.ui.components.GlassCard
import app.littleclone.ui.components.Glyph
import app.littleclone.ui.components.GlyphButton
import app.littleclone.ui.components.GlyphIcon
import app.littleclone.ui.components.Label
import app.littleclone.ui.components.PillButton
import app.littleclone.ui.components.ProgressBar
import app.littleclone.ui.components.pressable
import app.littleclone.ui.theme.LocalTokens
import app.littleclone.ui.theme.Type

/** Everything the screen can ask the outside world to do. */
class HomeActions(
    val onQuery: (String) -> Unit,
    val onTab: (HomeTab) -> Unit,
    val onSelect: (InstalledApp?) -> Unit,
    val onCreate: (InstalledApp, String) -> Unit,
    val onCancelJob: () -> Unit,
    val onDismissMessage: () -> Unit,
    val onGrant: () -> Unit,
    val onOpen: (CloneRecord) -> Unit,
    val onRemove: (CloneRecord) -> Unit,
    val defaultName: (InstalledApp) -> String,
    val formatSize: (Long) -> String,
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions) {
    val modules = buildModules(state)
    Box(Modifier.fillMaxSize()) {
        GlassBackdrop()
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        ) {
            item(key = "header") { Header() }
            for (module in modules) {
                when (module) {
                    is HomeModule.Setup -> item(key = module.key) { SetupModule(module, actions) }
                    is HomeModule.Stats -> item(key = module.key) { StatsModule(module, actions) }
                    is HomeModule.Tabs -> item(key = module.key) { TabsModule(module, actions) }
                    is HomeModule.Apps -> {
                        item(key = "search") { SearchField(module.query, actions.onQuery) }
                        if (module.items.isEmpty()) {
                            item(key = "apps-empty") {
                                EmptyNote(stringResource(if (module.loading) R.string.loading else R.string.empty_apps))
                            }
                        }
                        items(module.items, key = { it.packageName }) { app -> AppRow(app, actions) }
                    }
                    is HomeModule.Clones -> {
                        if (module.items.isEmpty()) {
                            item(key = "clones-empty") { EmptyNote(stringResource(R.string.empty_clones)) }
                        }
                        items(module.items, key = { it.clonePackage }) { c -> CloneRow(c, actions) }
                    }
                }
            }
        }

        val selected = state.selected
        BackHandler(enabled = selected != null) { actions.onSelect(null) }
        AnimatedVisibility(visible = selected != null, enter = fadeIn(), exit = fadeOut()) {
            if (selected != null) CloneSheet(selected, actions)
        }
        state.job?.let { JobOverlay(it, actions) }
        state.message?.let { MessageOverlay(it, actions) }
    }
}

@Composable
private fun Header() {
    val t = LocalTokens.current
    Column(Modifier.padding(top = 20.dp, bottom = 20.dp)) {
        Label(stringResource(R.string.app_name), Type.largeTitle, t.text)
        Label(stringResource(R.string.home_subtitle), Type.body, t.textSecondary)
    }
}

@Composable
private fun SetupModule(module: HomeModule.Setup, actions: HomeActions) {
    val t = LocalTokens.current
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(stringResource(R.string.setup_title), Type.headline, t.text)
            for (req in module.missing) {
                when (req) {
                    Requirement.InstallPermission -> {
                        Label(stringResource(R.string.setup_install_desc), Type.body, t.textSecondary, maxLines = 3)
                        Spacer(Modifier.height(6.dp))
                        PillButton(stringResource(R.string.action_allow), actions.onGrant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsModule(module: HomeModule.Stats, actions: HomeActions) {
    val tiles = listOf(
        stringResource(R.string.stat_clones) to module.cloneCount.toString(),
        stringResource(R.string.stat_used) to actions.formatSize(module.cloneBytes),
        stringResource(R.string.stat_free) to actions.formatSize(module.freeBytes),
    )
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((title, value) in tiles) {
            StatTile(title, value, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(title: String, value: String, modifier: Modifier) {
    val t = LocalTokens.current
    GlassCard(modifier, RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(14.dp)) {
            Label(title, Type.caption, t.textSecondary)
            Spacer(Modifier.height(4.dp))
            Label(value, Type.stat, t.text)
        }
    }
}

@Composable
private fun TabsModule(module: HomeModule.Tabs, actions: HomeActions) {
    val t = LocalTokens.current
    val shape = RoundedCornerShape(50)
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 14.dp), shape) {
        Row(Modifier.padding(4.dp)) {
            for (tab in HomeTab.values()) {
                val on = tab == module.selected
                val text = when (tab) {
                    HomeTab.Apps -> stringResource(R.string.tab_apps)
                    HomeTab.Clones -> stringResource(R.string.tab_clones) + if (module.cloneCount > 0) "  ${module.cloneCount}" else ""
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(shape)
                        .background(if (on) t.solid else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { actions.onTab(tab) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(text, Type.headline, if (on) t.onSolid else t.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val t = LocalTokens.current
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.Search, t.textSecondary, size = 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Label(stringResource(R.string.search_hint), Type.body, t.textTertiary)
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = Type.body.copy(color = t.text),
                    cursorBrush = SolidColor(t.text),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Label(text, Type.body.copy(textAlign = TextAlign.Center), t.textSecondary, maxLines = 3)
    }
}

@Composable
private fun AppRow(app: InstalledApp, actions: HomeActions) {
    val t = LocalTokens.current
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app.packageName, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label(app.label, Type.headline, t.text)
                val version = if (app.versionName.isNotEmpty()) stringResource(R.string.app_version, app.versionName) else ""
                val parts = if (app.partCount > 1) stringResource(R.string.apk_parts, app.partCount) else ""
                val sub = listOf(version, actions.formatSize(app.sizeBytes), parts)
                    .filter { it.isNotEmpty() }
                    .joinToString("  ·  ")
                Label(sub, Type.caption, t.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            PillButton(stringResource(R.string.action_clone), { actions.onSelect(app) })
        }
    }
}

@Composable
private fun CloneRow(record: CloneRecord, actions: HomeActions) {
    val t = LocalTokens.current
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AppIcon(record.clonePackage, 48.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(t.solid),
                    contentAlignment = Alignment.Center,
                ) {
                    Label(record.index.toString(), Type.caption, t.onSolid)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Label(record.label, Type.headline, t.text)
                Label(record.clonePackage, Type.caption, t.textSecondary)
            }
            Spacer(Modifier.width(8.dp))
            GlyphButton(Glyph.Play, { actions.onOpen(record) })
            Spacer(Modifier.width(8.dp))
            GlyphButton(Glyph.Trash, { actions.onRemove(record) })
        }
    }
}

@Composable
private fun Scrim(onClick: (() -> Unit)?) {
    val t = LocalTokens.current
    val base = Modifier.fillMaxSize().background(t.scrim)
    Box(
        if (onClick != null) {
            base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
        } else {
            base.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
        }
    )
}

@Composable
private fun CloneSheet(app: InstalledApp, actions: HomeActions) {
    val t = LocalTokens.current
    var name by remember(app.packageName) { mutableStateOf(actions.defaultName(app)) }
    Box(Modifier.fillMaxSize()) {
        Scrim { actions.onSelect(null) }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.systemBars).imePadding().padding(12.dp)
        ) {
            GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(30.dp)) {
                Box(Modifier.background(t.background.copy(alpha = if (t.dark) 0.55f else 0.35f))) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName, 52.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Label(stringResource(R.string.sheet_title, app.label), Type.title, t.text)
                                Label(app.packageName, Type.caption, t.textSecondary)
                            }
                        }
                        Label(stringResource(R.string.sheet_name_label), Type.caption, t.textSecondary)
                        GlassCard(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp)) {
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it.take(40) },
                                singleLine = true,
                                textStyle = Type.body.copy(color = t.text),
                                cursorBrush = SolidColor(t.text),
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                            )
                        }
                        InfoRow(stringResource(R.string.sheet_size), actions.formatSize(app.sizeBytes))
                        InfoRow(stringResource(R.string.sheet_parts), app.partCount.toString())
                        Label(stringResource(R.string.sheet_note), Type.caption, t.textSecondary, maxLines = 5)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(
                                stringResource(R.string.action_cancel), { actions.onSelect(null) },
                                Modifier.weight(1f), ButtonKind.Glass,
                            )
                            PillButton(
                                stringResource(R.string.action_create), { actions.onCreate(app, name) },
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Label(title, Type.body, t.textSecondary)
        Label(value, Type.body, t.text)
    }
}

@Composable
private fun JobOverlay(job: CloneJob, actions: HomeActions) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Scrim(null)
        GlassCard(Modifier.padding(28.dp).fillMaxWidth(), RoundedCornerShape(30.dp)) {
            Box(Modifier.background(t.background.copy(alpha = if (t.dark) 0.6f else 0.4f))) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppIcon(job.app.packageName, 64.dp)
                    Label(job.app.label, Type.title, t.text)
                    Label(stringResource(job.progress.stage.labelRes), Type.body, t.textSecondary)
                    ProgressBar(job.progress.overall)
                    Label("${(job.progress.overall * 100).toInt()}%", Type.caption, t.textSecondary)
                    if (job.progress.stage == Stage.Install) {
                        Label(stringResource(R.string.job_keep_open), Type.caption, t.textTertiary, maxLines = 3)
                    }
                    PillButton(stringResource(R.string.action_cancel), actions.onCancelJob, kind = ButtonKind.Glass)
                }
            }
        }
    }
}

@Composable
private fun MessageOverlay(message: UiMessage, actions: HomeActions) {
    val t = LocalTokens.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Scrim { actions.onDismissMessage() }
        GlassCard(Modifier.padding(28.dp).fillMaxWidth(), RoundedCornerShape(30.dp)) {
            Box(Modifier.background(t.background.copy(alpha = if (t.dark) 0.6f else 0.4f))) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlyphIcon(if (message.isError) Glyph.Close else Glyph.Check, t.text, size = 32.dp)
                    Label(message.title, Type.title, t.text, maxLines = 2)
                    Label(message.body, Type.body.copy(textAlign = TextAlign.Center), t.textSecondary, maxLines = 8)
                    Spacer(Modifier.height(4.dp))
                    PillButton(stringResource(R.string.action_ok), actions.onDismissMessage)
                }
            }
        }
    }
}
