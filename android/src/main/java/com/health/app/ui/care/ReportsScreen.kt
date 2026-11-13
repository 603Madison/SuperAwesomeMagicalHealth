@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.care

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.health.api.Module
import com.health.api.ReportSectionKey
import com.health.app.net.exportZip
import com.health.app.net.reportPdf
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** The ranges offered, newest last: days back from today. */
enum class ReportRange(val label: String, val days: Long) {
    MONTH("Last 30 days", 30), THREE("3 months", 91), SIX("6 months", 182), YEAR("A year", 365)
}

/** The report's sections for the features that are on (the overview always). */
fun reportSections(modules: Set<Module>): List<ReportSectionKey> = ReportSectionKey.entries.filter { it.module == null || it.module in modules }

/** "health-report-2026-08-14-to-2026-11-12.pdf". */
fun reportFileName(kind: String, from: LocalDate, to: LocalDate, extension: String) = "health-$kind-$from-to-$to.$extension"

@Composable
fun ReportsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val profile by c.profile.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    ReportsScreen(
        profile?.modules ?: Module.DEFAULT, LocalDate.now(c.zone()), busy, onBack,
        onPdf = { from, to, sections ->
            busy = "pdf"
            scope.launch {
                runCatching {
                    val all = reportSections(profile?.modules ?: Module.DEFAULT).toSet()
                    val bytes = c.api.reportPdf(from, to, sections.takeIf { it != all })
                    shareFile(context, reportFileName("report", from, to, "pdf"), bytes, "application/pdf", "Your health report")
                }.onFailure { c.tell(it.message ?: "Couldn't make the report") }
                busy = null
            }
        },
        onExport = { from, to ->
            busy = "zip"
            scope.launch {
                runCatching {
                    val bytes = c.api.exportZip(from, to)
                    shareFile(context, reportFileName("export", from, to, "zip"), bytes, "application/zip", "Your health data")
                }.onFailure { c.tell(it.message ?: "Couldn't export that") }
                busy = null
            }
        },
    )
}

/** Keeps [bytes] in the app's cache and offers them through the share sheet (Files, email, a messenger). */
suspend fun shareFile(context: Context, name: String, bytes: ByteArray, type: String, title: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Only the latest few stay.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        File(dir, name).apply { writeBytes(bytes) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Reports' sections, in the default order (the sections of [FeatureScreens.REPORTS]). */
val REPORT_SECTIONS = listOf(ArrangeItem("range", "Over"), ArrangeItem("pdf", "A report for your doctor"), ArrangeItem("export", "All your data"))

@Composable
fun ReportsScreen(
    modules: Set<Module>,
    today: LocalDate,
    busy: String?,
    onBack: () -> Unit,
    onPdf: (LocalDate, LocalDate, Set<ReportSectionKey>) -> Unit,
    onExport: (LocalDate, LocalDate) -> Unit,
) {
    val offered = reportSections(modules)
    var range by remember { mutableStateOf(ReportRange.THREE) }
    var chosen by remember(offered) { mutableStateOf(offered.toSet()) }
    val from = today.minusDays(range.days - 1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Reports and export") { ArrangeAction(FeatureScreens.REPORTS, "Reports and export", REPORT_SECTIONS) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Sections(arrangedIn(FeatureScreens.REPORTS, REPORT_SECTIONS)) { id ->
                when (id) {
                    "range" -> InfoCard("Over") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ReportRange.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
                        }
                        Hint("${CareFormat.date(from)} to ${CareFormat.date(today)}")
                    }
                    "pdf" -> InfoCard("A report for your doctor", icon = Icons.Rounded.Description) {
                        Hint("A PDF with plain summaries, tables and charts. Pick what goes in it:")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            offered.forEach { s ->
                                FilterChip(
                                    selected = s in chosen,
                                    enabled = s != ReportSectionKey.OVERVIEW,
                                    onClick = { chosen = if (s in chosen) chosen - s else chosen + s },
                                    label = { Text(s.label) },
                                )
                            }
                        }
                        Button(enabled = busy == null, onClick = { onPdf(from, today, chosen + ReportSectionKey.OVERVIEW) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            if (busy == "pdf") Working() else Text("Make the PDF")
                        }
                    }
                    "export" -> InfoCard("All your data", icon = Icons.Rounded.Inventory2) {
                        Text(
                            "Everything in that time as spreadsheet files (CSV), and a FHIR file that other health apps and records can read.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(enabled = busy == null, onClick = { onExport(from, today) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            if (busy == "zip") Working() else Text("Export everything")
                        }
                    }
                }
            }
            Hint(
                "Reports and exports can hold private features. Share them only with people and apps you trust.",
                Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Working() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("Making it…")
    }
}
