@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.health.app.ui.modules.meds

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.Opacity
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Vaccines
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.health.api.DoseStatus
import com.health.api.DueDose
import com.health.api.LabResult
import com.health.api.MedForm
import com.health.api.Medication
import com.health.api.MedsDay
import com.health.app.reminders.ReminderPlanner
import com.health.app.ui.Format
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LineChart
import com.health.app.ui.components.Loading
import com.health.app.ui.components.Pill
import com.health.app.ui.components.ScreenTitle
import com.health.app.ui.components.SegmentedChoice
import com.health.app.ui.components.appContainer
import com.health.app.ui.components.ArrangeItem
import com.health.app.ui.modules.ArrangeAction
import com.health.app.ui.modules.EmptyLine
import com.health.app.ui.modules.FeatureScreens
import com.health.app.ui.modules.Sections
import com.health.app.ui.modules.arrangedIn
import com.health.app.ui.modules.sectionId
import androidx.compose.material3.IconButton
import com.health.app.ui.log.NumberField
import com.health.app.ui.modules.IconTile
import com.health.app.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

fun formIcon(form: MedForm): ImageVector = when (form) {
    MedForm.INJECTION -> Icons.Rounded.Vaccines
    MedForm.PATCH, MedForm.IMPLANT -> Icons.Rounded.Healing
    MedForm.LIQUID, MedForm.DROPS, MedForm.GEL, MedForm.CREAM -> Icons.Rounded.Opacity
    MedForm.SPRAY, MedForm.INHALER -> Icons.Rounded.Air
    else -> Icons.Rounded.Medication
}

/** Common labs, with the unit they're often reported in (the person can change it). */
val LABS = listOf(
    "Estradiol" to "pg/mL", "Testosterone" to "ng/dL", "Progesterone" to "ng/mL", "Prolactin" to "ng/mL",
    "Ferritin" to "ng/mL", "Hemoglobin" to "g/dL", "TSH" to "mIU/L", "Vitamin D" to "ng/mL",
    "HbA1c" to "%", "LDL" to "mg/dL", "Potassium" to "mmol/L", "Creatinine" to "mg/dL",
)

class MedsActions(
    val tab: (MedsTab) -> Unit = {},
    val take: (DueDose, String?) -> Unit = { _, _ -> },
    val skip: (DueDose) -> Unit = {},
    val tookOne: (Medication) -> Unit = {},
    val edit: (Medication?) -> Unit = {},
    val addLab: (LabResult) -> Unit = {},
    val deleteLab: (String) -> Unit = {},
    val retry: () -> Unit = {},
    val allowExact: () -> Unit = {},
    val allowNotifications: () -> Unit = {},
)

@Composable
fun MedsRoute(onEdit: (String?) -> Unit) {
    val c = appContainer()
    val vm = viewModel { MedsViewModel(c) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    MedsScreen(
        state, c.zone(),
        canNotify = remember(refresh) { c.reminders.canNotify() },
        canBeExact = remember(refresh) { c.reminders.canBeExact() },
        actions = MedsActions(
            tab = vm::tab,
            take = { d, s -> vm.take(d, s) },
            skip = { vm.skip(it) },
            tookOne = { vm.tookOne(it) },
            edit = { onEdit(it?.id) },
            addLab = { vm.addLab(it) },
            deleteLab = { vm.deleteLab(it) },
            retry = { vm.load() },
            allowExact = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
                    }
                }
                refresh++
            },
            allowNotifications = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notify.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
        ),
    )
}

/** The Today tab's sections, in the default order (the sections of [FeatureScreens.MEDS_TODAY]). */
val MEDS_TODAY_SECTIONS = listOf(
    ArrangeItem("scheduled", "Scheduled"),
    ArrangeItem("summary", "Taken this week, and running low"),
    ArrangeItem("asneeded", "As needed"),
)

/** The Labs tab's sections: one for each test, newest first (the sections of [FeatureScreens.MEDS_LABS]). */
fun labSections(labs: List<LabResult>): List<ArrangeItem> =
    labs.sortedByDescending { it.at }.map { it.name }.distinctBy { it.lowercase() }.map { ArrangeItem(sectionId("lab", it), it) }.distinctBy { it.id }

@Composable
fun MedsScreen(state: MedsState, zone: ZoneId, actions: MedsActions, canNotify: Boolean = true, canBeExact: Boolean = true) {
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTitle("Medications", "Doses, reminders and results") {
            if (!readOnly) IconButton(onClick = { actions.edit(null) }) { Icon(Icons.Rounded.Add, "Add a medication") }
            when (state.tab) {
                MedsTab.TODAY -> ArrangeAction(FeatureScreens.MEDS_TODAY, "Today's medications", MEDS_TODAY_SECTIONS)
                MedsTab.LABS -> ArrangeAction(FeatureScreens.MEDS_LABS, "Lab results", labSections(state.labs))
                MedsTab.ALL -> Unit
            }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SegmentedChoice(MedsTab.entries, state.tab, { it.label }, actions.tab)
            when {
                state.loading && state.day == null -> Loading()
                state.error != null && state.day == null -> ErrorCard(state.error, actionLabel = "Try again", onAction = actions.retry)
                state.meds.isEmpty() && state.tab != MedsTab.LABS -> EmptyLine(
                    "No medications yet. Add what you take for reminders and a record of each dose.",
                    action = "Add".takeIf { !readOnly }, onAction = { actions.edit(null) },
                )
                else -> when (state.tab) {
                    MedsTab.TODAY -> TodayTab(state, zone, actions, canNotify, canBeExact)
                    MedsTab.ALL -> AllTab(state.meds, actions)
                    MedsTab.LABS -> LabsTab(state.labs, zone, actions)
                }
            }
        }
    }
}

