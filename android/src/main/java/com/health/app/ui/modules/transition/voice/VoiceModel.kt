package com.health.app.ui.modules.transition.voice

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.health.api.ApiJson
import com.health.api.VoicePlan
import com.health.api.VoiceProgram
import com.health.api.VoiceProgress
import com.health.api.VoiceSession
import com.health.app.Container
import com.health.app.attempt
import com.health.app.net.addVoiceSession
import com.health.app.net.saveVoicePlan
import com.health.app.net.voicePlan
import com.health.app.net.voiceProgram
import com.health.app.net.voiceProgress
import com.health.app.sync.SyncManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.time.LocalDate

/*
 * The voice trainer's data: the programme (from the server, with a copy kept on the phone so
 * practice works offline), the person's plan and progress, and sessions (sent when finished, kept
 * and sent later if the server can't be reached). Phone-only choices (keeping recordings, the
 * person's usual loudness) live in [VoiceStore].
 */

/** The programme built into the app, for when there's neither the server nor a copy on the phone. */
object VoiceOffline {
    /** The programme built into the app (shared/…/VoiceCatalog.kt), for when the server can't be reached. */
    val program: VoiceProgram? = com.health.api.VoiceCatalog.PROGRAM
}

/**
 * What the trainer keeps on this phone, in app-private storage that isn't backed up: a copy of the
 * programme and plan, sessions waiting to be sent, and choices that are only about this phone.
 */
class VoiceStore(context: Context) {
    private val prefs = context.getSharedPreferences("voice-trainer", Context.MODE_PRIVATE)
    private val dir = File(context.noBackupFilesDir, "voice")

    /** Keep each exercise's recording on this phone to listen back. Off unless the person turns it on. */
    var keepRecordings: Boolean
        get() = prefs.getBoolean(KEEP, false)
        set(value) = prefs.edit { putBoolean(KEEP, value) }

    /** The person's usual speaking level (dBFS), from their baseline, for the loudness meter. */
    var speakingDb: Double?
        get() = prefs.getFloat(SPEAKING, Float.NaN).takeIf { !it.isNaN() }?.toDouble()
        set(value) = prefs.edit { if (value == null) remove(SPEAKING) else putFloat(SPEAKING, value.toFloat()) }

    /** The last day a session was finished on this phone (the reminder skips it). */
    var practisedOn: LocalDate?
        get() = prefs.getString(PRACTISED, null)?.let { attempt { LocalDate.parse(it) }.getOrNull() }
        set(value) = prefs.edit { if (value == null) remove(PRACTISED) else putString(PRACTISED, value.toString()) }

    fun program(): VoiceProgram? = read("program.json") { ApiJson.decodeFromString(VoiceProgram.serializer(), it) }
    fun saveProgram(program: VoiceProgram) = write("program.json", ApiJson.encodeToString(VoiceProgram.serializer(), program))

    fun plan(): VoicePlan? = read("plan.json") { ApiJson.decodeFromString(VoicePlan.serializer(), it) }
    fun savePlan(plan: VoicePlan) = write("plan.json", ApiJson.encodeToString(VoicePlan.serializer(), plan))

    /**
     * Sessions [account] finished here that wait to be sent: kept under that account, so they
     * only ever go to it (none when signed out).
     */
    fun pending(account: String?): List<VoiceSession> {
        account ?: return emptyList()
        adopt(account)
        return readSessions(pendingName(account))
    }

    fun savePending(account: String, sessions: List<VoiceSession>) {
        if (sessions.isEmpty()) File(dir, pendingName(account)).delete()
        else write(pendingName(account), ApiJson.encodeToString(ListSerializer(VoiceSession.serializer()), sessions))
    }

    /** Sessions kept before they were kept per account (an older version of the app) belong to whoever is signed in now. */
    fun adopt(account: String) {
        val loose = File(dir, LEGACY_PENDING).takeIf { it.exists() } ?: return
        val sessions = readSessions(pendingName(account)) + readSessions(LEGACY_PENDING)
        savePending(account, sessions)
        loose.delete()
    }

    private fun readSessions(name: String): List<VoiceSession> = read(name) { ApiJson.decodeFromString(ListSerializer(VoiceSession.serializer()), it) }.orEmpty()

    private fun pendingName(account: String) = "pending-$account.json"

    /**
     * Forgets everything the trainer kept here (signing out), recordings included. Sessions waiting
     * to be sent stay, each under its account, unless they're [dropUnsentOf]'s (the person asked to
     * sign out of it).
     */
    fun forget(context: Context, dropUnsentOf: String? = null) {
        prefs.edit { clear() }
        dir.listFiles().orEmpty()
            .filter { f -> !f.name.startsWith("pending-") || (dropUnsentOf != null && f.name == pendingName(dropUnsentOf)) }
            .forEach { it.delete() }
        VoiceRecordings(context).deleteAll()
    }

    private fun <T> read(name: String, parse: (String) -> T): T? =
        attempt { File(dir, name).takeIf { it.exists() }?.readText()?.let(parse) }.getOrNull()

