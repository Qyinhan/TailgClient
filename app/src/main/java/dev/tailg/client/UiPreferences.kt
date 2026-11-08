package dev.tailg.client

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only non-sensitive presentation preferences. Do not store credentials or BLE identifiers here. */
enum class UiThemeMode(val label: String) {
    SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色")
}

data class UiPreferencesState(
    val theme: UiThemeMode = UiThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** Simple offline preferences: no network activity, no analytics, no background work. */
class UiPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("ui_preferences_v1", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(load())
    val state: StateFlow<UiPreferencesState> = mutableState.asStateFlow()

    fun setTheme(mode: UiThemeMode) {
        if (preferences.edit().putString("theme", mode.name).commit()) {
            mutableState.value = mutableState.value.copy(theme = mode)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        if (preferences.edit().putBoolean("dynamic_color", enabled).commit()) {
            mutableState.value = mutableState.value.copy(dynamicColor = enabled)
        }
    }

    private fun load(): UiPreferencesState = UiPreferencesState(
        theme = UiThemeMode.entries.firstOrNull { it.name == preferences.getString("theme", null) }
            ?: UiThemeMode.SYSTEM,
        dynamicColor = preferences.getBoolean("dynamic_color", true),
    )
}
