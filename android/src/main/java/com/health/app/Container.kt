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
import com.health.app.net.ServerApi
import com.health.app.net.SignIn
import com.health.app.reminders.DoseOutbox
import com.health.app.reminders.MedsCache
import com.health.app.reminders.Reminders
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
    val reminders = Reminders(context, medsCache, ::zone)
    val doseOutbox = DoseOutbox(File(context.filesDir, "dose-outbox"))

    private val _profile = MutableStateFlow<Profile?>(null)

    /** The person's profile and goals on the server, once loaded. */
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _revision = MutableStateFlow(0)

    /** Goes up whenever data changes (logged here, or synced): screens reload when it does. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun tell(message: String) {
        _messages.tryEmit(message)
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
        if (Module.MEDICATIONS !in modules()) {
            medsCache.clear()
            reminders.cancelAll()
            return
        }
        try {
            medsCache.save(api.medications())
            runCatching { api.medsDay(java.time.LocalDate.now(zone()).toString()) }.getOrNull()?.let(medsCache::saveSites)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load the medications; reminders use the ones kept", e)
        }
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
                    loadProfile()
                    runCatching { refreshReminders() }
                    runCatching { flushDoses() }
                } else {
                    _profile.value = null
                }
            }
        }
    }

    /** Loads the profile; the first time, gives it this phone's time zone if it has none. */
    suspend fun loadProfile(): Profile? = try {
        var p = api.profile()
        if (p.timezone.isBlank()) p = api.saveProfile(p.copy(timezone = ZoneId.systemDefault().id))
        _profile.value = p
        prefs.update { it.copy(units = p.units) }
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
        dataChanged()
        if (before != saved.modules) runCatching { refreshReminders() }
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
        reminders.cancelAll()
        monitors.clear()
        _profile.value = null
    }

    companion object {
        private const val TAG = "Health"
    }
}
