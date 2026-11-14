package com.health.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Sharing with someone who cares for you, reminders, the weekly digest, webhooks and Home
 * Assistant, browser notifications, reports and exports, and the watch app.
 */

// ---------------------------------------------------------------- Sharing

/**
 * Read-only access for another account on this server (a partner, a parent, a carer), to the
 * features the owner picks. The viewer sends `X-SAMH-View: <ownerId>` with GET requests to see
 * the owner's data; they can never change it.
 */
@Serializable
data class Share(
    val id: String,
    val ownerId: String,
    val ownerName: String,
    val viewerId: String,
    val viewerName: String,
    @Serializable(with = ModuleSetSerializer::class) val modules: Set<Module>,
    val createdAt: Long,
    val lastViewedAt: Long? = null,
)

@Serializable
data class ShareRequest(val username: String, @Serializable(with = ModuleSetSerializer::class) val modules: Set<Module>)

@Serializable
data class ShareChange(@Serializable(with = ModuleSetSerializer::class) val modules: Set<Module>)

@Serializable
data class Shares(
    /** People who can see this account's data. */
    val given: List<Share> = emptyList(),
    /** Accounts this person can see. */
    val received: List<Share> = emptyList(),
)

// ---------------------------------------------------------------- Reminders

/** How reminders behave on every device: the phone app, browsers, the watch and webhooks. */
@Serializable
data class ReminderPrefs(
    /** Remind again this many minutes after a dose is due, if it isn't marked (0 = don't). */
    val remindAgainMinutes: Int = 30,
    /** At most this many reminders again. */
    val remindAgainTimes: Int = 2,
    /** A dose counts as missed this long after it was due (for the "missed" webhook). */
    val missedAfterMinutes: Int = 120,
    /** A reminder to refill when doses left would last this many days or fewer (0 = don't). */
    val refillDays: Int = 7,
    /** "HH:mm": when refill reminders come. */
    val refillTime: String = "10:00",
    /** Names in reminders (medications, habits, routines, appointments); off, they say only "Time for your 8:00 medications". */
    val showNames: Boolean = false,
    /** "HH:mm" to "HH:mm": no sound for reminders other than doses (habits, refills) in this window. */
    val quietFrom: String? = "22:00",
    val quietTo: String? = "07:00",
    val habits: Boolean = true,
    val appointments: Boolean = true,
)

// ---------------------------------------------------------------- Weekly digest

@Serializable
data class DigestPrefs(
    val enabled: Boolean = true,
    /** 1 Monday … 7 Sunday. */
    val day: Int = 7,
    /** Local hour, 0–23. */
    val hour: Int = 18,
    /** Send it by email too (the server's admin sets up mail). */
    val email: String? = null,
    val emailEnabled: Boolean = false,
    /** Sensitive features whose lines may go in the email (the app always shows them all). */
    @Serializable(with = ModuleSetSerializer::class) val emailModules: Set<Module> = emptySet(),
)

@Serializable
data class DigestSection(
    val title: String,
    val module: Module? = null,
    /** "good", "info" or "warn". */
    val tone: String = "info",
    val lines: List<String>,
)

/** A week in health: Monday to Sunday, against the week before. */
@Serializable
data class Digest(
    val weekStart: String,
    val weekEnd: String,
    val generatedAt: Long,
    val headline: String,
    val sections: List<DigestSection>,
)

@Serializable
data class SmtpStatus(val configured: Boolean)

// ---------------------------------------------------------------- Webhooks and Home Assistant

@Serializable
enum class WebhookEvent(val key: String, val label: String, val module: Module? = null) {
    MORNING("morning", "Morning: sleep and readiness are ready"),
    READINESS_LOW("readiness.low", "Readiness is low"),
    DOSE_DUE("dose.due", "A dose is due", Module.MEDICATIONS),
    DOSE_MISSED("dose.missed", "A dose was missed", Module.MEDICATIONS),
    DOSE_TAKEN("dose.taken", "A dose was taken", Module.MEDICATIONS),
    REFILL("refill", "Time to refill", Module.MEDICATIONS),
    BP_HIGH("bp.high", "Blood pressure is high", Module.VITALS),
    GLUCOSE_LOW("glucose.low", "Glucose is low", Module.VITALS),
    GLUCOSE_HIGH("glucose.high", "Glucose is high", Module.VITALS),
    MIGRAINE("migraine", "A migraine started", Module.SYMPTOMS),
    HABIT_DUE("habit.due", "A habit isn't done yet", Module.HABITS),
    APPOINTMENT_SOON("appointment.soon", "An appointment is coming up", Module.APPOINTMENTS),
    DEVICE_BATTERY_LOW("device.battery_low", "A device's battery is low"),
    DEVICE_QUIET("device.quiet", "A device hasn't synced for a day"),
    DIGEST("digest", "The weekly digest is ready"),
    BLADDER_TRAINING("bladder.training", "Time for a planned toilet trip", Module.CONTINENCE),
    /** A product has been on for the hours the person chose (ContinenceSettings.changeReminderHours). */
    CONTINENCE_CHANGE("continence.change", "Time for a check", Module.CONTINENCE),
    SUPPLIES_LOW("supplies.low", "Supplies are running low"),
    CYCLE_REMINDER("cycle.reminder", "A cycle reminder", Module.CYCLE),
    VOICE_PRACTICE("voice.practice", "Time for voice practice", Module.TRANSITION),
    TEST("test", "A test"),
}

