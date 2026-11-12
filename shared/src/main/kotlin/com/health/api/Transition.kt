package com.health.api

import kotlinx.serialization.Serializable

/*
 * Transition (Module.TRANSITION), for trans and nonbinary people: whatever transition means to the
 * person, at their pace. Nothing assumes a direction, a goal, or medical steps. With hormone therapy
 * (Module.HORMONES) for doses and levels, and progress photos (Module.PHOTOS).
 */

@Serializable
enum class MilestoneArea(val label: String) { SOCIAL("Social"), LEGAL("Legal and documents"), MEDICAL("Medical"), VOICE("Voice"), OTHER("Other") }

@Serializable
enum class MilestoneStatus(val label: String) { IDEA("Thinking about it"), PLANNED("Planned"), IN_PROGRESS("In progress"), DONE("Done") }

/** A step: telling someone, a name change, a document, starting hormones, a procedure… */
@Serializable
data class Milestone(
    val title: String,
    val area: MilestoneArea = MilestoneArea.OTHER,
    val status: MilestoneStatus = MilestoneStatus.PLANNED,
    /** When it happened (done) or is planned. */
    val date: String? = null,
    val notes: String? = null,
    /** For documents: the checklist of what's needed. */
    val checklist: List<ChecklistItem> = emptyList(),
    val id: String? = null,
)

@Serializable
data class ChecklistItem(val text: String, val done: Boolean = false)

/** A ready-made milestone to start from (GET /api/transition/templates): "Update my passport"… */
@Serializable
data class MilestoneTemplate(val title: String, val area: MilestoneArea, val checklist: List<String> = emptyList(), val about: String? = null)

/** A change the person notices: "breast growth", "voice dropping", "skin softer", "more emotions"… */
@Serializable
data class BodyChange(
    val date: String,
    /** "chest", "skin", "hair", "face", "voice", "fat distribution", "muscle", "emotions", "libido", "periods"… */
    val area: String,
    val note: String,
    /** How the person feels about it, 1 not good … 5 love it. */
    val feeling: Int? = null,
    val id: String? = null,
)

/** Voice practice: time, exercises, and pitch if measured (on the phone; audio never leaves it). */
@Serializable
data class VoiceSession(
    val at: Long,
    val minutes: Int,
    /** "resonance", "pitch slides", "reading aloud", "humming"… */
    val exercises: List<String> = emptyList(),
    val pitchAvgHz: Double? = null,
    val pitchMinHz: Double? = null,
    val pitchMaxHz: Double? = null,
    /** How it felt, 1–5. */
    val feeling: Int? = null,
    val note: String? = null,
    val id: String? = null,
)

@Serializable
enum class HairRemovalMethod(val label: String) { LASER("Laser"), ELECTROLYSIS("Electrolysis"), IPL("IPL"), WAXING("Waxing"), OTHER("Other") }

@Serializable
data class HairRemovalSession(
    val at: Long,
    val method: HairRemovalMethod,
    /** "face", "neck", "chest", "legs"… */
    val area: String,
    val minutes: Int? = null,
    val pulses: Int? = null,
    val provider: String? = null,
    /** Pain, 0–10. */
    val pain: Int? = null,
    val cost: Double? = null,
    val note: String? = null,
    val id: String? = null,
)

@Serializable
enum class MomentKind(val label: String) { EUPHORIA("Euphoria"), DYSPHORIA("Dysphoria") }

/** A moment of gender euphoria (worth keeping) or dysphoria (worth understanding). */
@Serializable
data class GenderMoment(
    val at: Long,
    val kind: MomentKind,
    /** 1–5. */
    val intensity: Int = 3,
    /** "mirror", "voice", "being gendered right", "clothes", "body"… */
    val context: List<String> = emptyList(),
    val note: String? = null,
    val id: String? = null,
)

/** What the person tracks, and when things began (GET/PUT /api/transition/profile). */
@Serializable
data class TransitionProfile(
    /** Pronouns, shown on the person's own pages (and to people they share with). */
    val pronouns: String? = null,
    /** When they began transitioning, in whatever sense matters to them. */
    val startedOn: String? = null,
    /** The person's own goals, in their words. */
    val goals: List<String> = emptyList(),
    val voiceTargetHz: Double? = null,
)

@Serializable
data class TransitionOverview(
    val profile: TransitionProfile,
    /** "1 year, 3 months" since starting, and on hormone therapy (from HORMONES). */
    val sinceStart: String? = null,
    val onHormones: String? = null,
    val milestones: List<Milestone> = emptyList(),
    val recentChanges: List<BodyChange> = emptyList(),
    val voice: List<VoiceSession> = emptyList(),
    val hairByArea: List<Count> = emptyList(),
    val euphoria: Int = 0,
    val dysphoria: Int = 0,
    /** A few kind, plain lines: "12 moments of euphoria this month", "Passport: 2 of 5 steps done". */
    val highlights: List<String> = emptyList(),
    /** Body measurements (cm) over the last two years, oldest first, for when Fields.TRANSITION_MEASUREMENTS is on. */
    val measurements: List<MeasurementSet> = emptyList(),
)

@Serializable
data class TransitionToday(val headline: String? = null, val milestonesDue: List<String> = emptyList(), val euphoriaThisWeek: Int = 0)

// ---------------------------------------------------------------- Progress photos (Module.PHOTOS)

@Serializable
enum class Pose(val label: String) { FRONT("Front"), SIDE("Side"), BACK("Back"), FACE("Face"), FACE_SIDE("Face, side"), OTHER("Other") }

/**
 * A photo: encrypted on the server, its location and camera details removed, never shown to AI
 * connectors or the assistant, and shown to people the person shares with only if PHOTOS is shared.
 * POST /api/photos is multipart: `file` (JPEG, PNG or WebP) and `photo` (this, as JSON).
 */
@Serializable
data class Photo(
    /** "Transition", "Weight", "Skin", the person's own. */
    val album: String,
    val pose: Pose = Pose.FRONT,
    val at: Long,
    val note: String? = null,
    val tags: List<String> = emptyList(),
    val id: String? = null,
    val date: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Set by the server: the weight logged nearest that day, and time on hormone therapy then. */
    val weightKg: Double? = null,
    val onHormones: String? = null,
)

@Serializable
data class PhotoAlbum(val name: String, val count: Int, val latest: String? = null, val coverId: String? = null)

/** Body measurements live with weight (Metric.WAIST and the rest), so they chart and export the same way. */
@Serializable
data class MeasurementSet(val at: Long, val values: Map<Metric, Double>)
