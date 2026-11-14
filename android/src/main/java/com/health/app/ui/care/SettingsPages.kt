@file:OptIn(ExperimentalLayoutApi::class)

package com.health.app.ui.care

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.health.api.Digest
import com.health.api.DigestPrefs
import com.health.api.Module
import com.health.api.Profile
import com.health.api.ReminderPrefs
import com.health.app.attempt
import com.health.app.net.emailDigestTest
import com.health.app.net.latestDigest
import com.health.app.reminders.ReminderPlanner
import com.health.app.ui.components.AppCard
import com.health.app.ui.components.BackBar
import com.health.app.ui.components.ErrorCard
import com.health.app.ui.components.Hint
import com.health.app.ui.components.InfoCard
import com.health.app.ui.components.LabeledSwitch
import com.health.app.ui.components.Loading
import com.health.app.ui.components.SectionCard
import com.health.app.ui.components.appContainer
import com.health.app.ui.modules.NavRow
import com.health.app.ui.theme.AppTheme
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Settings' links to the pages for reminders, the digest, sharing, reports, the watch and integrations. */
@Composable
fun MoreSettings(onOpen: (String) -> Unit) {
    SectionCard("More") {
        NavRow(Icons.Rounded.Notifications, "Reminders", "Doses, refills, habits and appointments", onClick = { onOpen("settings/reminders") })
        NavRow(Icons.Rounded.Summarize, "Weekly digest", "Your week in health, on your chosen day", onClick = { onOpen("digest") })
        NavRow(Icons.Rounded.Group, "Sharing", "Let someone you trust see what you choose", onClick = { onOpen("settings/sharing") })
        NavRow(Icons.Rounded.Description, "Reports and export", "A PDF for your doctor, or all your data", onClick = { onOpen("reports") })
        NavRow(Icons.Rounded.Watch, "Pebble watch", "Today's scores and one-tap logging on your wrist", onClick = { onOpen("settings/watch") })
        NavRow(Icons.Rounded.Hub, "Integrations", "Webhooks and Home Assistant", onClick = { onOpen("settings/integrations") })
    }
}

// ---------------------------------------------------------------- Reminders