@Composable
private fun TodayTab(state: MedsState, zone: ZoneId, actions: MedsActions, canNotify: Boolean, canBeExact: Boolean) {
    val day = state.day ?: return
    val anyReminders = state.meds.any { it.active && it.reminders }
    if (anyReminders && !canNotify) {
        AppCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NotificationsOff, null, tint = AppTheme.status.warn)
                Spacer(Modifier.width(10.dp))
                Text("Notifications are off, so reminders can't show.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.allowNotifications) { Text("Allow") }
            }
        }
    } else if (anyReminders && !canBeExact) {
        AppCard(padding = 14.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.NotificationsActive, null, tint = AppTheme.status.warn)
                Spacer(Modifier.width(10.dp))
                Text("Reminders may come a few minutes late.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.allowExact) { Text("Make them exact") }
            }
        }
    }
    Sections(arrangedIn(FeatureScreens.MEDS_TODAY, MEDS_TODAY_SECTIONS)) { id ->
        when (id) {
            "scheduled" -> DoseList(day, zone, actions, sitesFor = { medId -> state.meds.firstOrNull { it.id == medId }?.sites.orEmpty() })
            "summary" -> if (day.adherence7d != null || day.lowStock.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    day.adherence7d?.let { Pill("$it% taken this week", if (it >= 80) AppTheme.status.good else AppTheme.status.warn) }
                    day.lowStock.forEach { Pill("${it.name}: ${it.stock ?: 0} left", AppTheme.status.warn) }
                }
            }
            "asneeded" -> AsNeeded(day, zone, actions)
        }
    }
}

