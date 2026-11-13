@file:OptIn(ExperimentalMaterial3Api::class)

package com.health.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.health.api.CombinedSleep
import com.health.api.CombinedValue
import com.health.api.DeviceChoices
import com.health.api.Metric
import com.health.api.SleepSource
import com.health.api.SourceValue
import com.health.api.Units
import com.health.app.Container
import com.health.app.HealthApp
import com.health.app.net.choosing
import com.health.app.net.choosingSleep
import com.health.app.net.deviceChoices
import com.health.app.net.saveDeviceChoices
import com.health.app.ui.Format
import com.health.app.ui.care.LocalReadOnly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId
import kotlin.math.roundToInt

/*
 * Numbers more than one device measured (a ring and a watch): the server works out its own from
 * them (DaySummary.combined, SleepSummary.combined). A card with any shows a small "From 2 devices"
 * line; tapping it shows each device's value and share, how the number was worked out, and lets
 * the person use only one device for a reading, or combine them again (/api/devices/choices,
 * always with Container.api; never while viewing someone's share).
 */

/** Which readings each place speaks for, and the words for them. */
object Sources {
    /** Today's Heart card. */
    val HEART = setOf(
        Metric.HEART_RATE, Metric.RESTING_HEART_RATE, Metric.HRV, Metric.SPO2, Metric.RESPIRATORY_RATE,
        Metric.SKIN_TEMPERATURE, Metric.TEMPERATURE_DEVIATION, Metric.STRESS,
    )

    /** Today's Activity card. */
    val ACTIVITY = setOf(Metric.STEPS, Metric.DISTANCE, Metric.ACTIVE_CALORIES, Metric.FLOORS)

    /** The readings a Trends chart (by its section id) shows; null for charts that aren't a device's. */
    fun forChart(id: String): Set<Metric>? = when (id) {
        "steps" -> setOf(Metric.STEPS)
        "heart" -> setOf(Metric.RESTING_HEART_RATE, Metric.HEART_RATE)
        "hrv" -> setOf(Metric.HRV)
        else -> null
    }

    /** How many devices the [values] (and [sleep]) come from. */
    fun deviceCount(values: List<CombinedValue>, sleep: CombinedSleep? = null): Int =
        (values.flatMap { v -> v.sources.map { it.deviceId } } + sleep?.sources?.map { it.deviceId }.orEmpty()).distinct().size

    fun line(count: Int): String = "From $count devices"

    /** A reading's name inside a sentence: "heart rate variability". */
    fun reading(metric: Metric): String = metric.label.replaceFirstChar { it.lowercase() }

    fun useOnly(device: String, what: String): String = "Use only $device for $what"

    /** Said once a choice is saved. */
    fun chosen(what: String, device: String?): String =
        if (device == null) "${what.replaceFirstChar { it.uppercase() }} combines your devices again" else "${what.replaceFirstChar { it.uppercase() }} now comes from $device only"

    /** "62%". */
    fun share(weight: Double): String = "${(weight.coerceIn(0.0, 1.0) * 100).roundToInt()}%"

    /** Under a device's value: its share, how many readings it had, and why it counted for less. */
    fun caption(source: SourceValue, chosen: String?): String {
        val share = when {
            chosen == source.deviceId -> "The one you chose"
            chosen != null || source.weight <= 0.0 -> "Left out"
            else -> "Counts for ${share(source.weight)}"
        }
        val readings = source.readings.takeIf { it > 0 }?.let { "${Format.number(it)} ${if (it == 1) "reading" else "readings"}" }
        return listOfNotNull(share, readings, source.note).joinToString(" · ")
    }

    /** A reading's value with its unit, as the cards show it. */
    fun value(metric: Metric, value: Double, units: Units): String = when (metric) {
        Metric.HEART_RATE, Metric.RESTING_HEART_RATE -> "${value.roundToInt()} bpm"
        Metric.HRV -> "${value.roundToInt()} ms"
        Metric.SPO2, Metric.BODY_FAT -> "${Format.compact(value)}%"
        Metric.RESPIRATORY_RATE -> "${Format.compact(value)} per min"
        Metric.SKIN_TEMPERATURE -> if (units == Units.IMPERIAL) "${Format.compact(value * 1.8 + 32)} °F" else "${Format.compact(value)} °C"
        Metric.TEMPERATURE_DEVIATION -> Format.temperatureDelta(value, units)
        Metric.STRESS -> "${value.roundToInt()}"
        Metric.STEPS -> "${Format.number(value)} steps"
        Metric.DISTANCE -> Format.distance(value, units)
        Metric.ACTIVE_CALORIES -> "${Format.number(value)} kcal"
        Metric.FLOORS -> "${Format.compact(value)} floors"
        Metric.WEIGHT -> Format.weight(value, units)
        else -> "${Format.compact(value)} ${metric.unit}".trim()
    }

