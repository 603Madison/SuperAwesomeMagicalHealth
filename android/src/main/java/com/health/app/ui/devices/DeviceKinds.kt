package com.health.app.ui.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bloodtype
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DevicesOther
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.ui.graphics.vector.ImageVector
import com.health.api.DeviceKind

/** Each kind of device's picture, as Devices draws them (the ring as its card does). A kind this app doesn't know is a plain device. */
object DeviceKinds {
    fun icon(kind: DeviceKind?): ImageVector = when (kind) {
        DeviceKind.COLMI_RING -> Icons.Rounded.Radar
        DeviceKind.HEART_RATE_STRAP -> Icons.Rounded.MonitorHeart
        DeviceKind.PEBBLE, DeviceKind.GARMIN -> Icons.Rounded.Watch
        DeviceKind.HEALTH_CONNECT -> Icons.Rounded.HealthAndSafety
        DeviceKind.GADGETBRIDGE, DeviceKind.FIT_FILE -> Icons.Rounded.Description
        DeviceKind.PHONE -> Icons.Rounded.Smartphone
        DeviceKind.BP_MONITOR -> Icons.Rounded.Speed
        DeviceKind.GLUCOSE_METER -> Icons.Rounded.Bloodtype
        DeviceKind.MANUAL -> Icons.Rounded.EditNote
        DeviceKind.OTHER, null -> Icons.Rounded.DevicesOther
    }
}
