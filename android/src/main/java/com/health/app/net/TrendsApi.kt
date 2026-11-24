package com.health.app.net

import com.health.api.Trends
import java.time.LocalDate

/** The trends from [from] to [to] (GET /api/trends?from=&to=; up to two years): Body's details read the person's usual from the 30 days before a span. */
suspend fun ServerApi.trends(from: LocalDate, to: LocalDate): Trends =
    get("${base()}/api/trends?from=$from&to=$to", Trends.serializer())
