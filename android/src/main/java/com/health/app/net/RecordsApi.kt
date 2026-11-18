package com.health.app.net

import com.health.api.ApiJson
import com.health.api.HealthRecords
import com.health.api.RecordAuthorize
import com.health.api.RecordConnect
import com.health.api.RecordConnection
import com.health.api.RecordItem
import com.health.api.RecordKind
import com.health.api.RecordOrganization
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Medical records (docs/API.md, "Medical records"): the portals connected, finding one to connect,
 * starting the sign-in, bringing one up to date, disconnecting, and the record itself. Always the
 * person's own, never someone's share: call these on [com.health.app.Container.api].
 */

suspend fun ServerApi.records(): HealthRecords = get("${base()}/api/records", HealthRecords.serializer())

suspend fun ServerApi.recordOrganizations(query: String): List<RecordOrganization> =
    get("${base()}/api/records/organizations?q=${enc(query)}", ListSerializer(RecordOrganization.serializer()))

/** Starts connecting: the portal's sign-in address, which sends the browser back to `samhealth://records` when [RecordConnect.app]. */
suspend fun ServerApi.connectRecords(connect: RecordConnect): RecordAuthorize =
    send("POST", "${base()}/api/records/connect", ApiJson.encodeToString(RecordConnect.serializer(), connect), RecordAuthorize.serializer())

/** Finishes connecting once the portal has sent the app back with ?confirm=: only then is it connected. */
suspend fun ServerApi.confirmRecords(token: String): RecordConnection =
    send("POST", "${base()}/api/records/confirm", buildJsonObject { put("token", token) }.toString(), RecordConnection.serializer())

suspend fun ServerApi.syncRecords(id: String): RecordConnection =
    send("POST", "${base()}/api/records/${enc(id)}/sync", "{}", RecordConnection.serializer())

/** Forgets a portal; with [keep], what it brought in stays. */
suspend fun ServerApi.disconnectRecords(id: String, keep: Boolean) = delete("/api/records/${enc(id)}?keep=$keep")

suspend fun ServerApi.recordItems(kind: RecordKind? = null, connection: String? = null): List<RecordItem> {
    val query = listOfNotNull(kind?.let { "kind=${it.name}" }, connection?.let { "connection=${enc(it)}" }).joinToString("&")
    return get("${base()}/api/records/items" + (if (query.isEmpty()) "" else "?$query"), ListSerializer(RecordItem.serializer()))
}
