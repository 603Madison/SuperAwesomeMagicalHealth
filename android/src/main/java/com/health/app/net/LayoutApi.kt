package com.health.app.net

import com.health.api.ApiJson
import com.health.api.LayoutPrefs
import com.health.api.ScreenLayout

/*
 * How the person has arranged each screen (docs/API.md, "Arranging screens"). Always the person's
 * own (Container.api, never the view-aware one): someone viewing a share keeps their own layout.
 */

suspend fun ServerApi.layout(): LayoutPrefs = get("${base()}/api/layout", LayoutPrefs.serializer())

suspend fun ServerApi.saveScreenLayout(key: String, layout: ScreenLayout): LayoutPrefs =
    send("PUT", "${base()}/api/layout/${enc(key)}", ApiJson.encodeToString(ScreenLayout.serializer(), layout), LayoutPrefs.serializer())
