package com.health.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DownhillSkiing
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Hiking
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.MonitorWeight
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.Pool
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.SportsBasketball
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Air
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector
import com.health.api.WorkoutType

/** The icons the server's insights may name, and the workouts', as Material icons. */
object AppIcons {
    fun insight(name: String): ImageVector = when (name.lowercase()) {
        "moon", "bed", "sleep", "bedtime" -> Icons.Rounded.Bedtime
        "heart", "hr", "pulse" -> Icons.Rounded.Favorite
        "hrv", "heart-pulse", "activity-heart" -> Icons.Rounded.MonitorHeart
        "flame", "fire", "calories", "energy" -> Icons.Rounded.LocalFireDepartment
        "steps", "walk", "footprints" -> Icons.AutoMirrored.Rounded.DirectionsWalk
        "run", "running" -> Icons.AutoMirrored.Rounded.DirectionsRun
        "water", "droplet", "drop" -> Icons.Rounded.WaterDrop
        "food", "utensils", "meal", "apple" -> Icons.Rounded.Restaurant
        "scale", "weight" -> Icons.Rounded.MonitorWeight
        "thermometer", "temperature" -> Icons.Rounded.Thermostat
        "trophy", "award", "star" -> Icons.Rounded.EmojiEvents
        "alert", "warning", "alert-triangle" -> Icons.Rounded.WarningAmber
        "trend-up", "trending-up", "up" -> Icons.AutoMirrored.Rounded.TrendingUp
        "trend-down", "trending-down", "down" -> Icons.AutoMirrored.Rounded.TrendingDown
        "battery", "battery-low" -> Icons.Rounded.BatteryAlert
        "dumbbell", "workout", "strength" -> Icons.Rounded.FitnessCenter
        "mood", "smile" -> Icons.Rounded.Mood
        "timer", "clock" -> Icons.Rounded.Timer
        "zap", "bolt" -> Icons.Rounded.Bolt
        "wind", "breath", "lungs" -> Icons.Rounded.Air
        else -> Icons.Rounded.AutoAwesome
    }

    fun workout(type: WorkoutType): ImageVector = when (type) {
        WorkoutType.WALK -> Icons.AutoMirrored.Rounded.DirectionsWalk
        WorkoutType.RUN -> Icons.AutoMirrored.Rounded.DirectionsRun
        WorkoutType.CYCLE -> Icons.Rounded.DirectionsBike
        WorkoutType.SWIM -> Icons.Rounded.Pool
        WorkoutType.STRENGTH -> Icons.Rounded.FitnessCenter
        WorkoutType.HIIT -> Icons.Rounded.Bolt
        WorkoutType.YOGA, WorkoutType.PILATES -> Icons.Rounded.SelfImprovement
        WorkoutType.HIKE -> Icons.Rounded.Hiking
        WorkoutType.ROW -> Icons.Rounded.Rowing
        WorkoutType.ELLIPTICAL -> Icons.Rounded.Timer
        WorkoutType.DANCE -> Icons.Rounded.MusicNote
        WorkoutType.SPORT -> Icons.Rounded.SportsBasketball
        WorkoutType.CLIMB -> Icons.Rounded.Landscape
        WorkoutType.SKI -> Icons.Rounded.DownhillSkiing
        WorkoutType.OTHER -> Icons.Rounded.FitnessCenter
    }
}