    /** What Trends shows under the chart [id], from today's combined readings; null where there's nothing to show. */
    fun chart(id: String, combined: List<CombinedValue>, sleep: CombinedSleep?, units: Units, zone: ZoneId): (@Composable () -> Unit)? {
        if (id == "sleep") return sleep?.takeIf { it.sources.size > 1 }?.let { s -> { CombinedSleepSources(s, zone) } }
        val metrics = forChart(id) ?: return null
        val values = combined.filter { it.metric in metrics }
        if (values.isEmpty()) return null
        return { CombinedSources("Today's ${values.first().metric.label.lowercase()}", values, metrics, units) }
    }
}

/** Trends' charts read their "From 2 devices" from here (provided around each chart). */
val LocalChartSources = staticCompositionLocalOf<(@Composable () -> Unit)?> { null }

// ---- Choosing ----

/** Saves which device to use for a reading; the day reloads after. */
interface SourceChooser {
    /** The person's choices, once [load]ed (sleep's isn't in the day). */
    val choices: StateFlow<DeviceChoices?>

    fun load()

    /** [metric] from [deviceId] (named [device]) only, or combined again (null). */
    fun choose(metric: Metric, deviceId: String?, device: String?)

    fun chooseSleep(deviceId: String?, device: String?)
}

/** The [SourceChooser] screens use; null (the default) means the app's own. */
val LocalSourceChooser = staticCompositionLocalOf<SourceChooser?> { null }

/** Who can choose here: nobody while viewing someone's share (then the sources only show). */
@Composable
fun sourceChooser(): SourceChooser? {
    if (LocalReadOnly.current) return null
    LocalSourceChooser.current?.let { return it }
    val c = (LocalContext.current.applicationContext as? HealthApp)?.container ?: return null
    val viewing by c.viewing.collectAsState()
    if (viewing != null) return null
    return remember(c) { ContainerChooser(c) }
}

/** Choices saved with the person's own API ([Container.api]: never the view header). */
private class ContainerChooser(private val c: Container) : SourceChooser {
    private val _choices = MutableStateFlow<DeviceChoices?>(null)
    override val choices: StateFlow<DeviceChoices?> = _choices.asStateFlow()

    override fun load() {
        if (c.viewing.value != null) return
        c.scope.launch { runCatching { c.api.deviceChoices() }.onSuccess { _choices.value = it } }
    }

    override fun choose(metric: Metric, deviceId: String?, device: String?) =
        save(Sources.chosen(Sources.reading(metric), device)) { it.choosing(metric, deviceId) }

    override fun chooseSleep(deviceId: String?, device: String?) = save(Sources.chosen("sleep", device)) { it.choosingSleep(deviceId) }

    private fun save(done: String, change: (DeviceChoices) -> DeviceChoices) {
        if (c.viewing.value != null) return
        c.scope.launch {
            try {
                val next = change(c.api.deviceChoices())
                _choices.value = c.api.saveDeviceChoices(next)
                c.tell(done)
                c.dataChanged()
            } catch (e: Exception) {
                c.tell(e.message ?: "Couldn't save that")
            }
        }
    }
}

// ---- On the cards ----

/** "From 2 devices", tapped for where the numbers come from (outlined, so it doesn't read as one of the card's numbers). */
@Composable
fun SourcesLine(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val label = Sources.line(count)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Row(Modifier.padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Devices, null, Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(16.dp))
        }
    }
}

/**
 * The card's readings in [metrics] that more than one device measured: the line, and its sheet.
 * Nothing when there are none.
 */
