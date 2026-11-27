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
import com.health.app.devices.strap.StrapConduit
import com.health.app.devices.strap.StrapRecorder
import com.health.app.devices.strap.StrapService
import com.health.app.devices.strap.StrapStore
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
import com.health.app.net.saveAllLayouts
import com.health.app.net.saveScreenLayout
import com.health.app.net.serverHealth
import com.health.app.net.habitsForReminders
import com.health.api.HealthAlert
import com.health.api.HealthAlerts
import com.health.app.net.alerts
import com.health.app.reminders.AlertNotices
import com.health.app.reminders.DigestWorker
import com.health.app.ui.alerts.Alerts
import com.health.app.reminders.DoseOutbox
import com.health.app.reminders.HabitReminder
import com.health.app.reminders.MedsCache
import com.health.app.reminders.ReminderCache
import com.health.app.reminders.ReminderRefresh
import com.health.app.reminders.Reminders
import kotlinx.coroutines.CancellationException
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

    val api = ServerApi(http, { settings.value.server }, { settings.value.token }, onSignedOut = { scope.launch { signOut(requested = false) } })

    val signIn = SignIn(context, api, prefs)

    /** Signing in as whoever is signed in to SuperAwesomeMagicalOrchestrator on this phone (sign-in only: nothing else goes to it). */
    val orchestratorSignIn = com.health.app.orchestrator.OrchestratorSignIn(com.health.app.orchestrator.OrchestratorSignIn.onThisPhone(context), api, prefs)

    val syncStore = SyncStore(context)
    val outbox = Outbox(File(context.filesDir, "outbox"))
    val colmi = ColmiConduit(context, syncStore, zone = ::zone)
    val healthConnect = HealthConnectConduit(context, syncStore)
    val gadgetbridge = GadgetbridgeConduit(context, syncStore)

    /** Blood pressure monitors and glucose meters paired with this phone. */
    val monitors = MonitorStore(context)
    val vitalsMonitors = VitalsConduit(context, monitors, zone = ::zone)

    /** The heart rate strap chosen on this phone, and what its recordings have waiting for the server (devices/strap). */
    val strapStore = StrapStore(context)
    val strap = StrapConduit(strapStore, syncStore)

    val sync = SyncManager(
        conduits = listOf(colmi, healthConnect, gadgetbridge, vitalsMonitors, strap),
        store = syncStore,
        outbox = outbox,
        upload = { api.ingest(it) },
        uploadJson = { api.ingestJson(it) },
        onNewData = { dataChanged() },
        account = { settings.value.account },
    )

    /** Records the strap (with [StrapService] keeping the app going), sending what it has about every 15 minutes. */
    val strapRecorder = StrapRecorder(context, strapStore, scope, upload = { sync.sync(StrapConduit.ID) })

    /** A recording the app was closed during (and Android didn't start again) carries on once the app is open. */
    fun resumeStrapRecording() {
        if (settings.value.signedIn && strapStore.recording.value != null && !strapRecorder.running) StrapService.start(context)
    }

    // ---- Health alerts (ui/alerts): Today's bell, and a notification for each new one after a sync ----

    /** Tells the person of new health alerts by notification (no push service: the phone asks after each sync). */
    val alertNotices = AlertNotices(context)

    private val alertsLock = Any()
    private var unseenKnown: Map<String, Long> = emptyMap()
    private val _unseenAlerts = MutableStateFlow(0)

    /**
     * How many health alerts are unseen and not put away (Today's bell; any other screen can show
     * it too). Kept up to date by [refreshAlerts], which each sync calls ([checkAlerts]).
     */
    val unseenAlerts: StateFlow<Int> = _unseenAlerts.asStateFlow()

    private val _alerts = MutableStateFlow<HealthAlerts?>(null)

    /**
     * The alerts last looked up (either window covers what's still active): Today's alert card and
     * Body's Health alerts row. Never someone else's: alerts are only ever the person's own.
     */
    val alerts: StateFlow<HealthAlerts?> = _alerts.asStateFlow()

    /** The last [days] of the person's own alerts, [unseenAlerts] following; null when signed out or the server can't be reached. */
    suspend fun refreshAlerts(days: Int = Alerts.DAYS): HealthAlerts? {
        if (!settings.value.signedIn) return null
        val asked = System.currentTimeMillis()
        val result = try {
            api.alerts(days)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Couldn't look for health alerts: ${e.javaClass.simpleName}")
            return null
        }
        alertsLoaded(result, days, asked)
        return result
    }

    /** [result] is the server's word on the alerts of the [days] before [at]: the bell follows (a whole page's worth replaces what was known). */
    fun alertsLoaded(result: HealthAlerts, days: Int, at: Long = System.currentTimeMillis()) {
        val since = if (days >= Alerts.DAYS) Long.MIN_VALUE else at - days * 86_400_000L
        synchronized(alertsLock) {
            unseenKnown = Alerts.mergeUnseen(unseenKnown, result.alerts, since, result.settings.enabled)
            _unseenAlerts.value = unseenKnown.size
            _alerts.value = result
        }
    }

    /** [changed] as it now is (put away): Today's card and Body's row follow. */
    fun alertChanged(changed: HealthAlert) {
        synchronized(alertsLock) {
            _alerts.value = _alerts.value?.let { it.copy(alerts = Alerts.replace(it.alerts, changed)) }
        }
    }

    /** Every alert is seen now (the alerts page opened), or [id] alone is (put away). */
    fun alertsSeen(id: String? = null) {
        synchronized(alertsLock) {
            unseenKnown = if (id == null) emptyMap() else unseenKnown - id
            _unseenAlerts.value = unseenKnown.size
        }
    }

    /** After each sync (and a strap's upload while it records): the last two days' alerts, and a notification for each one not told of yet. */
    suspend fun checkAlerts() {
        val result = refreshAlerts(Alerts.RECENT_DAYS) ?: return
        alertNotices.tell(result, reminders.canNotify())
    }

    /** Medications kept on the phone for reminders, the reminders themselves, and doses waiting to be sent. */
    val medsCache = MedsCache(context)
    val reminderCache = ReminderCache(context)
    val reminders = Reminders(context, medsCache, ::zone, reminderCache)
    val doseOutbox = DoseOutbox(File(context.filesDir, "dose-outbox"))

    init {
        sync.afterEach = { checkAlerts() }
    }

    /** One voice sender at a time, however many of the trainer's pages are open (ui/modules/transition/voice). */
    val voiceSending = kotlinx.coroutines.sync.Mutex()

    init {
        // What an older version kept waiting (not yet under an account) is the signed-in account's.
        settings.value.account?.let { account ->
            attempt { outbox.adopt(account) }
            attempt { doseOutbox.adopt(account) }
            attempt { com.health.app.ui.modules.transition.voice.VoiceStore(context).adopt(account) }
        }
    }

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
        if (share != null) _viewProfile.value = attempt { viewApi.profile() }.getOrNull()
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
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "Couldn't load the fields", e)
            _fields.value
        }
    }

    private val _layout = MutableStateFlow(com.health.api.LayoutPrefs())

    /** How the person has arranged each screen (their own, also while viewing a share). */
    val layout: StateFlow<com.health.api.LayoutPrefs> = _layout.asStateFlow()

    /** Reloads [layout] from the server, keeping what's there if it can't. */
    suspend fun refreshLayout() {
        attempt { api.layout() }.onSuccess { _layout.value = it }.onFailure { Log.w(TAG, "Couldn't load the layout", it) }
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
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _layout.value = before
            throw e
        }
    }

    /** Puts every arranged screen back as it was, in both apps (Settings › Appearance): shown at once, undone if the server says no. */
    suspend fun resetLayouts() {
        val before = _layout.value
        _layout.value = com.health.api.LayoutPrefs()
        try {
            _layout.value = api.saveAllLayouts(com.health.api.LayoutPrefs())
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
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
        // Work stopped on purpose (a screen reloading or closing) is never news.
        if (isCancellationMessage(message)) return
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
        attempt { api.serverHealth().version }.onSuccess { _serverVersion.value = it }.onFailure { Log.w(TAG, "Couldn't ask the server its version", it) }
    }

    fun dataChanged() {
        _revision.value++
    }

    /** The modules the person has on (the defaults until the profile has loaded). */
    fun modules(): Set<Module> = profile.value?.modules ?: Module.DEFAULT

    /** Whether reminders (and the focus timer) name what they're for: the person's choice, kept for when the profile hasn't loaded. */
    fun showNames(): Boolean = profile.value?.reminders?.showNames ?: reminderCache.prefs().showNames

    /** The features reminders follow: the profile's, or before it loads the ones kept with the reminders. */
    private fun reminderModules(): Set<Module> = profile.value?.modules ?: reminderCache.modules()

    /**
     * Brings the phone's copy of the medications (and the rest reminders need) up to date and sets
     * the next reminder; with Medications off, forgets them and takes any reminders away. Before
     * the profile loads (an alarm, a restart or the background sync can start the app while the
     * server can't be reached) it works from the features last known and forgets nothing; with
     * none known, the reminders kept here carry on. A load that fails keeps what was kept.
     */
    suspend fun refreshReminders() {
        val plan = ReminderRefresh.of(profile.value?.modules, reminderCache.modules())
        if (plan == null) {
            reminders.reschedule()
            return
        }
        val modules = plan.modules
        val today = java.time.LocalDate.now(zone())
        profile.value?.let { reminderCache.savePrefs(it.reminders, modules) }
        if (!plan.any) {
            if (plan.forget) {
                // Every feature that reminds is off: forget what's kept for them (the zone and choices stay).
                reminders.cancelAll()
                medsCache.clear()
                reminderCache.clear()
                profile.value?.let {
                    reminderCache.savePrefs(it.reminders, modules)
                    reminderCache.saveZone(it.timezone)
                }
            }
            return
        }
        if (Module.MEDICATIONS in modules) {
            try {
                medsCache.save(api.medications())
                attempt { api.medsDay(today.toString()) }.getOrNull()?.let {
                    medsCache.saveSites(it)
                    reminderCache.saveMarked(it)
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "Couldn't load the medications; reminders use the ones kept", e)
            }
        } else if (plan.forgets(Module.MEDICATIONS)) {
            medsCache.clear()
        }
        // Habits with a reminder time, and whether each is done today.
        if (Module.HABITS in modules) {
            attempt { api.habitsForReminders(today) }.getOrNull()?.let { list ->
                reminderCache.saveHabits(
                    list.filter { it.habit.active && it.habit.reminderTime != null && it.habit.id != null }
                        .map { HabitReminder(it.habit.id!!, it.habit.name, it.habit.reminderTime!!, it.date, it.done) },
                )
            }
        } else if (plan.forgets(Module.HABITS)) {
            reminderCache.saveHabits(emptyList())
        }
        if (Module.APPOINTMENTS in modules) {
            attempt { api.appointments(days = 60, upcoming = true) }.getOrNull()?.let(reminderCache::saveAppointments)
        } else if (plan.forgets(Module.APPOINTMENTS)) {
            reminderCache.saveAppointments(emptyList())
        }
        // Bladder training (bladder and bowel detail): its settings, kept for the phone's own reminders.
        if (Module.CONTINENCE in modules) {
            attempt { api.continenceSettings() }.getOrNull()?.let { reminderCache.saveBladder(it.bladderTraining.takeIf { t -> t.enabled }) }
            saveChangeDue()
        } else if (plan.forgets(Module.CONTINENCE)) {
            reminderCache.saveBladder(null)
            reminderCache.saveChangeDue(null, null)
        }
        reminders.reschedule()
    }

    /** Keeps when a check or change of what's being worn is due, for the phone's own reminder. */
    private suspend fun saveChangeDue() {
        attempt { api.continenceWearing() }.onSuccess { w ->
            reminderCache.saveChangeDue(w.changeDueAt.takeIf { !w.nothingOn }, w.since)
        }
    }

    /** After a change is logged: the next check moves, so the phone's reminder does too. */
    suspend fun continenceChanged() {
        if (com.health.api.Module.CONTINENCE !in reminderModules()) return
        saveChangeDue()
        reminders.reschedule()
    }

    /** Sends doses marked from notifications while offline (the signed-in account's only). */
    suspend fun flushDoses() {
        val account = settings.value.account ?: return
        if (doseOutbox.size(account) == 0) return
        val sent = doseOutbox.flush(account, { api.logDoseJson(it) })
        if (sent > 0) dataChanged()
    }

    /**
     * The person's time zone: the profile's; before it loads, the one kept with the reminders (what
     * they were planned in, so an alarm that starts the app finds what's due); else the phone's.
     */
    fun zone(): ZoneId = ReminderRefresh.zone(profile.value?.timezone, reminderCache.zone())

    /** Shows [p] as the person's profile, and keeps its time zone for the reminders. */
    private fun setProfile(p: Profile) {
        _profile.value = p
        reminderCache.saveZone(p.timezone)
    }

    fun start() {
        scope.launch {
            settings.map { Triple(it.signedIn, it.backgroundSync, it.syncOnWifiOnly) }.distinctUntilChanged().collect { (signedIn, on, wifi) ->
                SyncWorker.schedule(context, signedIn && on, wifi)
                if (signedIn) {
                    attempt { refreshServerVersion() }
                    loadProfile()
                    attempt { refreshReminders() }
                    attempt { flushDoses() }
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
                if (settings.value.signedIn) attempt { refreshReminders() }
            }
        }
        scope.launch {
            profile.map { it?.digest to it?.timezone }.distinctUntilChanged().collect { (digest, _) ->
                if (digest != null) attempt { DigestWorker.schedule(context, digest, zone()) }
            }
        }
    }

    /** Loads the profile; the first time, gives it this phone's time zone if it has none. */
    suspend fun loadProfile(): Profile? = try {
        var p = api.profile()
        if (p.timezone.isBlank()) p = api.saveProfile(p.copy(timezone = ZoneId.systemDefault().id))
        setProfile(p)
        prefs.update { it.copy(units = p.units) }
        refreshFields()
        refreshLayout()
        p
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w(TAG, "Couldn't load the profile", e)
        _profile.value
    }

    suspend fun saveProfile(profile: Profile): Profile {
        val before = _profile.value?.modules
        val saved = api.saveProfile(profile)
        setProfile(saved)
        prefs.update { it.copy(units = saved.units) }
        if (before != saved.modules) refreshFields()
        dataChanged()
        if (before != saved.modules || saved.reminders != reminderCache.prefs()) attempt { refreshReminders() }
        return saved
    }

    /** Syncs now, from the Sync button: tells the person how it went. The job ends when the sync does. */
    fun syncNow(onlyId: String? = null): kotlinx.coroutines.Job =
        scope.launch {
            val result = attempt { sync.sync(onlyId) }.getOrElse { it.message ?: "Couldn't sync" }
            tell(result.lineSequence().first())
        }

    private var lastQuietSync = 0L

    /** Syncs when the app opens, at most every 15 minutes, without a message. */
    fun syncNowQuietly() {
        val now = System.currentTimeMillis()
        if (now - lastQuietSync < 15 * 60_000L || sync.running.value != null) return
        lastQuietSync = now
        scope.launch {
            attempt { flushDoses() }
            attempt { sync.sync() }
        }
    }

    /**
     * Signs out. When the person asks ([requested]), the server is told, and what this account had
     * waiting to be sent goes with the rest. When the server says the sign-in is no longer good (a
     * revoked or expired sign-in), what's waiting to be sent stays, kept under its account: it goes
     * once the same person signs in to the same server again, and never to anyone else.
     */
    suspend fun signOut(requested: Boolean = true) {
        val account = settings.value.account
        if (requested) {
            api.logout()
        } else if (!settings.value.signedIn) {
            return // Already signed out: several requests can come back refused at once.
        }
        prefs.signOut(onPurpose = requested)
        // The strap stops recording, and what it has waiting is forgotten with the rest, or kept under
        // the account when the sign-out wasn't asked for (needs the strap's cursor: before the sync store goes).
        strapRecorder.stop(send = false)
        attempt { strap.signedOut(account, keep = !requested, outbox) }
        syncStore.clear()
        if (requested && account != null) {
            outbox.clear(account)
            doseOutbox.clear(account)
        }
        // The practice reminder, and what the voice trainer kept (recordings too; unsent sessions as above).
        com.health.app.ui.modules.transition.voice.VoiceReminders.forget(context, dropUnsentOf = account.takeIf { requested })
        // Snoozed doses' alarms are found from the reminders kept, so they go first.
        reminders.cancelAll()
        medsCache.clear()
        reminderCache.clear()
        alertNotices.clear()
        alertsSeen()
        _alerts.value = null
        // Nothing personal stays in the notification shade.
        androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
        monitors.clear()
        attempt { DigestWorker.schedule(context, null, zone()) }
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

    /**
     * Keeps the routines' reminders on the phone in step with the server (only the person's own).
     * Until the profile loads nothing is known to be off, so what's kept stays; and a refresh that
     * fails keeps it too.
     */
    suspend fun refreshRoutineReminders() {
        when (com.health.app.reminders.RoutinePlanner.action(settings.value.signedIn, _viewing.value != null, profile.value?.modules)) {
            com.health.app.reminders.RoutineAction.KEEP -> Unit
            com.health.app.reminders.RoutineAction.CLEAR -> routineReminders.clear()
            com.health.app.reminders.RoutineAction.REFRESH -> routineReminders.refresh(api, zone())
        }
    }

    @OptIn(FlowPreview::class)
    private fun startWaveThree() {
        sync.afterSync = { healthConnectWriter.run() }
        scope.launch {
            profile.map { it?.modules }.distinctUntilChanged().collect { attempt { refreshRoutineReminders() } }
        }
        scope.launch {
            revision.drop(1).debounce(5_000).collect { attempt { refreshRoutineReminders() } }
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
    // Transition being on; signing out (in signOut) cancels it and deletes what the trainer kept here,
    // recordings included ----
    init {
        scope.launch {
            profile.map { it?.modules?.contains(Module.TRANSITION) }.distinctUntilChanged().collect { on ->
                if (on != null && _viewing.value == null) attempt { com.health.app.ui.modules.transition.voice.VoiceReminders.refresh(context, api, on, zone()) }
            }
        }
    }

    // ---- Guided meditation (ui/meditation): the nightly "Time to wind down" is the phone's own alarm,
    // from the person's settings on the server; planned again when they're loaded or changed, after
    // each sync (data changing), and forgotten on signing out. Only ever the person's own. ----

    /** The reminder's settings as the server has them (loaded or just saved): the alarm follows. */
    fun meditationSettingsLoaded(settings: com.health.api.MeditationSettings) {
        if (_viewing.value != null) return
        attempt { com.health.app.ui.meditation.MeditationReminders(context).update(settings, zone()) }
    }

    @OptIn(FlowPreview::class)
    private fun startMeditation() {
        scope.launch {
            profile.map { it?.timezone }.distinctUntilChanged().collect { zone ->
                if (zone != null && settings.value.signedIn && _viewing.value == null) {
                    attempt { com.health.app.ui.meditation.MeditationReminders.refresh(context, api, zone()) }
                }
            }
        }
        scope.launch {
            revision.drop(1).debounce(5_000).collect {
                if (settings.value.signedIn && _viewing.value == null) attempt { com.health.app.ui.meditation.MeditationReminders.refresh(context, api, zone()) }
            }
        }
        scope.launch {
            settings.map { it.signedIn }.distinctUntilChanged().drop(1).collect { signedIn ->
                if (!signedIn) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) { com.health.app.ui.meditation.MeditationPlayback.stop() }
                    com.health.app.ui.meditation.MeditationReminders.forget(context)
                }
            }
        }
    }

    init {
        startMeditation()
    }

    companion object {
        private const val TAG = "Health"
    }
}