/** A URL the server calls when something happens (Home Assistant's webhook trigger works as is). */
@Serializable
data class Webhook(
    val name: String,
    val url: String,
    val events: Set<WebhookEvent>,
    val enabled: Boolean = true,
    /** Include names (of medications, symptoms, appointments) in what's sent; off sends only times and counts. */
    val includeDetails: Boolean = false,
    val id: String? = null,
    val createdAt: Long? = null,
    val lastDelivery: WebhookDelivery? = null,
    /** Shown once, when it's made: signs each call as `X-SAMH-Signature: sha256=<hex HMAC of the body>`. */
    val secret: String? = null,
)

@Serializable
data class WebhookDelivery(
    val at: Long,
    val event: WebhookEvent,
    val ok: Boolean,
    val status: Int? = null,
    val error: String? = null,
    val webhookId: String? = null,
)

/** What a webhook receives. */
@Serializable
data class WebhookPayload(
    val event: String,
    val at: Long,
    val user: String,
    val title: String,
    val data: JsonObject,
)

/** A key for Home Assistant's REST sensors: it can read `/api/integrations/state` and nothing else. */
@Serializable
data class IntegrationKey(val id: String, val name: String, val token: String, val url: String)

// ---------------------------------------------------------------- Browser notifications (Web Push)

@Serializable
data class PushKey(val publicKey: String)

@Serializable
data class PushSubscriptionInput(val endpoint: String, val p256dh: String, val auth: String, val name: String = "")

@Serializable
data class PushSubscriptionInfo(val id: String, val name: String, val createdAt: Long, val lastOkAt: Long? = null)

/** What a notification says and what its buttons do; the same for push, the phone and the watch. */
@Serializable
data class ReminderAction(
    /** "taken", "skip", "snooze", "open". */
    val action: String,
    val title: String,
)

@Serializable
data class PushMessage(
    val title: String,
    val body: String,
    /** Replaces an earlier notification with the same tag. */
    val tag: String,
    /** Where tapping it goes in the web app: "/meds". */
    val url: String = "/",
    val actions: List<ReminderAction> = emptyList(),
    /** For "taken"/"skip": the dose. */
    val medicationId: String? = null,
    val scheduledFor: Long? = null,
    /** When it's for (in /api/reminders/upcoming). */
    val at: Long? = null,
    /** Whose it is: a browser's reminder acts only while that account is signed in there. */
    val userId: String? = null,
)

// ---------------------------------------------------------------- Reports and export

/** What a report or export covers: GET /api/report.pdf and /api/export.zip take these as query parameters. */
@Serializable
enum class ReportSectionKey(val label: String, val module: Module?) {
    OVERVIEW("Overview", null),
    SLEEP("Sleep", Module.SLEEP),
    ACTIVITY("Activity", Module.ACTIVITY),
    STRESS("Stress and resilience", Module.ACTIVITY),
    BODY("Weight and body", Module.BODY),
    NUTRITION("Food and drinks", Module.NUTRITION),
    VITALS("Blood pressure and glucose", Module.VITALS),
    MEDICATIONS("Medications", Module.MEDICATIONS),
    HORMONES("Hormone therapy", Module.HORMONES),
    MIND("Mind", Module.MIND),
    NEURO("Autism & ADHD", Module.NEURO),
    TRANSITION("Transition", Module.TRANSITION),
    CYCLE("Cycle", Module.CYCLE),
    CONTINENCE("Bladder and bowel", Module.CONTINENCE),
    SYMPTOMS("Symptoms", Module.SYMPTOMS),
    HABITS("Habits", Module.HABITS),
    APPOINTMENTS("Appointments", Module.APPOINTMENTS),
}

// ---------------------------------------------------------------- The watch app

/** Today, small enough for a watch: GET /api/watch/today. */
@Serializable
data class WatchToday(
    val date: String,
    val readiness: Int? = null,
    val sleep: Int? = null,
    val activity: Int? = null,
    val steps: Int = 0,
    val stepGoal: Int = 0,
    val waterMl: Int = 0,
    val waterGoal: Int = 0,
    @Serializable(with = ModuleSetSerializer::class) val modules: Set<Module> = emptySet(),
    val doses: List<WatchDose> = emptyList(),
    val habits: List<WatchHabit> = emptyList(),
    val stress: StressState? = null,
    /**
     * Whether the person keeps track of water ([Fields.NUTRITION_WATER]). Off, [waterMl] and
     * [waterGoal] are 0, and the watch offers no water tap and shows no water progress.
     */
    val water: Boolean = true,
)

@Serializable
data class WatchDose(val medicationId: String, val scheduledFor: Long, val name: String, val time: String, val taken: Boolean)

@Serializable
data class WatchHabit(val id: String, val name: String, val done: Boolean)

@Serializable
enum class WatchLogKind { MOOD, WATER, COFFEE, TOILET, LEAK, BOWEL, DOSE_TAKEN, HABIT, HOT_FLASH, SYMPTOM }

/** One tap on the watch: POST /api/watch/log. */
@Serializable
data class WatchLog(
    val kind: WatchLogKind,
    /** Mood 1–5, millilitres of water, symptom severity 0–10… */
    val value: Int? = null,
    /** A medication id ("medId@scheduledFor" for doses), habit id or symptom name. */
    val ref: String? = null,
    val at: Long? = null,
)
