package com.health.app.ui.meditation

import android.content.Context
import androidx.core.content.edit
import com.health.api.ApiJson
import com.health.api.Meditation
import com.health.api.MeditationSettings
import com.health.app.attempt
import kotlinx.serialization.builtins.ListSerializer

/**
 * What guided meditation keeps on the phone: the scripts (so the player works without the server),
 * the person's choices here (read aloud or text only, fading out at the end), the one used last,
 * when one last began (so the reminder keeps quiet after it), and the reminder's settings for its
 * alarm. Forgotten on signing out.
 */
class MeditationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("meditation", Context.MODE_PRIVATE)

    fun meditations(): List<Meditation> = prefs.getString(SCRIPTS, null)?.let {
        attempt { ApiJson.decodeFromString(ListSerializer(Meditation.serializer()), it) }.getOrNull()
    }.orEmpty()

    fun saveMeditations(list: List<Meditation>) {
        if (list.isEmpty()) return
        prefs.edit { putString(SCRIPTS, ApiJson.encodeToString(ListSerializer(Meditation.serializer()), list)) }
    }

    fun meditation(id: String): Meditation? = meditations().firstOrNull { it.id == id }

    /** Read aloud (on until the person chooses text only). */
    var voice: Boolean
        get() = prefs.getBoolean(VOICE, true)
        set(value) = prefs.edit { putBoolean(VOICE, value) }

    /** When it ends, the screen stays dark and nothing more is said. */
    var fadeOut: Boolean
        get() = prefs.getBoolean(FADE, false)
        set(value) = prefs.edit { putBoolean(FADE, value) }

    var lastId: String?
        get() = prefs.getString(LAST, null)
        set(value) = prefs.edit { if (value == null) remove(LAST) else putString(LAST, value) }

    var lastStartedAt: Long?
        get() = prefs.getLong(STARTED, 0L).takeIf { it > 0 }
        set(value) = prefs.edit { if (value == null) remove(STARTED) else putLong(STARTED, value) }

    fun settings(): MeditationSettings? = prefs.getString(SETTINGS, null)?.let { attempt { ApiJson.decodeFromString(MeditationSettings.serializer(), it) }.getOrNull() }

    fun saveSettings(settings: MeditationSettings) = prefs.edit { putString(SETTINGS, ApiJson.encodeToString(MeditationSettings.serializer(), settings)) }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val SCRIPTS = "scripts"
        const val VOICE = "voice"
        const val FADE = "fadeOut"
        const val LAST = "last"
        const val STARTED = "startedAt"
        const val SETTINGS = "settings"
    }
}
