package com.health.app.ui.tracking

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hotel
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NoDrinks
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SportsBar
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WineBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.health.api.BpCategory
import com.health.api.DrinkKind
import com.health.api.GlucoseUnit
import com.health.api.ResilienceLevel
import com.health.api.StressSpan
import com.health.api.StressState
import com.health.app.ui.theme.AppTheme
import java.util.Locale
import kotlin.math.roundToInt

/** Glucose as the person reads it: mmol/L or mg/dL. The server keeps mmol/L. */
object Glucose {
    const val MG_PER_MMOL = 18.0182

    fun value(mmol: Double, unit: GlucoseUnit): Double = if (unit == GlucoseUnit.MG_DL) mmol * MG_PER_MMOL else mmol

    fun toMmol(value: Double, unit: GlucoseUnit): Double = if (unit == GlucoseUnit.MG_DL) value / MG_PER_MMOL else value

    /** "5.4" in mmol/L, "97" in mg/dL. */
    fun number(mmol: Double, unit: GlucoseUnit): String =
        if (unit == GlucoseUnit.MG_DL) (mmol * MG_PER_MMOL).roundToInt().toString() else String.format(Locale.getDefault(), "%.1f", mmol)

    fun text(mmol: Double, unit: GlucoseUnit): String = "${number(mmol, unit)} ${unit.label}"
}

/** A colour for each blood pressure category: calm when normal, warmer as it climbs. */
@Composable
fun bpColor(category: BpCategory?): Color = when (category) {
    BpCategory.NORMAL -> AppTheme.status.good
    BpCategory.ELEVATED -> AppTheme.status.info
    BpCategory.STAGE_1, BpCategory.LOW -> AppTheme.status.warn
    BpCategory.STAGE_2, BpCategory.CRISIS -> AppTheme.status.bad
    null -> AppTheme.status.idle
}

/** The American Heart Association's categories, as the server works them out; for readings the app shows before they're saved. */
fun bpCategory(systolic: Int, diastolic: Int): BpCategory = when {
    systolic > 180 || diastolic > 120 -> BpCategory.CRISIS
    systolic >= 140 || diastolic >= 90 -> BpCategory.STAGE_2
    systolic >= 130 || diastolic >= 80 -> BpCategory.STAGE_1
    systolic >= 120 -> BpCategory.ELEVATED
    systolic < 90 || diastolic < 60 -> BpCategory.LOW
    else -> BpCategory.NORMAL
}

/** Four hues that stay apart in every theme: orange, blue, the theme's purple, green. */
@Composable
fun stressColor(state: StressState): Color = when (state) {
    StressState.STRESSED -> AppTheme.status.warn
    StressState.ENGAGED -> AppTheme.data.water
    StressState.RELAXED -> MaterialTheme.colorScheme.primary
    StressState.RESTORED -> AppTheme.status.good
}

@Composable
fun resilienceColor(level: ResilienceLevel): Color = when (level) {
    ResilienceLevel.LIMITED -> AppTheme.status.warn
    ResilienceLevel.ADEQUATE -> MaterialTheme.colorScheme.secondary
    ResilienceLevel.SOLID -> MaterialTheme.colorScheme.primary
    ResilienceLevel.STRONG, ResilienceLevel.EXCEPTIONAL -> AppTheme.status.good
}

fun drinkIcon(kind: DrinkKind): ImageVector = when (kind) {
    DrinkKind.WATER -> Icons.Rounded.WaterDrop
    DrinkKind.COFFEE, DrinkKind.ESPRESSO, DrinkKind.DECAF, DrinkKind.TEA, DrinkKind.GREEN_TEA -> Icons.Rounded.LocalCafe
    DrinkKind.BEER -> Icons.Rounded.SportsBar
    DrinkKind.WINE -> Icons.Rounded.WineBar
    DrinkKind.SPIRITS, DrinkKind.COCKTAIL -> Icons.Rounded.LocalBar
    DrinkKind.ENERGY_DRINK, DrinkKind.SODA, DrinkKind.OTHER -> Icons.Rounded.LocalDrink
}

/** A drink of [kind] and [ml]: its caffeine (mg) and alcohol (g), as the contract's table has them. */
fun drinkContents(kind: DrinkKind, ml: Int): Pair<Double, Double> {
    val caffeine = (ml * kind.caffeineMgPer100Ml / 100).roundToInt().toDouble()
    // Ethanol weighs 0.789 g a millilitre.
    val alcohol = (ml * kind.alcoholPercent / 100 * 0.789 * 10).roundToInt() / 10.0
    return caffeine to alcohol
}

/** Habit icons, by the names the server keeps (the web app's lucide names). */
object HabitIcons {
    val all: Map<String, ImageVector> = linkedMapOf(
        "sparkles" to Icons.Rounded.AutoAwesome,
        "footprints" to Icons.AutoMirrored.Rounded.DirectionsWalk,
        "dumbbell" to Icons.Rounded.FitnessCenter,
        "droplet" to Icons.Rounded.WaterDrop,
        "moon" to Icons.Rounded.Bedtime,
        "bed-double" to Icons.Rounded.Hotel,
        "book-open" to Icons.AutoMirrored.Rounded.MenuBook,
        "brain" to Icons.Rounded.Psychology,
        "heart" to Icons.Rounded.Favorite,
        "leaf" to Icons.Rounded.Spa,
        "sun" to Icons.Rounded.WbSunny,
        "coffee" to Icons.Rounded.LocalCafe,
        "pill" to Icons.Rounded.Medication,
        "wind" to Icons.Rounded.Air,
        "music" to Icons.Rounded.MusicNote,
        "pen-line" to Icons.Rounded.EditNote,
        "apple" to Icons.Rounded.Restaurant,
        "smile" to Icons.Rounded.Mood,
        "wine-off" to Icons.Rounded.NoDrinks,
        "list-checks" to Icons.Rounded.Checklist,
    )