@Composable
fun CombinedSources(title: String, combined: List<CombinedValue>, metrics: Set<Metric>, units: Units, modifier: Modifier = Modifier) {
    val values = combined.filter { it.metric in metrics && it.sources.size > 1 }
    if (values.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    // The chip's touch target already adds room above it.
    SourcesLine(Sources.deviceCount(values), modifier.padding(top = 4.dp)) { open = true }
    if (open) {
        val chooser = sourceChooser()
        ModalBottomSheet(onDismissRequest = { open = false }) {
            SourcesDetails(title, values, null, units, ZoneId.systemDefault(), chooser) { open = false }
        }
    }
}

/** A night more than one device recorded: the line, and its sheet. */
@Composable
fun CombinedSleepSources(sleep: CombinedSleep, zone: ZoneId, modifier: Modifier = Modifier) {
    if (sleep.sources.size < 2) return
    var open by remember { mutableStateOf(false) }
    SourcesLine(Sources.deviceCount(emptyList(), sleep), modifier.padding(top = 4.dp)) { open = true }
    if (open) {
        val chooser = sourceChooser()
        LaunchedEffect(chooser) { chooser?.load() }
        ModalBottomSheet(onDismissRequest = { open = false }) {
            SourcesDetails("Sleep", emptyList(), sleep, Units.METRIC, zone, chooser) { open = false }
        }
    }
}

/**
 * Where [values] and [sleep] come from: each device's value and share, how the number was
 * worked out, and (with a [chooser]) the way to use one device or combine them. [onChosen] is
 * told once a choice is made.
 */
@Composable
fun SourcesDetails(
    title: String,
    values: List<CombinedValue>,
    sleep: CombinedSleep?,
    units: Units,
    zone: ZoneId,
    chooser: SourceChooser?,
    onChosen: () -> Unit = {},
) {
    val choices = chooser?.choices?.collectAsState()?.value
    val count = Sources.deviceCount(values, sleep)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        Hint("${Sources.line(count)}. Where they overlap, the app works out its own number from them, or uses the one you choose.")
        values.forEachIndexed { i, v ->
            if (i > 0) HorizontalDivider(Modifier.padding(top = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            ReadingSources(v, units, chooser, onChosen)
        }
        if (sleep != null) {
            if (values.isNotEmpty()) HorizontalDivider(Modifier.padding(top = 18.dp), color = MaterialTheme.colorScheme.outlineVariant)
            SleepSources(sleep, zone, chooser, choices?.sleepDeviceId, onChosen)
        }
        if (chooser == null && LocalReadOnly.current) {
            Spacer(Modifier.height(16.dp))
            Hint("Only the person whose data this is can choose which device to use.")
        }
    }
}

@Composable
private fun ReadingSources(v: CombinedValue, units: Units, chooser: SourceChooser?, onChosen: () -> Unit) {
    val chosen = v.chosenDeviceId
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(v.metric.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text(Sources.value(v.metric, v.value, units), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
    UsingLine(chosen?.let { id -> v.sources.firstOrNull { it.deviceId == id }?.device ?: "the device you chose" }, v.sources.size)
    v.sources.forEach { s ->
        SourceRow(s.device, Sources.value(v.metric, s.value, units), Sources.caption(s, chosen), if (chosen == null) s.weight.toFloat() else if (chosen == s.deviceId) 1f else 0f)
    }
    Spacer(Modifier.height(8.dp))
    Text(v.how, style = MaterialTheme.typography.bodyMedium)
    if (chooser != null) {
        Choices(
            what = Sources.reading(v.metric),
            devices = v.sources.map { it.deviceId to it.device },
            chosen = chosen,
            choose = { id, name -> chooser.choose(v.metric, id, name); onChosen() },
        )
    }
}

@Composable
private fun SleepSources(sleep: CombinedSleep, zone: ZoneId, chooser: SourceChooser?, chosen: String?, onChosen: () -> Unit) {
    Spacer(Modifier.height(16.dp))
    Text("Last night", style = MaterialTheme.typography.titleMedium)
    UsingLine(chosen?.let { id -> sleep.sources.firstOrNull { it.deviceId == id }?.device ?: "the device you chose" }, sleep.sources.size)
    val stagesFrom = sleep.sources.firstOrNull { it.deviceId == sleep.stagesFrom || it.device == sleep.stagesFrom }
    sleep.sources.forEach { s ->
        SourceRow(s.device, "${Format.minutes(s.asleepMinutes)} asleep", sleepCaption(s, zone, s == stagesFrom), null)
    }
    Spacer(Modifier.height(8.dp))
    Text(sleep.how, style = MaterialTheme.typography.bodyMedium)
    if (chooser != null) {
        Choices(
            what = "sleep",
            devices = sleep.sources.map { it.deviceId to it.device },
            chosen = chosen,
            choose = { id, name -> chooser.chooseSleep(id, name); onChosen() },
        )
    }
}

/** "Only Colmi R02", or the app's own from them all. */
@Composable
private fun UsingLine(chosen: String?, devices: Int) {
    Text(
        chosen?.let { "Only $it" } ?: if (devices == 2) "The app's own, from both" else "The app's own, from all $devices",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun sleepCaption(s: SleepSource, zone: ZoneId, stagesShown: Boolean): String = listOfNotNull(
    "${Format.timeOfDay(s.start, zone)} – ${Format.timeOfDay(s.end, zone)}",
    when {
        stagesShown -> "its stages are shown"
        s.staged -> "with stages"
        else -> "no stages"
    },
).joinToString(" · ")

/** One device: its name and value, its share as a bar, and a caption. */
@Composable
private fun SourceRow(device: String, value: String, caption: String, share: Float?) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(device, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (share != null) {
            Spacer(Modifier.height(6.dp))
            ProgressBar(share, height = 6.dp)
        }
        Spacer(Modifier.height(4.dp))
        Hint(caption)
    }
}

/** "Use only … for …" for each device, and "Combine them" once one is chosen. */
@Composable
private fun Choices(what: String, devices: List<Pair<String, String>>, chosen: String?, choose: (String?, String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (chosen != null) {
            FilledTonalButton(onClick = { choose(null, null) }, modifier = Modifier.fillMaxWidth()) { Text("Combine them") }
        }
        devices.filter { it.first != chosen }.forEach { (id, name) ->
            OutlinedButton(onClick = { choose(id, name) }, modifier = Modifier.fillMaxWidth()) {
                Text(Sources.useOnly(name, what), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
