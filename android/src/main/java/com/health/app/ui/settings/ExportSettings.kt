@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.health.api.Module
import com.health.app.data.DeviceChoices
import com.health.app.devices.healthconnect.ExportKind
import com.health.app.net.fitExport
import com.health.app.ui.care.CareFormat
import com.health.app.ui.care.shareFile
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.SectionCard
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.NavRow
import com.health.app.ui.modules.photos.PhotoUnlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** What the Export page shows of Health Connect. */
data class ExportState(
    /** Whether Health Connect is on this phone. */
    val available: Boolean = true,
    val chosen: Set<ExportKind> = emptySet(),
    /** Kinds Health Connect lets the app write. */
    val allowed: Set<ExportKind> = emptySet(),
    val note: String? = null,
    val busy: String? = null,
)

class ExportActions(
    val back: () -> Unit = {},
    val toggle: (ExportKind, Boolean) -> Unit = { _, _ -> },
    val writeNow: () -> Unit = {},
    val saveFit: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    val shareFit: (LocalDate, LocalDate) -> Unit = { _, _ -> },
    val openReports: () -> Unit = {},
)

/** The ranges a FIT export can cover. */
private enum class FitRange(val label: String, val days: Long) { MONTH("30 days", 30), QUARTER("3 months", 91), YEAR("A year", 365), THREE("3 years", 365 * 3) }

@Composable
fun ExportRoute(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val choices = remember { DeviceChoices.of(context) }
    val chosenNames by choices.healthConnectExport.collectAsState()
    val note by choices.healthConnectNote.collectAsState()
    val writer = c.healthConnectWriter
    val available = remember { writer.available() }
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var pendingKind by remember { mutableStateOf<ExportKind?>(null) }
    var fitRange by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    LaunchedEffect(Unit) { granted = writer.granted() }
    fun writeNow() {
        busy = "write"
        scope.launch {
            val result = runCatching { writer.run() }.getOrElse { it.message }
            result?.let(c::tell)
            granted = writer.granted()
            busy = null
        }
    }
    // Asks Health Connect for one kind's write permission, only when it's turned on.
    val ask = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { result ->
        granted = granted + result
        pendingKind?.let { kind ->
            if (kind.permission in result || kind.permission in granted) {
                choices.setHealthConnectExport(choices.healthConnectExport.value + kind.name)
                writeNow()
            } else {
                c.tell("Health Connect didn't allow writing ${kind.label.lowercase()}")
            }
        }
        pendingKind = null
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val range = fitRange ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "fit"
        scope.launch {
            runCatching {
                val bytes = c.api.fitExport(range.first, range.second)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                c.tell("Saved the FIT files")
            }.onFailure { c.tell(it.message ?: "Couldn't export that") }
            busy = null
        }
    }
    val chosen = chosenNames.mapNotNull { n -> ExportKind.entries.firstOrNull { it.name == n } }.toSet()
    ExportScreen(
        ExportState(available, chosen, ExportKind.entries.filter { it.permission in granted }.toSet(), note, busy),
        ExportActions(
            back = onBack,
            toggle = { kind, on ->
                if (!on) {
                    choices.setHealthConnectExport(choices.healthConnectExport.value - kind.name)
                } else if (kind.permission in granted) {
                    choices.setHealthConnectExport(choices.healthConnectExport.value + kind.name)
                    writeNow()
                } else {
                    pendingKind = kind
                    runCatching { ask.launch(setOf(kind.permission)) }.onFailure { c.tell("Health Connect couldn't be asked") }
                }
            },
            writeNow = ::writeNow,
            saveFit = { from, to -> fitRange = from to to; save.launch("superawesomemagicalhealth-fit-$from-to-$to.zip") },
            shareFit = { from, to ->
                busy = "fit"
                scope.launch {
                    runCatching { shareFile(context, "health-fit-$from-to-$to.zip", c.api.fitExport(from, to), "application/zip", "Your workouts and weight (FIT)") }
                        .onFailure { c.tell(it.message ?: "Couldn't export that") }
                    busy = null
                }
            },
            openReports = { onOpen("reports") },
        ),
        LocalDate.now(c.zone()),
    )
}

