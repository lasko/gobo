package com.gobo.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-selectable color theme. */
enum class ThemeMode(val label: String) {
    SYSTEM("System default"),
    LIGHT("Light"),
    DARK("Dark"),
}

/**
 * Local-only app preferences. Plain SharedPreferences (not encrypted) — these
 * are non-sensitive UI choices and never leave the device.
 */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("gobo_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode = _themeMode.asStateFlow()

    /** When on, placing a stone takes two taps (preview, then confirm). Default off. */
    private val _confirmMoves = MutableStateFlow(prefs.getBoolean(KEY_CONFIRM_MOVES, false))
    val confirmMoves = _confirmMoves.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    fun setConfirmMoves(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONFIRM_MOVES, enabled).apply()
        _confirmMoves.value = enabled
    }

    private fun loadThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)

    private companion object {
        const val KEY_THEME = "theme_mode"
        const val KEY_CONFIRM_MOVES = "confirm_moves"
    }
}
