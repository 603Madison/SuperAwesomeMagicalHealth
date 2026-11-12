package com.fitness.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fitness.api.ApiJson
import com.fitness.api.Units
import com.fitness.api.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("fitness")

/** The app's settings, kept on the phone. */
data class Settings(
    /** The server's address, e.g. https://fitness.example.com; empty before signing in. */
    val server: String = "",
    /** The app's sign-in token (long-lived, revocable from the server's Settings). */
    val token: String? = null,
    val user: User? = null,
    val serverName: String = "",
    /** Light, dark, or as the phone is. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** The phone's wallpaper colours (Android 12 and later) instead of the theme's. */
    val wallpaperColors: Boolean = false,
    /** All text in Lexend Bold, as the phone's own Bold text setting draws it. */
    val boldText: Boolean = false,
    /** The colours (unless the wallpaper's are used). */
    val palette: Palette = Palette.AURORA,
    /** Sync devices in the background, about once an hour. */
    val backgroundSync: Boolean = true,
    /** Only sync on Wi-Fi (or another unmetered network). */
    val syncOnWifiOnly: Boolean = false,
    /** Metric or imperial, as the profile on the server says (kept here so it's known offline). */
    val units: Units = Units.METRIC,
) {
    val signedIn: Boolean get() = server.isNotEmpty() && token != null
}

/** Light, dark, or as the phone is. */
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The themes, as on the web app, with the two colours each shows in Settings. */
enum class Palette(val label: String, val swatchA: Long, val swatchB: Long) {
    AURORA("Aurora", 0xFF6A3FD6, 0xFF12A37C),
    VIOLET("Violet", 0xFF5B3FD1, 0xFF8E3F5C),
    OCEAN("Ocean", 0xFF1D5FC9, 0xFF0E8FA3),
    EMBER("Ember", 0xFFB4471A, 0xFF9C3F60),
}

class Prefs(private val context: Context) {
    val settings: Flow<Settings> = context.store.data.map(::read)

    suspend fun current(): Settings = settings.first()

    suspend fun signIn(server: String, token: String, user: User, serverName: String) = context.store.edit {
        it[SERVER] = server
        it[TOKEN] = token
        it[USER] = ApiJson.encodeToString(User.serializer(), user)
        it[SERVER_NAME] = serverName
    }

    suspend fun signOut() = context.store.edit {
        it.remove(TOKEN)
        it.remove(USER)
    }

    suspend fun update(transform: (Settings) -> Settings) = context.store.edit { prefs ->
        val next = transform(read(prefs))
        prefs[THEME_MODE] = next.themeMode.name
        prefs[WALLPAPER_COLORS] = next.wallpaperColors
        prefs[BOLD_TEXT] = next.boldText
        prefs[PALETTE] = next.palette.name
        prefs[BACKGROUND_SYNC] = next.backgroundSync
        prefs[SYNC_WIFI_ONLY] = next.syncOnWifiOnly
        prefs[UNITS] = next.units.name
        next.user?.let { prefs[USER] = ApiJson.encodeToString(User.serializer(), it) }
    }

    private fun read(prefs: Preferences) = Settings(
        server = prefs[SERVER].orEmpty(),
        token = prefs[TOKEN],
        user = prefs[USER]?.let { runCatching { ApiJson.decodeFromString(User.serializer(), it) }.getOrNull() },
        serverName = prefs[SERVER_NAME].orEmpty(),
        themeMode = ThemeMode.entries.firstOrNull { it.name == prefs[THEME_MODE] } ?: ThemeMode.SYSTEM,
        wallpaperColors = prefs[WALLPAPER_COLORS] ?: false,
        boldText = prefs[BOLD_TEXT] ?: false,
        palette = Palette.entries.firstOrNull { it.name == prefs[PALETTE] } ?: Palette.AURORA,
        backgroundSync = prefs[BACKGROUND_SYNC] ?: true,
        syncOnWifiOnly = prefs[SYNC_WIFI_ONLY] ?: false,
        units = Units.entries.firstOrNull { it.name == prefs[UNITS] } ?: Units.METRIC,
    )

    private companion object {
        val SERVER = stringPreferencesKey("server")
        val TOKEN = stringPreferencesKey("token")
        val USER = stringPreferencesKey("user")
        val SERVER_NAME = stringPreferencesKey("server_name")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val WALLPAPER_COLORS = booleanPreferencesKey("wallpaper_colors")
        val BOLD_TEXT = booleanPreferencesKey("bold_text")
        val PALETTE = stringPreferencesKey("palette")
        val BACKGROUND_SYNC = booleanPreferencesKey("background_sync")
        val SYNC_WIFI_ONLY = booleanPreferencesKey("sync_wifi_only")
        val UNITS = stringPreferencesKey("units")
    }
}
