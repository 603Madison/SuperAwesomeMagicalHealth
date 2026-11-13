package com.health.app.net

import com.health.api.ApiJson
import com.health.api.BodyChange
import com.health.api.GenderMoment
import com.health.api.HairRemovalSession
import com.health.api.Milestone
import com.health.api.MilestoneTemplate
import com.health.api.TransitionOverview
import com.health.api.TransitionProfile
import com.health.api.VoiceSession
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/*
 * Transition (docs/API.md, "Transition"): the overview, the person's profile, milestones,
 * changes they notice, voice practice, hair removal, and moments of euphoria and dysphoria.
 */

private suspend fun <T> ServerApi.put(path: String, body: T, serializer: KSerializer<T>): T =
    send("PUT", "${base()}$path", ApiJson.encodeToString(serializer, body), serializer)

suspend fun ServerApi.transitionOverview(): TransitionOverview = get("${base()}/api/transition", TransitionOverview.serializer())

suspend fun ServerApi.transitionProfile(): TransitionProfile = get("${base()}/api/transition/profile", TransitionProfile.serializer())

suspend fun ServerApi.saveTransitionProfile(profile: TransitionProfile): TransitionProfile =
    put("/api/transition/profile", profile, TransitionProfile.serializer())

suspend fun ServerApi.milestoneTemplates(): List<MilestoneTemplate> =
    get("${base()}/api/transition/templates", ListSerializer(MilestoneTemplate.serializer()))

// ---- Milestones ----

suspend fun ServerApi.milestones(): List<Milestone> = get("${base()}/api/transition/milestones", ListSerializer(Milestone.serializer()))

suspend fun ServerApi.saveMilestone(milestone: Milestone): Milestone = milestone.id?.let { id ->
    put("/api/transition/milestones/${enc(id)}", milestone, Milestone.serializer())
} ?: post("/api/transition/milestones", milestone, Milestone.serializer())

suspend fun ServerApi.deleteMilestone(id: String) = delete("/api/transition/milestones/${enc(id)}")

// ---- Changes ----

suspend fun ServerApi.bodyChanges(): List<BodyChange> = get("${base()}/api/transition/changes", ListSerializer(BodyChange.serializer()))

suspend fun ServerApi.saveBodyChange(change: BodyChange): BodyChange = change.id?.let { id ->
    put("/api/transition/changes/${enc(id)}", change, BodyChange.serializer())
} ?: post("/api/transition/changes", change, BodyChange.serializer())

suspend fun ServerApi.deleteBodyChange(id: String) = delete("/api/transition/changes/${enc(id)}")

// ---- Voice, hair removal, moments ----

suspend fun ServerApi.voiceSessions(days: Int = 180): List<VoiceSession> =
    get("${base()}/api/transition/voice?days=$days", ListSerializer(VoiceSession.serializer()))

suspend fun ServerApi.addVoiceSession(session: VoiceSession): VoiceSession = post("/api/transition/voice", session, VoiceSession.serializer())

suspend fun ServerApi.deleteVoiceSession(id: String) = delete("/api/transition/voice/${enc(id)}")

suspend fun ServerApi.hairRemoval(days: Int = 365): List<HairRemovalSession> =
    get("${base()}/api/transition/hair-removal?days=$days", ListSerializer(HairRemovalSession.serializer()))

suspend fun ServerApi.addHairRemoval(session: HairRemovalSession): HairRemovalSession =
    post("/api/transition/hair-removal", session, HairRemovalSession.serializer())

suspend fun ServerApi.deleteHairRemoval(id: String) = delete("/api/transition/hair-removal/${enc(id)}")

suspend fun ServerApi.genderMoments(days: Int = 90): List<GenderMoment> =
    get("${base()}/api/transition/moments?days=$days", ListSerializer(GenderMoment.serializer()))

suspend fun ServerApi.addGenderMoment(moment: GenderMoment): GenderMoment = post("/api/transition/moments", moment, GenderMoment.serializer())

suspend fun ServerApi.deleteGenderMoment(id: String) = delete("/api/transition/moments/${enc(id)}")
