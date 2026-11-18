package com.health.server.records

import com.health.api.RecordConnection

/**
 * Medical records from patient portals on Epic and athenahealth (shared Records.kt, /api/records):
 * connecting with SMART on FHIR, reading the record, and keeping it up to date. Every request out
 * asks `privacy.allows(userId, DataPath.HEALTH_RECORDS)` first.
 */
class RecordsService {
    /** The person's connected portals. */
    suspend fun connections(userId: String): List<RecordConnection> = emptyList()

    /** The hosts their records are read from, for Where your data goes. */
    suspend fun hosts(userId: String): List<String> = connections(userId).mapNotNull { runCatching { java.net.URI(it.fhirBaseUrl).host }.getOrNull() }.distinct()

    companion object {
        /** Where the portal sends the person back after they allow it: registered with each system as the redirect URI. */
        const val CALLBACK_PATH = "/api/records/callback"
    }
}