@Composable
fun RemindersRoute(onBack: () -> Unit) {
    val c = appContainer()
    val profile by c.profile.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { refresh++ }
    RemindersScreen(
        profile, onBack,
        canNotify = remember(refresh) { c.reminders.canNotify() },
        onAllow = {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else attempt { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        },
        onSave = { p -> scope.launch { attempt { c.saveProfile(p) }.onFailure { c.tell(it.message ?: "Couldn't save that") } } },
    )
}

@Composable
fun RemindersScreen(profile: Profile?, onBack: () -> Unit, canNotify: Boolean = true, onAllow: () -> Unit = {}, onSave: (Profile) -> Unit) {
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Reminders")
        if (profile == null) {
            Loading()
            return@Column
        }
        val r = profile.reminders
        fun save(change: ReminderPrefs) = onSave(profile.copy(reminders = change))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!canNotify) {
                ErrorCard("Notifications are off for this app, so reminders can't show.", actionLabel = "Turn on", onAction = onAllow)
            }
            Hint("These go for every device: this phone, your browsers, your watch and your webhooks.", Modifier.padding(horizontal = 4.dp))
            SectionCard("Privacy") {
                LabeledSwitch(
                    "Show names in reminders", r.showNames, { save(r.copy(showNames = it)) },
                    subtitle = if (r.showNames) "Reminders name your medications, habits, routines and appointments. Anyone who sees your phone can read them."
                    else "Reminders say \"Time for your 8:00 medications\". Names show only in the app.",
                )
            }
            SectionCard("Doses") {
                Text("If a dose isn't marked, remind again", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 15, 30, 60).forEach { m ->
                        FilterChip(selected = r.remindAgainMinutes == m, onClick = { save(r.copy(remindAgainMinutes = m)) }, label = { Text(if (m == 0) "Don't" else "After $m min") })
                    }
                }
                if (r.remindAgainMinutes > 0) {
                    Text("At most", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 2, 3).forEach { n ->
                            FilterChip(selected = r.remindAgainTimes == n, onClick = { save(r.copy(remindAgainTimes = n)) }, label = { Text(if (n == 1) "Once" else "$n times") })
                        }
                    }
                }
                Text("A dose counts as missed after", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(60, 120, 240).forEach { m ->
                        FilterChip(selected = r.missedAfterMinutes == m, onClick = { save(r.copy(missedAfterMinutes = m)) }, label = { Text("${m / 60} ${if (m == 60) "hour" else "hours"}") })
                    }
                }
                Hint("For webhooks and Home Assistant, which can tell you or someone else.")
            }
            SectionCard("Refills") {
                Text("Remind me when doses left will last", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 3, 7, 14).forEach { d ->
                        FilterChip(selected = r.refillDays == d, onClick = { save(r.copy(refillDays = d)) }, label = { Text(if (d == 0) "Don't" else "$d days") })
                    }
                }
                if (r.refillDays > 0) {
                    OutlinedButton(onClick = { picking = "refill" }) { Text("At ${r.refillTime}") }
                    Hint("For medications where you count the doses left.")
                }
            }
            SectionCard("Habits and appointments") {
                LabeledSwitch("Habit nudges", r.habits, { save(r.copy(habits = it)) }, subtitle = "At a habit's reminder time, if it isn't ticked yet")
                LabeledSwitch("Before appointments", r.appointments, { save(r.copy(appointments = it)) }, subtitle = "When each appointment says, a day and an hour before to start")
            }
            SectionCard("Quiet hours") {
                val on = r.quietFrom != null && r.quietTo != null
                LabeledSwitch(
                    "Quiet at night", on, { v -> save(if (v) r.copy(quietFrom = "22:00", quietTo = "07:00") else r.copy(quietFrom = null, quietTo = null)) },
                    subtitle = "Refills, habits and appointments come silently. Doses always make a sound.",
                )
                if (on) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { picking = "from" }) { Text("From ${r.quietFrom}") }
                        OutlinedButton(onClick = { picking = "to" }) { Text("To ${r.quietTo}") }
                    }
                }
            }
        }
        val current = when (picking) {
            "refill" -> r.refillTime
            "from" -> r.quietFrom
            "to" -> r.quietTo
            else -> null
        }
        if (picking != null) {
            TimePickDialog(current?.let(ReminderPlanner::parseTime) ?: LocalTime.of(10, 0), "Time", { picking = null }) { t ->
                val text = ReminderPlanner.formatTime(t)
                save(
                    when (picking) {
                        "refill" -> r.copy(refillTime = text)
                        "from" -> r.copy(quietFrom = text)
                        else -> r.copy(quietTo = text)
                    },
                )
                picking = null
            }
        }
    }
}

// ---------------------------------------------------------------- Weekly digest

@Composable
fun DigestRoute(onBack: () -> Unit) {
    val c = appContainer()
    val profile by c.profile.collectAsState()
    val scope = rememberCoroutineScope()
    var digest by remember { mutableStateOf<Digest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        attempt { c.ui.latestDigest() }.onSuccess { digest = it }.onFailure { error = it.message ?: "Couldn't load your digest" }
        loading = false
    }
    DigestScreen(
        digest, loading, error, profile, onBack,
        onSave = { p -> scope.launch { attempt { c.saveProfile(p) }.onFailure { c.tell(it.message ?: "Couldn't save that") } } },
        onTestEmail = { scope.launch { attempt { c.api.emailDigestTest() }.onSuccess { c.tell("Sent. Check your inbox.") }.onFailure { c.tell(it.message ?: "Couldn't send it") } } },
    )
}