@Composable
private fun AsNeeded(day: MedsDay, zone: ZoneId, actions: MedsActions) {
    if (day.asNeeded.isNotEmpty()) {
        InfoCard("As needed") {
            day.asNeeded.forEachIndexed { i, med ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                val taken = day.extra.filter { it.medicationId == med.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(formIcon(med.form), size = 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(med.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(med.dose, taken.takeIf { it.isNotEmpty() }?.let { t -> "taken ${t.joinToString { Format.timeOfDay(it.at, zone) }}" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { actions.tookOne(med) }) { Text("Took one") }
                }
            }
        }
    }
}

/** Today's doses by time: each with Take and Skip, or what happened to it. */
@Composable
fun DoseList(day: MedsDay, zone: ZoneId, actions: MedsActions, compact: Boolean = false, sitesFor: (String) -> List<String> = { emptyList() }) {
    var picking by remember { mutableStateOf<DueDose?>(null) }
    val now = System.currentTimeMillis()
    if (day.due.isEmpty()) {
        if (!compact) AppCard { Hint("Nothing scheduled today.") }
        return
    }
    val content: @Composable () -> Unit = {
        day.due.sortedBy { it.scheduledFor }.forEachIndexed { i, due ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(formIcon(due.form), size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(due.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val log = due.log
                    val status = when (log?.status) {
                        DoseStatus.TAKEN -> "Taken ${Format.timeOfDay(log.at, zone)}" + (log.site?.let { " · $it" } ?: "")
                        DoseStatus.SKIPPED -> "Skipped"
                        null -> listOfNotNull(due.dose, if (due.scheduledFor < now) "due ${Format.timeOfDay(due.scheduledFor, zone)}" else Format.timeOfDay(due.scheduledFor, zone)).joinToString(" · ")
                    }
                    Text(
                        status, style = MaterialTheme.typography.bodySmall,
                        color = when (log?.status) {
                            DoseStatus.TAKEN -> AppTheme.status.good
                            DoseStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            null -> if (due.scheduledFor < now - 30 * 60_000) AppTheme.status.warn else MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (log == null && due.nextSite != null) Hint("Next site: ${due.nextSite}")
                }
                if (due.log == null && !com.health.app.ui.care.LocalReadOnly.current) {
                    if (!compact) TextButton(onClick = { actions.skip(due) }) { Text("Skip") }
                    FilledTonalButton(onClick = { if (needsSite(due)) picking = due else actions.take(due, null) }) { Text("Take") }
                }
            }
        }
    }
    if (compact) Column { content() } else InfoCard("Scheduled") { content() }
    picking?.let { due ->
        SitePicker(due, sitesFor(due.medicationId), onDismiss = { picking = null }) { site -> actions.take(due, site); picking = null }
    }
}

private fun needsSite(due: DueDose) = (due.form == MedForm.INJECTION || due.form == MedForm.PATCH) && due.nextSite != null

/** Where this injection or patch went: the suggested site first. */
@Composable
private fun SitePicker(due: DueDose, own: List<String>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    var site by remember { mutableStateOf(due.nextSite) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where did it go?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hint("${due.nextSite} is next in your rotation.")
                val sites = (listOfNotNull(due.nextSite) + own.ifEmpty { suggestedSites(due.form) }).distinct()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sites.forEach { s -> FilterChip(selected = site == s, onClick = { site = s }, label = { Text(s) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(site) }) { Text("Taken") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AllTab(meds: List<Medication>, actions: MedsActions) {
    val sorted = meds.sortedWith(compareBy({ !it.active }, { it.name.lowercase() }))
    InfoCard(null) {
        sorted.forEachIndexed { i, med ->
            if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
            com.health.app.ui.modules.NavRow(
                formIcon(med.form),
                med.name + if (!med.active) " (stopped)" else "",
                listOfNotNull(med.dose, ReminderPlanner.describe(med.schedule), med.stock?.let { "$it left" }).joinToString(" · "),
                onClick = { actions.edit(med) },
                trailing = {
                    Icon(
                        if (med.reminders && med.active) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                        if (med.reminders && med.active) "Reminders on" else "Reminders off",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }
    }
    OutlinedButton(onClick = { actions.edit(null) }, modifier = Modifier.fillMaxWidth()) { Text("Add a medication") }
}

@Composable
private fun LabsTab(labs: List<LabResult>, zone: ZoneId, actions: MedsActions) {
    var adding by remember { mutableStateOf(false) }
    val readOnly = com.health.app.ui.care.LocalReadOnly.current
    if (labs.isEmpty()) {
        EmptyLine("No results yet. Keep blood tests here to see how they change.", action = "Add".takeIf { !readOnly }, onAction = { adding = true })
    }
    val byName = labs.groupBy { sectionId("lab", it.name) }
    Sections(arrangedIn(FeatureScreens.MEDS_LABS, labSections(labs))) { id ->
        val results = byName[id] ?: return@Sections
        val sorted = results.sortedBy { it.at }
        val name = sorted.last().name
        val latest = sorted.last()
        val inRange = (latest.low == null || latest.value >= latest.low!!) && (latest.high == null || latest.value <= latest.high!!)
        InfoCard(name, icon = Icons.Rounded.Science, trailing = {
            if (latest.low != null || latest.high != null) Pill(if (inRange) "In range" else "Out of range", if (inRange) AppTheme.status.good else AppTheme.status.warn, Modifier.padding(end = 8.dp))
        }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Format.upTo2(latest.value), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.width(6.dp))
                Text(latest.unit, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            Hint(
                listOfNotNull(
                    Format.shortDay(Instant.ofEpochMilli(latest.at).atZone(zone).toLocalDate()),
                    rangeText(latest.low, latest.high),
                ).joinToString(" · "),
            )
            if (sorted.size >= 2) {
                Spacer(Modifier.height(8.dp))
                LineChart(sorted.map { it.value }, height = 64.dp, color = MaterialTheme.colorScheme.primary)
            }
            if (sorted.size > 1) {
                Spacer(Modifier.height(6.dp))
                sorted.reversed().drop(1).take(3).forEach { r ->
                    Hint("${Format.shortDay(Instant.ofEpochMilli(r.at).atZone(zone).toLocalDate())}: ${Format.upTo2(r.value)} ${r.unit}")
                }
            }
        }
    }
    if (labs.isNotEmpty() && !readOnly) Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add a result") }
    if (adding) LabDialog(zone, onDismiss = { adding = false }) { actions.addLab(it); adding = false }
}

/** "range 100–200", "under 50", "over 30", or null when there's no range. */
fun rangeText(low: Double?, high: Double?): String? = when {
    low != null && high != null -> "range ${Format.upTo2(low)}–${Format.upTo2(high)}"
    high != null -> "range under ${Format.upTo2(high)}"
    low != null -> "range over ${Format.upTo2(low)}"
    else -> null
}

@Composable
private fun LabDialog(zone: ZoneId, onDismiss: () -> Unit, onSave: (LabResult) -> Unit) {
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var low by remember { mutableStateOf("") }
    var high by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(zone).toString()) }
    val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull()
    val number = Format.parse(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("A lab result") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LABS.take(8).forEach { (n, u) -> FilterChip(selected = name == n, onClick = { name = n; if (unit.isBlank()) unit = u }, label = { Text(n) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Test") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(value, { value = it }, "Result", Modifier.weight(1f))
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(low, { low = it }, "Range from", Modifier.weight(1f))
                    NumberField(high, { high = it }, "to", Modifier.weight(1f))
                }
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true, isError = parsedDate == null, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && number != null && unit.isNotBlank() && parsedDate != null,
                onClick = {
                    onSave(
                        LabResult(
                            name.trim(), number!!, unit.trim(), parsedDate!!.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(),
                            low = Format.parse(low), high = Format.parse(high),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