/** Settings › Export: Health Connect for the apps that read it, FIT files, CSV and FHIR, and a word about iPhones. */
@Composable
fun ExportScreen(state: ExportState, actions: ExportActions, today: LocalDate) {
    var range by remember { mutableStateOf(FitRange.YEAR) }
    val from = today.minusDays(range.days - 1)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        BackBar(actions.back, "Export to other apps")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Health Connect", icon = Icons.Rounded.Favorite) {
                Text(
                    "Samsung Health, Google Fit, MyFitnessPal, MyNetDiary and many other apps read from Health Connect, so this is how they get your data. " +
                        "Pick what to share; Health Connect asks you to allow each one.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!state.available) {
                    Hint("Health Connect isn't on this phone. It's built into Android 14 and later, and available from the app store before that.", Modifier.padding(top = 8.dp))
                } else {
                    ExportKind.entries.forEach { kind ->
                        val on = kind in state.chosen
                        LabeledSwitch(
                            kind.label, on, { actions.toggle(kind, it) },
                            subtitle = kind.about + if (on && kind !in state.allowed) ". Not allowed in Health Connect yet." else "",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Hint(
                        "Written after each sync: the last 30 days (a week of food and drink), updated rather than copied again. " +
                            "Anything that came from Health Connect isn't written back, and the app never reads back what it wrote.",
                        Modifier.padding(top = 8.dp),
                    )
                    state.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
                    if (state.chosen.isNotEmpty()) {
                        FilledTonalButton(enabled = state.busy == null, onClick = actions.writeNow, modifier = Modifier.padding(top = 8.dp)) {
                            Text(if (state.busy == "write") "Writing…" else "Write now")
                        }
                    }
                }
            }
            InfoCard("FIT files", icon = Icons.AutoMirrored.Rounded.DirectionsRun) {
                Text(
                    "Your workouts, and weight with body fat, as FIT files in a zip: Garmin Connect, Strava, TrainingPeaks and most training apps import them.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    FitRange.entries.forEach { r -> FilterChip(selected = range == r, onClick = { range = r }, label = { Text(r.label) }) }
                }
                Hint("${CareFormat.date(from)} to ${CareFormat.date(today)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilledTonalButton(enabled = state.busy == null, onClick = { actions.saveFit(from, today) }, modifier = Modifier.weight(1f)) { Text("Save to a file") }
                    OutlinedButton(enabled = state.busy == null, onClick = { actions.shareFit(from, today) }, modifier = Modifier.weight(1f)) { Text(if (state.busy == "fit") "Making it…" else "Share") }
                }
            }
            InfoCard("Spreadsheets and health records", icon = Icons.Rounded.Description) {
                Text("Everything as CSV files for spreadsheets, and a FHIR file that health records and many health apps read.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = actions.openReports, modifier = Modifier.padding(top = 8.dp)) { Text("Reports and export") }
            }
            InfoCard("Apple Health", icon = Icons.Rounded.PhoneIphone) {
                Text(
                    "Android phones can't write to Apple Health. On an iPhone, open your server's web app and use its exports (CSV, FHIR and FIT files); " +
                        "apps there that read those can bring your data into Apple Health.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * Settings' wave-3 rows: export to other apps, calm mode, and the photo lock. Phone-only choices
 * are read straight from [DeviceChoices], so this draws anywhere.
 */
@Composable
fun MoreChoices(modules: Set<Module>, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val choices = remember { DeviceChoices.of(context) }
    val calm by choices.calm.collectAsState()
    val lock by choices.photoLock.collectAsState()
    var lockProblem by remember { mutableStateOf<String?>(null) }
    SectionCard("Export and privacy") {
        NavRow(Icons.Rounded.FileUpload, "Export to other apps", "Health Connect (Samsung Health, MyFitnessPal…), FIT files, CSV and FHIR", onClick = { onOpen("settings/export") })
        LabeledSwitch(
            "Calm mode", calm, choices::setCalm,
            subtitle = "Less motion, no glow behind the pages, and softer colours. On this phone only.",
        )
        if (Module.PHOTOS in modules) {
            LabeledSwitch(
                "Lock progress photos", lock,
                { on ->
                    if (on && !PhotoUnlock.available(context)) {
                        lockProblem = "Set a screen lock (a PIN, pattern, fingerprint or face) on this phone first."
                    } else {
                        lockProblem = null
                        choices.setPhotoLock(on)
                    }
                },
                subtitle = "Ask for your fingerprint, face or screen lock before photos show. On this phone only.",
            )
            lockProblem?.let { Hint(it) }
        }
    }
}