    private fun write(name: String, text: String) {
        attempt {
            dir.mkdirs()
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name))
        }.onFailure { Log.w("Voice", "Couldn't keep $name", it) }
    }

    private companion object {
        const val KEEP = "keepRecordings"
        const val SPEAKING = "speakingDb"
        const val PRACTISED = "practisedOn"
        const val LEGACY_PENDING = "pending.json"
    }
}

data class VoiceState(
    val program: VoiceProgram? = null,
    val plan: VoicePlan? = null,
    val progress: VoiceProgress? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** The programme is the phone's copy: the server couldn't be reached. */
    val offline: Boolean = false,
    /** Sessions finished here, waiting to be sent. */
    val pending: Int = 0,
)

/** The last state, shared between the trainer's pages so moving between them doesn't flash. */
private object VoiceMemory {
    @Volatile
    var state: VoiceState? = null
}

class VoiceViewModel(private val c: Container) : ViewModel() {
    val store = VoiceStore(c.context)
    private val _state = MutableStateFlow(
        VoiceMemory.state ?: VoiceState(program = store.program() ?: VoiceOffline.program, plan = store.plan(), pending = store.pending(c.settings.value.account).size),
    )
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { c.revision.drop(1).collect { load(quiet = true) } }
    }

    private fun set(block: (VoiceState) -> VoiceState) {
        _state.update(block)
        VoiceMemory.state = _state.value
    }

    fun load(quiet: Boolean = false) {
        set { it.copy(loading = !quiet || it.program == null, error = null) }
        viewModelScope.launch {
            sendPending()
            val api = c.ui
            val program = async { attempt { api.voiceProgram() } }
            val plan = async { attempt { api.voicePlan() } }
            val progress = async { attempt { api.voiceProgress() } }
            val p = program.await()
            val pl = plan.await()
            val pr = progress.await()
            val viewing = c.viewing.value != null
            if (!viewing) {
                p.getOrNull()?.let(store::saveProgram)
                (pr.getOrNull()?.plan ?: pl.getOrNull())?.let {
                    store.savePlan(it)
                    VoiceReminders(c.context).update(it, store.practisedOn, c.zone())
                }
            }
            set { s ->
                val programNow = p.getOrNull() ?: s.program ?: store.program() ?: VoiceOffline.program
                s.copy(
                    program = programNow,
                    plan = pr.getOrNull()?.plan ?: pl.getOrNull() ?: s.plan,
                    progress = pr.getOrNull() ?: s.progress,
                    loading = false,
                    offline = p.isFailure && programNow != null,
                    error = if (programNow == null) (p.exceptionOrNull()?.message ?: "Couldn't load the lessons") else null,
                    pending = store.pending(c.settings.value.account).size,
                )
            }
        }
    }

    /** Saves the plan (and its reminder), then [then] once it's saved. */
    fun savePlan(plan: VoicePlan, then: () -> Unit = {}) {
        set { it.copy(plan = plan) }
        store.savePlan(plan)
        VoiceReminders(c.context).update(plan, store.practisedOn, c.zone())
        c.scope.launch {
            try {
                val saved = c.api.saveVoicePlan(plan)
                set { it.copy(plan = saved) }
                store.savePlan(saved)
                c.tell("Plan saved")
                c.dataChanged()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                c.tell(e.message ?: "The plan didn't save")
            }
        }
        then()
    }

    /**
     * Sends a finished session; if the server can't be reached, keeps it (under the account that
     * practised) to send later.
     */
    fun saveSession(session: VoiceSession) {
        store.practisedOn = LocalDate.now(c.zone())
        _state.value.plan?.let { VoiceReminders(c.context).update(it, store.practisedOn, c.zone()) }
        val account = c.settings.value.account
        c.scope.launch {
            try {
                c.api.addVoiceSession(session)
                c.tell("Practice saved")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (account == null || SyncManager.isRefused(e)) {
                    c.tell(e.message ?: "The practice didn't save")
                } else {
                    store.savePending(account, store.pending(account) + session)
                    set { it.copy(pending = store.pending(account).size) }
                    c.tell("Saved on this phone; it'll be sent when the server can be reached")
                }
            }
            c.dataChanged()
        }
    }

    /** Sends the sessions waiting; one the server refuses as wrong is dropped, the rest wait for next time. */
    private suspend fun sendPending() {
        if (c.viewing.value != null) return
        val account = c.settings.value.account ?: return
        val waiting = store.pending(account)
        if (waiting.isEmpty()) return
        val left = waiting.filter { session ->
            val failure = attempt { c.api.addVoiceSession(session) }.exceptionOrNull()
            failure != null && !(failure is Exception && SyncManager.isRefused(failure))
        }
        store.savePending(account, left)
        if (left.size < waiting.size) c.dataChanged()
    }

    companion object {
        /** Forgets the trainer's state in memory (signing out). */
        fun forget() {
            VoiceMemory.state = null
        }
    }
}