    fun of(name: String): ImageVector = all[name] ?: Icons.Rounded.AutoAwesome
}

/** The suggestions for symptoms, as on the web. */
object SymptomWords {
    val symptoms = listOf(
        "headache", "back pain", "joint pain", "stomach ache", "fatigue", "nausea", "dizziness", "brain fog", "muscle pain", "pelvic pain",
        "bloating", "heartburn", "cough", "sore throat", "anxiety", "trouble sleeping",
    )
    val areas = listOf("head", "neck", "shoulders", "upper back", "lower back", "chest", "stomach", "pelvis", "arms", "hands", "hips", "legs", "knees", "feet", "joints", "all over")
    val triggers = listOf(
        "poor sleep", "stress", "skipped a meal", "caffeine", "alcohol", "period", "weather", "screens", "bright light", "strong smells",
        "dehydration", "exercise", "long sitting", "certain food", "travel",
    )
    val relief = listOf("rest", "dark room", "water", "food", "ibuprofen", "paracetamol", "naproxen", "triptan", "heat", "cold", "stretching", "massage", "sleep", "fresh air")
    val migraine = listOf("nausea", "vomiting", "light sensitivity", "sound sensitivity", "smell sensitivity", "dizziness", "neck pain", "blurred vision", "tingling", "trouble finding words")
    val helped = listOf("Not at all", "A little", "A lot", "Completely")
}

/** One line of a [BandChart]. */
data class ChartSeries(val values: List<Double?>, val color: Color)

/**
 * Lines over the same days, with the person's target range shaded behind them ([band]); nulls
 * are gaps. The scale takes in the band and every value, with a little room.
 */
@Composable
fun BandChart(series: List<ChartSeries>, modifier: Modifier = Modifier, band: ClosedFloatingPointRange<Double>? = null, height: Dp = 120.dp, description: String = "") {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val bandColor = AppTheme.status.good.copy(alpha = 0.12f)
    val bandEdge = AppTheme.status.good.copy(alpha = 0.45f)
    Canvas(modifier.fillMaxWidth().height(height).semantics { if (description.isNotEmpty()) contentDescription = description }) {
        val all = series.flatMap { it.values.filterNotNull() } + listOfNotNull(band?.start, band?.endInclusive)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        if (all.isEmpty()) return@Canvas
        var lo = all.min()
        var hi = all.max()
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.12
        lo -= pad
        hi += pad
        fun y(v: Double) = (size.height - ((v - lo) / (hi - lo)).toFloat() * size.height).coerceIn(0f, size.height)
        band?.let {
            val top = y(it.endInclusive)
            val bottom = y(it.start)
            drawRect(bandColor, Offset(0f, top), Size(size.width, bottom - top))
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            drawLine(bandEdge, Offset(0f, top), Offset(size.width, top), 1.dp.toPx(), pathEffect = dash)
            drawLine(bandEdge, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx(), pathEffect = dash)
        }
        for (s in series) {
            val n = s.values.size
            if (n == 0) continue
            val step = if (n > 1) size.width / (n - 1) else size.width
            fun x(i: Int) = if (n > 1) i * step else size.width / 2
            // Readings come on some days and not others: the line joins them across the days between.
            val path = Path()
            var started = false
            s.values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                if (!started) {
                    path.moveTo(x(i), y(v)); started = true
                } else {
                    path.lineTo(x(i), y(v))
                }
            }
            drawPath(path, s.color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            s.values.forEachIndexed { i, v -> if (v != null) drawCircle(s.color, 3.dp.toPx(), Offset(x(i), y(v))) }
        }
    }
}

/** The waking day as a strip of colour: each span in its state's colour, gaps left empty. */
@Composable
fun StressStrip(spans: List<StressSpan>, modifier: Modifier = Modifier, height: Dp = 16.dp) {
    val colors = StressState.entries.associateWith { stressColor(it) }
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val summary = StressState.entries.joinToString(", ") { s ->
        "${spans.filter { it.state == s }.sumOf { (it.end - it.start) / 60_000 }} minutes ${s.label.lowercase()}"
    }
    Row(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = summary }) {
            val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(empty, cornerRadius = radius)
            if (spans.isEmpty()) return@Canvas
            val start = spans.minOf { it.start }
            val end = spans.maxOf { it.end }.coerceAtLeast(start + 1)
            fun x(t: Long) = ((t - start).toFloat() / (end - start)) * size.width
            spans.forEach { s ->
                drawRect(colors.getValue(s.state), Offset(x(s.start), 0f), Size((x(s.end) - x(s.start)).coerceAtLeast(1f), size.height))
            }
        }
    }
}
