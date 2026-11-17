package com.health.app.net

import com.health.api.RecentReadings

/*
 * The last day or so as the person's devices recorded it (GET /api/recent, shared/…/Health.kt): each
 * night's stages and every reading side by side, from one device or all of them. A share never
 * reaches it (403): call it on Container.api, never the view-aware Container.ui, and only while the
 * person is looking at their own data.
 */

/** The last [hours] (24 by default), from [device] alone or (null) every device. An unknown device is a 404. */
suspend fun ServerApi.recent(hours: Int = 24, device: String? = null): RecentReadings =
    get("${base()}/api/recent?hours=$hours" + (device?.let { "&device=${enc(it)}" } ?: ""), RecentReadings.serializer())
