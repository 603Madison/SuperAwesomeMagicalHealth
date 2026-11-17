package com.health.app.ui.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SentimentSatisfied
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Wc
import androidx.compose.ui.graphics.vector.ImageVector
import com.health.api.QuickAction
import com.health.api.Units
import com.health.app.ui.Format

/** How much a tap on the Water quick action adds. */
const val QUICK_WATER_ML = 250

/** Each way to log's icon, and its words: short under its tile in the + sheet's grid, in full for screen readers. */
object QuickActions {
    fun icon(action: QuickAction): ImageVector = when (action) {
        QuickAction.FOOD -> Icons.Rounded.Restaurant
        QuickAction.WATER -> Icons.Rounded.WaterDrop
        QuickAction.MOOD -> Icons.Rounded.SentimentSatisfied
        QuickAction.PERIOD -> Icons.Rounded.CalendarMonth
        QuickAction.BLADDER -> Icons.Rounded.Wc
        QuickAction.DOSE -> Icons.Rounded.Medication
        QuickAction.WEIGHT -> Icons.Rounded.MonitorWeight
        QuickAction.COFFEE -> Icons.Rounded.LocalCafe
        QuickAction.DRINK -> Icons.Rounded.LocalBar
        QuickAction.WORKOUT -> Icons.Rounded.FitnessCenter
        QuickAction.SLEEP -> Icons.Rounded.Bedtime
        QuickAction.CHECK_IN -> Icons.Rounded.Mood
        QuickAction.SYMPTOM -> Icons.Rounded.Healing
        QuickAction.BLOOD_PRESSURE -> Icons.Rounded.MonitorHeart
        QuickAction.GLUCOSE -> Icons.Rounded.Bloodtype
        QuickAction.HABIT -> Icons.Rounded.Checklist
        QuickAction.ENERGY -> Icons.Rounded.Bolt
        QuickAction.OVERLOAD -> Icons.Rounded.Waves
        QuickAction.CALM -> Icons.Rounded.SelfImprovement
        QuickAction.MOMENT -> Icons.Rounded.AutoAwesome
        QuickAction.PHOTO -> Icons.Rounded.PhotoCamera
        QuickAction.MEASUREMENTS -> Icons.Rounded.Straighten
        QuickAction.APPOINTMENT -> Icons.AutoMirrored.Rounded.EventNote
    }

    /** The word under a tile in the + sheet's grid: short, so four fit across a small phone. */
    fun short(action: QuickAction, units: Units): String = when (action) {
        QuickAction.FOOD -> "Food"
        QuickAction.WATER -> "+${Format.water(QUICK_WATER_ML, units)}"
        QuickAction.BLADDER -> "Bladder"
        QuickAction.DOSE -> "Meds"
        QuickAction.DRINK -> "Drink"
        QuickAction.SLEEP -> "Sleep"
        QuickAction.SYMPTOM -> "Symptom"
        QuickAction.BLOOD_PRESSURE -> "Blood pressure"
        QuickAction.HABIT -> "Habits"
        QuickAction.ENERGY -> "Energy"
        QuickAction.OVERLOAD -> "Too much"
        QuickAction.MEASUREMENTS -> "Measure"
        QuickAction.APPOINTMENT -> "Visit"
        else -> action.label
    }

    /** What a screen reader says for a button: "Log food", "Add 250 ml of water", "Log your mood". */
    fun spoken(action: QuickAction, units: Units): String = when (action) {
        QuickAction.WATER -> "Add ${Format.water(QUICK_WATER_ML, units)} of water"
        QuickAction.COFFEE -> "Add a coffee"
        QuickAction.MOOD -> "Log your mood"
        QuickAction.PERIOD -> "Log your period"
        QuickAction.BLADDER -> "Log bladder and bowel"
        QuickAction.DOSE -> "Take a dose"
        QuickAction.WEIGHT -> "Log your weight"
        QuickAction.DRINK -> "Add a drink"
        QuickAction.WORKOUT -> "Log a workout"
        QuickAction.SLEEP -> "Add a night's sleep"
        QuickAction.SYMPTOM -> "Log a symptom"
        QuickAction.BLOOD_PRESSURE -> "Log blood pressure"
        QuickAction.GLUCOSE -> "Log glucose"
        QuickAction.HABIT -> "Tick a habit"
        QuickAction.ENERGY -> "Log energy and focus"
        QuickAction.OVERLOAD -> "When it's too much"
        QuickAction.CALM -> "Something calming"
        QuickAction.MOMENT -> "Note a moment of euphoria"
        QuickAction.PHOTO -> "Take a progress photo"
        QuickAction.MEASUREMENTS -> "Log measurements"
        QuickAction.APPOINTMENT -> "Add an appointment"
        else -> action.label
    }
}
