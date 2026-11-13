package com.health.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Choices that belong to this phone, not the account, so they never leave it: calm mode, the
 * progress photos' lock, and which kinds of data go to Health Connect. Plain SharedPreferences, so
 * the theme can read calm mode before anything else has loaded.
 */
class DeviceChoices private constructor(app: Context) {
    private val prefs = app.getSharedPreferences("device-choices", Context.MODE_PRIVATE)

    /** Which app it was made for (no Context is kept). */
    private val owner = System.identityHashCode(app)

    private val _calm = MutableStateFlow(prefs.getBoolean(CALM, false))

    /** Calm mode: less motion, no aurora glow, softer colours, everywhere in the app. */
    val calm: StateFlow<Boolean> = _calm.asStateFlow()

    fun setCalm(on: Boolean) {
        prefs.edit { putBoolean(CALM, on) }
        _calm.value = on
    }

    private val _photoLock = MutableStateFlow(prefs.getBoolean(PHOTO_LOCK, false))

    /** Asks for a fingerprint, face or the screen lock before progress photos show. */
    val photoLock: StateFlow<Boolean> = _photoLock.asStateFlow()

    fun setPhotoLock(on: Boolean) {
        prefs.edit { putBoolean(PHOTO_LOCK, on) }
        _photoLock.value = on
    }

    private val _export = MutableStateFlow(prefs.getStringSet(EXPORT, emptySet()).orEmpty().toSet())

    /** The kinds of data written to Health Connect (names of HealthConnectExport.Kind). */
    val healthConnectExport: StateFlow<Set<String>> = _export.asStateFlow()

    fun setHealthConnectExport(kinds: Set<String>) {
        prefs.edit { putStringSet(EXPORT, kinds) }
        _export.value = kinds
    }

    private val _exportNote = MutableStateFlow(prefs.getString(EXPORT_NOTE, null))

    /** How the last write to Health Connect went, for Settings › Export. */
    val healthConnectNote: StateFlow<String?> = _exportNote.asStateFlow()

    fun setHealthConnectNote(note: String?) {
        prefs.edit { if (note == null) remove(EXPORT_NOTE) else putString(EXPORT_NOTE, note) }
        _exportNote.value = note
    }

    /** Signing out: what was chosen for the account goes (calm mode stays: it's about this phone). */
    fun forgetAccount() {
        setHealthConnectExport(emptySet())
        setHealthConnectNote(null)
        setPhotoLock(false)
    }

    companion object {
        private const val CALM = "calm"
        private const val PHOTO_LOCK = "photo_lock"
        private const val EXPORT = "health_connect_export"
        private const val EXPORT_NOTE = "health_connect_export_note"

        @Volatile
        private var instance: DeviceChoices? = null

        /** The one for this app (made again if the app was, as between tests). */
        fun of(context: Context): DeviceChoices {
            val app = context.applicationContext ?: context
            val owner = System.identityHashCode(app)
            return instance?.takeIf { it.owner == owner } ?: synchronized(this) {
                instance?.takeIf { it.owner == owner } ?: DeviceChoices(app).also { instance = it }
            }
        }
    }
}
