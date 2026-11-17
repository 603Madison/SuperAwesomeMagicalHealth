package com.health.app.net

import com.health.api.ApiJson
import com.health.api.DataMap
import com.health.api.DataPath
import com.health.api.DataPathChange

/*
 * Where the person's data can go from their server (GET /api/privacy, shared/…/Privacy.kt), and
 * pausing or resuming each way out (PUT /api/privacy/{path}). Always the person's own: call these
 * on Container.api, never the view-aware Container.ui (a share never reaches them).
 */

suspend fun ServerApi.dataMap(): DataMap = ServerVersion.plain("for Where your data goes") { get("${base()}/api/privacy", DataMap.serializer()) }

/** Pauses (or, with [paused] false, resumes) [path]; the server answers with the whole map as it now is. */
suspend fun ServerApi.pauseDataPath(path: DataPath, paused: Boolean): DataMap = send(
    "PUT", "${base()}/api/privacy/${enc(path.name)}", ApiJson.encodeToString(DataPathChange.serializer(), DataPathChange(paused)), DataMap.serializer(),
)
