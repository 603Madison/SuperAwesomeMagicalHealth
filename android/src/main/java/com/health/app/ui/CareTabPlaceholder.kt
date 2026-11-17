package com.health.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.health.api.Layouts
import com.health.api.Module
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BigTitle
import com.health.app.ui.components.LinkRow
import com.health.app.ui.components.RowDivider
import com.health.app.ui.components.Tile
import com.health.app.ui.components.arrangedBy
import com.health.app.ui.modules.ModuleInfo
import com.health.app.ui.settings.SettingsPage

/*
 * The Care tab until its own page (ui/care, CareRoute) is in: each area the person uses, in their
 * order (Layouts.NAV), one tap to its page, so every feature stays a tap away.
 */

@Composable
fun CareTabPlaceholder(open: (String) -> Unit, modules: Set<Module> = com.health.app.ui.care.shownModules()) {
    val areas = (listOf(Module.NUTRITION) + ModuleInfo.OPT_IN).filter { it in modules && ModuleInfo.route(it) != null }
    val shown = arrangedBy(Layouts.NAV, areas) { ModuleInfo.navId(it) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            BigTitle("Care")
            Text("What you look after, soonest first", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (shown.isNotEmpty()) {
            AppCard(padding = 0.dp) {
                shown.forEachIndexed { i, m ->
                    if (i > 0) RowDivider()
                    LinkRow(m.label, ModuleInfo.description(m), onClick = { ModuleInfo.route(m)?.let(open) }, leading = {
                        Tile(ModuleInfo.icon(m), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    })
                }
            }
        }
        AppCard(padding = 0.dp) {
            LinkRow("Choose what you track", "Your conditions, goals and features", onClick = { open(SettingsPage.TRACKING.route) })
        }
    }
}
