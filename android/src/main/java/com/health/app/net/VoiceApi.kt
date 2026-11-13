package com.health.app.net

import com.health.api.ApiJson
import com.health.api.VoicePlan
import com.health.api.VoiceProgram
import com.health.api.VoiceProgress

/*
 * The voice trainer (docs/API.md, "Transition"): the programme, the person's plan, and their
 * progress. Sessions are saved with addVoiceSession (POST /api/transition/voice). No sound is ever
 * sent: only the numbers each exercise adds up to.
 */

suspend fun ServerApi.voiceProgram(): VoiceProgram = get("${base()}/api/transition/voice/program", VoiceProgram.serializer())

suspend fun ServerApi.voicePlan(): VoicePlan = get("${base()}/api/transition/voice/plan", VoicePlan.serializer())

suspend fun ServerApi.saveVoicePlan(plan: VoicePlan): VoicePlan =
    send("PUT", "${base()}/api/transition/voice/plan", ApiJson.encodeToString(VoicePlan.serializer(), plan), VoicePlan.serializer())

suspend fun ServerApi.voiceProgress(): VoiceProgress = get("${base()}/api/transition/voice/progress", VoiceProgress.serializer())
