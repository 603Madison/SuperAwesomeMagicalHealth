package com.health.app.net

import com.health.api.DeviceComparison

/*
 * Two devices side by side, night by night (GET /api/devices/compare, shared/…/Health.kt): how far
 * one reads from the other over the time both measured. A share never reaches it: call it on
 * Container.api, never the view-aware Container.ui, and only while the person is looking at their own data.
 */

/**
 * [a] (the one to compare against) and [b] (the one being checked) over the last [days] nights
 * (1–90). Without them the server picks: a chest strap first, then the devices with the most
 * readings. An unknown device is a 404; the same one twice, a 400.
 */
suspend fun ServerApi.compareDevices(a: String? = null, b: String? = null, days: Int = 14): DeviceComparison {
    val query = listOfNotNull(
        a?.takeIf { it.isNotBlank() }?.let { "a=${enc(it)}" },
        b?.takeIf { it.isNotBlank() }?.let { "b=${enc(it)}" },
        "days=$days",
    ).joinToString("&")
    return get("${base()}/api/devices/compare?$query", DeviceComparison.serializer())
}