@Composable
fun DigestScreen(
    digest: Digest?,
    loading: Boolean,
    error: String?,
    profile: Profile?,
    onBack: () -> Unit,
    onSave: (Profile) -> Unit = {},
    onTestEmail: () -> Unit = {},
) {
    val readOnly = LocalReadOnly.current
    var pickingHour by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Your week in health")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                loading && digest == null -> Loading()
                digest != null -> DigestView(digest)
                error != null -> Hint("No digest yet. The first one comes at the end of your first week.")
            }
            if (profile != null && !readOnly) {
                val d = profile.digest
                fun save(change: DigestPrefs) = onSave(profile.copy(digest = change))
                SectionCard("Settings") {
                    LabeledSwitch("A weekly digest", d.enabled, { save(d.copy(enabled = it)) }, subtitle = "Your week against the one before, in a few lines")
                    if (d.enabled) {
                        Text("On", style = MaterialTheme.typography.bodyLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DayOfWeek.entries.forEach { day ->
                                FilterChip(selected = d.day == day.value, onClick = { save(d.copy(day = day.value)) }, label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) })
                            }
                        }
                        OutlinedButton(onClick = { pickingHour = true }) { Text("At %d:00".format(d.hour)) }
                        Hint("The phone shows a notification that only says it's ready.")
                        var email by remember(d.email) { mutableStateOf(d.email.orEmpty()) }
                        LabeledSwitch("Email it to me too", d.emailEnabled, { save(d.copy(emailEnabled = it, email = email.trim().ifBlank { null })) })
                        if (d.emailEnabled) {
                            OutlinedTextField(
                                email, { email = it }, label = { Text("Email address") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(enabled = email.contains('@') && email.trim() != d.email, onClick = { save(d.copy(email = email.trim())) }) { Text("Save address") }
                                TextButton(enabled = !d.email.isNullOrBlank(), onClick = onTestEmail) { Text("Send a test") }
                            }
                            val private = profile.modules.filter { it.sensitive }
                            if (private.isNotEmpty()) {
                                Text("Private features in the email", style = MaterialTheme.typography.bodyLarge)
                                Hint("Email is less private than the app. Only what you pick here goes in it.")
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    private.forEach { m ->
                                        FilterChip(
                                            selected = m in d.emailModules,
                                            onClick = { save(d.copy(emailModules = if (m in d.emailModules) d.emailModules - m else d.emailModules + m)) },
                                            label = { Text(m.label) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (pickingHour) {
                    TimePickDialog(LocalTime.of(d.hour, 0), "At", { pickingHour = false }) { t -> save(d.copy(hour = t.hour)); pickingHour = false }
                }
            }
        }
    }
}

@Composable
fun DigestView(digest: Digest) {
    AppCard(padding = 18.dp) {
        Hint("${CareFormat.date(digest.weekStart)} to ${CareFormat.date(digest.weekEnd)}")
        Text(digest.headline, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 2.dp))
    }
    digest.sections.forEach { s ->
        val tone = when (s.tone) {
            "good" -> AppTheme.status.good
            "warn" -> AppTheme.status.warn
            else -> MaterialTheme.colorScheme.primary
        }
        InfoCard(s.title, iconTint = tone, icon = Icons.Rounded.Summarize) {
            s.lines.forEach { line -> Text("• $line", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
        }
    }
}

// ---------------------------------------------------------------- Watch and integrations

@Composable
fun WatchRoute(onBack: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.collectAsState()
    val context = LocalContext.current
    WatchScreen(
        settings.server, onBack,
        onReleases = { attempt { context.startActivity(Intent(Intent.ACTION_VIEW, RELEASES.toUri())) } },
        water = com.health.app.ui.modules.waterShown(),
    )
}

const val RELEASES = "https://github.com/603madison/superawesomemagicalhealth/releases/latest"

@Composable
fun WatchScreen(server: String, onBack: () -> Unit, onReleases: () -> Unit, water: Boolean = true) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Pebble watch")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("On your wrist", icon = Icons.Rounded.Watch) {
                Text(
                    "The watch app shows today's scores and steps, and logs mood, ${if (water) "water, " else ""}coffee, toilet trips, doses, habits, hot flashes and symptoms in one tap.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint("It talks to your server through the Pebble app on your phone, so it works without this app open.", Modifier.padding(top = 6.dp))
            }
            InfoCard("Setting it up") {
                listOf(
                    "Download the .pbw file from the latest release, and open it with the Pebble app to install it.",
                    "In the Pebble app, open the watch app's settings.",
                    "Type your server's address: $server",
                    "Sign in when your server asks, and press Connect my watch.",
                ).forEachIndexed { i, step ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onReleases) { Text("Get the watch app") }
            }
            Hint("Taps made while your phone is offline wait there and go when it's back.", Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
fun IntegrationsRoute(onBack: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.collectAsState()
    val context = LocalContext.current
    IntegrationsScreen(onBack) { attempt { context.startActivity(Intent(Intent.ACTION_VIEW, "${settings.server.trimEnd('/')}/settings".toUri())) } }
}

@Composable
fun IntegrationsScreen(onBack: () -> Unit, onOpenWeb: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        BackBar(onBack, "Integrations")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCard("Webhooks and Home Assistant", icon = Icons.Rounded.Hub) {
                Text(
                    "Your server can tell other things when something happens: a dose due or missed, a low reading, a morning summary, a quiet device. " +
                        "Home Assistant can show today's numbers as sensors, and turn on a light when a dose is due.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint("They're set up on your server's web page, under Settings.", Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onOpenWeb) { Text("Open the web settings") }
            }
        }
    }
}
