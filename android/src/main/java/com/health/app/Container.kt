package com.health.app

import android.content.Context
import android.util.Log
import com.health.api.Module
import com.health.api.Profile
import com.health.app.data.Prefs
import com.health.app.data.Settings
import com.health.app.devices.colmi.ColmiConduit
import com.health.app.devices.gadgetbridge.GadgetbridgeConduit
import com.health.app.devices.healthconnect.HealthConnectConduit
import com.health.app.devices.vitals.MonitorStore
import com.health.app.devices.vitals.VitalsConduit
import com.health.api.Share
import com.health.app.net.ServerApi
import com.health.app.net.SignIn
import com.health.app.net.appointments
import com.health.app.net.continenceSettings
import com.health.app.net.continenceWearing
import com.health.app.net.fields
import com.health.app.net.layout
import com.health.app.net.saveScreenLayout
import com.health.app.net.serverHealth
import com.health.app.net.habitsForReminders
import com.health.app.reminders.DigestWorker
import com.health.app.reminders.DoseOutbox
import com.health.app.reminders.HabitReminder
import com.health.app.reminders.MedsCache
import com.health.app.reminders.ReminderCache
import com.health.app.reminders.Reminders
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import com.health.app.sync.Outbox
import com.health.app.sync.SyncManager
import com.health.app.sync.SyncStore
import com.health.app.sync.SyncWorker
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Everything the app is made of; one lives in [HealthApp]. */
class Container(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) })
    val prefs = Prefs(context)

    /** The settings, kept current (read once at start so the first requests know the server). */
    val settings: StateFlow<Settings> = prefs.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { prefs.current() })

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(ServerApi.authInterceptor({ settings.value.server }, { settings.value.token }))
        .build()

    val api = ServerApi(http, { settings.value.server }, { settings.value.token }, onSignedOut = { scope.launch { signOut(callServer = false) } })

    val signIn = SignIn(context, api, prefs)

    val syncStore = SyncStore(context)
    val outbox = Outbox(File(context.filesDir, "outbox"))
    val colmi = ColmiConduit(context, syncStore, zone = ::zone)
    val healthConnect = HealthConnectConduit(context, syncStore)
    val gadgetbridge = GadgetbridgeConduit(context, syncStore)

    /** Blood pressure monitors and glucose meters paired with this phone. */
    val monitors = MonitorStore(context)
    val vitalsMonitors = VitalsConduit(context, monitors, zone = ::zone)

    val sync = SyncManager(
        conduits = listOf(colmi, healthConnect, gadgetbridge, vitalsMonitors),
        store = syncStore,
        outbox = outbox,
        upload = { api.ingest(it) },
        uploadJson = { api.ingestJson(it) },
        onNewData = { dataChanged() },
    )

    /** Medications kept on the phone for reminders, the reminders themselves, and doses waiting to be sent. */
    val medsCache = MedsCache(context)
    val reminderCache = ReminderCache(context)
    val reminders = Reminders(context, medsCache, ::zone, reminderCache)
    val doseOutbox = DoseOutbox(File(context.filesDir, "dose-outbox"))

    // ---- Viewing someone else's data, from a share (read-only) ----

    private val _viewing = MutableStateFlow<Share?>(null)

    /** The share being viewed, or null for the person's own data. */
    val viewing: StateFlow<Share?> = _viewing.asStateFlow()

    private val _viewProfile = MutableStateFlow<Profile?>(null)

    /** The viewed person's profile, limited by the server to what's shared. */
    val viewProfile: StateFlow<Profile?> = _viewProfile.asStateFlow()

    /** Reads the viewed person's data; refuses any change before it leaves the phone. */
    val viewApi = ServerApi(http, { settings.value.server }, { settings.value.token }, viewAs = { _viewing.value?.ownerId })

    /** What screens read from: the person's own data, or the shared data being viewed. Background work always uses [api]. */
    val ui: ServerApi get() = if (_viewing.value != null) viewApi else api

    /** Starts (or, with null, stops) viewing a share. */
    suspend fun view(share: Share?) {
        _viewing.value = share
        _viewProfile.value = null
        if (share != null) _viewProfile.value = runCatching { viewApi.profile() }.getOrNull()
        _fields.value = null
        refreshFields()
        dataChanged()
    }

    private val _fields = MutableStateFlow<com.health.api.FieldLayout?>(null)

    /**
     * Which fields the forms show (from the person's conditions and choices, or what's shared while
     * viewing). Null until loaded: screens then use [com.health.api.Fields.DEFAULTS].
     */
    val fields: StateFlow<com.health.api.FieldLayout?> = _fields.asStateFlow()

    /** Reloads [fields]: after signing in, viewing a share, or changing conditions or fields. */
    suspend fun refreshFields() {
        _fields.value = try {
            ui.fields()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load the fields", e)
            _fields.value
        }
    }

    private val _layout = MutableStateFlow(com.health.api.LayoutPrefs())

    /** How the person has arranged each screen (their own, also while viewing a share). */
    val layout: StateFlow<com.health.api.LayoutPrefs> = _layout.asStateFlow()

    /** Reloads [layout] from the server, keeping what's there if it can't. */
    suspend fun refreshLayout() {
        runCatching { api.layout() }.onSuccess { _layout.value = it }.onFailure { Log.w(TAG, "Couldn't load the layout", it) }
    }

    /**
     * Saves one screen's arrangement ([com.health.api.Layouts] keys): shown at once, then sent to the
     * server; if that fails, it goes back to what was saved. An empty layout resets the screen.
     */
    suspend fun saveLayout(key: String, screen: com.health.api.ScreenLayout) {
        val before = _layout.value
        _layout.value = before.copy(screens = if (screen.order.isEmpty() && screen.hidden.isEmpty()) before.screens - key else before.screens + (key to screen))
        try {
            _layout.value = api.saveScreenLayout(key, screen)
        } catch (e: Exception) {
            _layout.value = before
            throw e
        }
    }

    /** The features to show: the viewed person's shared ones while viewing, else the person's own. */
    fun shownModules(): Set<Module> = if (_viewing.value != null) _viewProfile.value?.modules ?: _viewing.value?.modules.orEmpty() else modules()

    private val _profile = MutableStateFlow<Profile?>(null)

    /** The person's profile and goals on the server, once loaded. */
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Goes up whenever data changes (logged here, or synced): screens reload when it does. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8)

    /** Short messages for the snackbar, some with one thing to do about them ("Undo"). */
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    /** A snackbar's words, and its action. */
    class Message(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

    fun tell(message: String) {
        _messages.tryEmit(Message(message))
    }

    /** A message with an action ([action] names it: "Undo"). */
    fun tell(message: String, action: String, onAction: () -> Unit) {
        _messages.tryEmit(Message(message, action, onAction))
    }

    private val _serverVersion = MutableStateFlow<String?>(null)

    /** The server's version (`GET /api/health`), once known: Today and Settings say when it's older than the app. */
    val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

    /** Asks the server its version: after signing in, and each time the app starts. */
    suspend fun refreshServerVersion() {
        runCatching { api.serverHealth().version }.onSuccess { _serverVersion.value = it }.onFailure { Log.w(TAG, "Couldn't ask the server its version", it) }
    }

    fun dataChanged() {
        _revision.value++
    }

    /** The modules the person has on (the defaults until the profile has loaded). */
    fun modules(): Set<Module> = profile.value?.modules ?: Module.DEFAULT

    /**
     * Brings the phone's copy of the medications up to date and sets the next reminder; with
     * Medications off, forgets them and takes any reminders away.
     */
    suspend fun refreshReminders() {
        val modules = modules()
        val today = java.time.LocalDate.now(zone())
        profile.value?.let { reminderCache.savePrefs(it.reminders, modules) }
        val any = Module.MEDICATIONS in modules || Module.HABITS in modules || Module.APPOINTMENTS in modules ||
            Module.CONTINENCE in modules // Bladder training (bladder and bowel detail)
        if (!any) {
            medsCache.clear()
            reminderCache.clear()
            reminders.cancelAll()
            return
        }
        if (Module.MEDICATIONS in modules) {
            try {
                medsCache.save(api.medications())
                runCatching { api.medsDay(today.toString()) }.getOrNull()?.let {
                    medsCache.saveSites(it)
                    reminderCache.saveMarked(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't load the medications; reminders use the ones kept", e)
            }
        } else {
            medsCache.clear()
        }
        // Habits with a reminder time, and whether each is done today.
        if (Module.HABITS in modules) {
            runCatching { api.habitsForReminders(today) }.getOrNull()?.let { list ->
                reminderCache.saveHabits(
                    list.filter { it.habit.active && it.habit.reminderTime != null && it.habit.id != null }
                        .map { HabitReminder(it.habit.id!!, it.habit.name, it.habit.reminderTime!!, it.date, it.done) },
                )
            }
        } else {
            reminderCache.saveHabits(emptyList())
        }
        if (Module.APPOINTMENTS in modules) {
            runCatching { api.appointments(days = 60, upcoming = true) }.getOrNull()?.let(reminderCache::saveAppointments)
        } else {
            reminderCache.saveAppointments(emptyList())
        }
        // Bladder training (bladder and bowel detail): its settings, kept for the phone's own reminders.
        if (Module.CONTINENCE in modules) {
            runCatching { api.continenceSettings() }.getOrNull()?.let { reminderCache.saveBladder(it.bladderTraining.takeIf { t -> t.enabled }) }
            saveChangeDue()
        } else {
            reminderCache.saveBladder(null)
            reminderCache.saveChangeDue(null, null)
        }
        reminders.reschedule()
    }

    /** Keeps when a check or change of what's being worn is due, for the phone's own reminder. */
    private suspend fun saveChangeDue() {
        runCatching { api.continenceWearing() }.onSuccess { w ->
            reminderCache.saveChangeDue(w.changeDueAt.takeIf { !w.nothingOn }, w.since)
        }
    }

    /** After a change is logged: the next check moves, so the phone's reminder does too. */
    suspend fun continenceChanged() {
        if (com.health.api.Module.CONTINENCE !in modules()) return
        saveChangeDue()
        reminders.reschedule()
    }

    /** Sends doses marked from notifications while offline. */
    suspend fun flushDoses() {
        if (doseOutbox.size() == 0) return
        val sent = doseOutbox.flush { api.logDoseJson(it) }
        if (sent > 0) dataChanged()
    }

    /** The person's time zone: the profile's, else the phone's. */
    fun zone(): ZoneId = profile.value?.timezone?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    fun start() {
        scope.launch {
            settings.map { Triple(it.signedIn, it.backgroundSync, it.syncOnWifiOnly) }.distinctUntilChanged().collect { (signedIn, on, wifi) ->
                SyncWorker.schedule(context, signedIn && on, wifi)
                if (signedIn) {
                    runCatching { refreshServerVersion() }
                    loadProfile()
                    runCatching { refreshReminders() }
                    runCatching { flushDoses() }
                } else {
                    _profile.value = null
                    _fields.value = null
                    _layout.value = com.health.api.LayoutPrefs()
                    _serverVersion.value = null
                }
            }
        }
        startCare()
    }

    /**
     * Reminders follow what changes (a dose marked here or elsewhere, a sync): they're planned
     * again a few seconds after. The weekly digest's check follows the person's chosen time.
     */
    @OptIn(FlowPreview::class)
    private fun startCare() {
        scope.launch {
            revision.drop(1).debounce(3_000).collect {
                if (settings.value.signedIn) runCatching { refreshReminders() }
            }
        }
        scope.launch {
            profile.map { it?.digest to it?.timezone }.distinctUntilChanged().collect { (digest, _) ->
                if (digest != null) runCatching { DigestWorker.schedule(context, digest, zone()) }
            }
        }
    }

    /** Loads the profile; the first time, gives it this phone's time zone if it has none. */
    suspend fun loadProfile(): Profile? = try {
        var p = api.profile()
        if (p.timezone.isBlank()) p = api.saveProfile(p.copy(timezone = ZoneId.systemDefault().id))
        _profile.value = p
        prefs.update { it.copy(units = p.units) }
        refreshFields()
        refreshLayout()
        p
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't load the profile", e)
        _profile.value
    }

    suspend fun saveProfile(profile: Profile): Profile {
        val before = _profile.value?.modules
        val saved = api.saveProfile(profile)
        _profile.value = saved
        prefs.update { it.copy(units = saved.units) }
        if (before != saved.modules) refreshFields()
        dataChanged()
        if (before != saved.modules || saved.reminders != reminderCache.prefs()) runCatching { refreshReminders() }
        return saved
    }

    /** Syncs now, from the Sync button: tells the person how it went. */
    fun syncNow(onlyId: String? = null) {
        scope.launch {
            val result = runCatching { sync.sync(onlyId) }.getOrElse { it.message ?: "Couldn't sync" }
            tell(result.lineSequence().first())
        }
    }

    private var lastQuietSync = 0L

    /** Syncs when the app opens, at most every 15 minutes, without a message. */
    fun syncNowQuietly() {
        val now = System.currentTimeMillis()
        if (now - lastQuietSync < 15 * 60_000L || sync.running.value != null) return
        lastQuietSync = now
        scope.launch {
            runCatching { flushDoses() }
            runCatching { sync.sync() }
        }
    }

    suspend fun signOut(callServer: Boolean = true) {
        if (callServer) api.logout()
        prefs.signOut()
        syncStore.clear()
        outbox.clear()
        doseOutbox.clear()
        medsCache.clear()
        reminderCache.clear()
        reminders.cancelAll()
        monitors.clear()
        runCatching { DigestWorker.schedule(context, null, zone()) }
        _viewing.value = null
        _viewProfile.value = null
        _profile.value = null
        _fields.value = null
        _layout.value = com.health.api.LayoutPrefs()
        // The person's own cycle wording, cached for Today's card.
        com.health.app.ui.modules.cycle.CycleWording.neutral.value = null
    }

    // ---- Wave 3: writing to Health Connect after each sync (Settings › Export), routine
    // reminders (Autism & ADHD), and forgetting both, the focus timer and photos on signing out ----

    val healthConnectWriter = com.health.app.devices.healthconnect.HealthConnectWriter(context, api, com.health.app.data.DeviceChoices.of(context), ::zone)

    val routineReminders = com.health.app.reminders.RoutineReminders(context)

    /** Keeps the routines' reminders on the phone in step with the server (only the person's own). */
    suspend fun refreshRoutineReminders() {
        if (!settings.value.signedIn || _viewing.value != null) return
        if (Module.NEURO !in modules()) return routineReminders.clear()
        routineReminders.refresh(api, zone())
    }

    @OptIn(FlowPreview::class)
    private fun startWaveThree() {
        sync.afterSync = { healthConnectWriter.run() }
        scope.launch {
            profile.map { it?.modules }.distinctUntilChanged().collect { runCatching { refreshRoutineReminders() } }
        }
        scope.launch {
            revision.drop(1).debounce(5_000).collect { runCatching { refreshRoutineReminders() } }
        }
        scope.launch {
            settings.map { it.signedIn }.distinctUntilChanged().drop(1).collect { signedIn ->
                if (!signedIn) {
                    com.health.app.data.DeviceChoices.of(context).forgetAccount()
                    healthConnectWriter.forget()
                    routineReminders.clear()
                    com.health.app.reminders.FocusAlerts.stop(context)
                    com.health.app.ui.modules.photos.PhotoUnlock.lock()
                }
            }
        }
    }

    init {
        startWaveThree()
    }

    // ---- Voice trainer (ui/modules/transition/voice): its practice reminder follows the plan and
    // Transition being on; signing out cancels it and deletes what the trainer kept here, recordings included ----
    init {
        scope.launch {
            settings.map { it.signedIn }.distinctUntilChanged().drop(1).collect { signedIn ->
                if (!signedIn) com.health.app.ui.modules.transition.voice.VoiceReminders.forget(context)
            }
        }
        scope.launch {
            profile.map { it?.modules?.contains(Module.TRANSITION) }.distinctUntilChanged().collect { on ->
                if (on != null && _viewing.value == null) runCatching { com.health.app.ui.modules.transition.voice.VoiceReminders.refresh(context, api, on, zone()) }
            }
        }
    }

    companion object {
        private const val TAG = "Health"
    }
}
