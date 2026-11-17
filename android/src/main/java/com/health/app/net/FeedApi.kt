package com.health.app.net

import com.health.api.DayFeed

/*
 * Today's feed (GET /api/feed, shared/…/Timeline.kt): "Right now", the live things with one action
 * each (only for today, and only the person's own), and "Your day", the day's timeline from every
 * feature that's on. Through a share (Container.ui), only the shared features' timeline.
 */

/** [date]'s feed ("2026-11-17"); an older server without it says it needs updating. */
suspend fun ServerApi.feed(date: String): DayFeed = ServerVersion.plain("for Today's timeline") { get("${base()}/api/feed?date=${enc(date)}", DayFeed.serializer()) }
