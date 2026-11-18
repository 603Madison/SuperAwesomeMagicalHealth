package com.health.api

import kotlinx.serialization.Serializable

/*
 * Medical records (You › Medical records, /api/records): a hospital, clinic or practice's own
 * record of the person, read from its patient portal through the standard patient API that US
 * health record systems offer (SMART on FHIR, R4): Epic's and athenahealth's. Read-only: nothing is
 * ever written back. The sign-in happens at the portal; the server keeps its access sealed and
 * never sends it to the apps. Never shared, and not offered to the assistant or connector links.
 * Lab results also go into Medications › Labs, marked with where they came from.
 */

/** The health record systems this server can read from, each through the app its admin registered there. */
@Serializable
enum class RecordProvider(val label: String) {
    /** Hospitals and health systems on Epic (MyChart): each organization has its own address, from Epic's public list. */
    EPIC("Epic"),
    /** Practices on athenahealth (athenaPatient): one address for all; the practice is chosen at sign-in. */
    ATHENA("athenahealth"),
}

/** Somewhere to connect: an organization on Epic, or athenahealth's practices. */
@Serializable
data class RecordOrganization(
    /** As its system lists it: "Mass General Brigham"; "A practice on athenahealth". */
    val name: String,
    /** The FHIR R4 address its records are read from. */
    val fhirBaseUrl: String,
    val provider: RecordProvider = RecordProvider.EPIC,
)

/** What a record holds, a kind at a time, in the order the page shows them. */
@Serializable
enum class RecordKind(val label: String) {
    LAB("Lab results"),
    VITAL("Vital signs"),
    MEDICATION("Medications"),
    CONDITION("Conditions"),
    ALLERGY("Allergies"),
    IMMUNIZATION("Immunizations"),
    PROCEDURE("Procedures"),
}

/** How a lab result compares with its reference range, as the record flags it (or as its range says). */
@Serializable
enum class RecordFlag { HIGH, LOW, ABNORMAL }

/** One patient portal the person has connected. Its access never leaves the server. */
@Serializable
data class RecordConnection(
    val id: String,
    val organization: String,
    val fhirBaseUrl: String,
    val provider: RecordProvider = RecordProvider.EPIC,
    val connectedAt: Long,
    /** When it last brought the record up to date. */
    val lastSyncAt: Long? = null,
    /** What went wrong last time, said plainly ("Sign in again to bring it up to date"); null when all's well. */
    val problem: String? = null,
    /**
     * Whether it can bring itself up to date (the portal gave lasting access, so it does once a
     * day); otherwise each sign-in brings it up to date once.
     */
    val keepsUpdating: Boolean = false,
    /** How many of each kind came from it. */
    val counts: Map<RecordKind, Int> = emptyMap(),
)

/** The page's state: whether this server can connect to Epic at all, and what's connected. */
@Serializable
data class HealthRecords(
    /** The systems the server's admin has set up (Admin › Medical records); none, and there's nothing to connect to. */
    val providers: List<RecordProvider> = emptyList(),
    /** Paused in Settings › Where your data goes: nothing is fetched until it's resumed. */
    val paused: Boolean = false,
    val connections: List<RecordConnection> = emptyList(),
)

/** One thing from a connected record, in plain words. */
@Serializable
data class RecordItem(
    val id: String,
    val connectionId: String,
    val kind: RecordKind,
    /** "Estradiol", "Hypothyroidism", "Penicillin", "Influenza vaccine", "Blood pressure". */
    val title: String,
    /** "142 pg/mL (usual 30–400)", "Since 2019", "Hives · severe", "2 mg tablet, once a day". */
    val detail: String? = null,
    /** The day it happened or was recorded, "YYYY-MM-DD", where the record says. */
    val date: String? = null,
    val at: Long? = null,
    /** As the record has it: "active", "resolved", "stopped", "completed". */
    val status: String? = null,
    /** For a lab result outside its range. */
    val flag: RecordFlag? = null,
    val organization: String,
    /**
     * For a medication: its name and dose as the record has them, to offer as a new medication
     * here ("Add to my medications"); null for other kinds.
     */
    val medication: Medication? = null,
)

/** Starts connecting to [organization]'s portal; [app] when it's the phone app asking, so the sign-in comes back to it. */
@Serializable
data class RecordConnect(
    val organization: String,
    val fhirBaseUrl: String,
    val provider: RecordProvider = RecordProvider.EPIC,
    val app: Boolean = false,
)

/** Where to send the person to sign in to their portal and allow it. */
@Serializable
data class RecordAuthorize(val url: String)
