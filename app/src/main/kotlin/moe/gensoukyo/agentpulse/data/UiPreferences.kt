package moe.gensoukyo.agentpulse.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.uiPreferencesDataStore by preferencesDataStore(name = "ui_preferences")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ColorSource(val seedArgb: Int?) {
    DYNAMIC(null),
    INDIGO(0xFF4F63E7.toInt()),
    VIOLET(0xFF7657E8.toInt()),
    TEAL(0xFF168A78.toInt()),
    ORANGE(0xFFE46F24.toInt()),
    ROSE(0xFFD84F76.toInt()),
    CUSTOM(null),
}

data class UiPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ColorSource = ColorSource.DYNAMIC,
    val customSeedArgb: Int = ColorSource.INDIGO.seedArgb!!,
)

class UiPreferencesRepository(private val context: Context) {
    val preferences: Flow<UiPreferences> = context.uiPreferencesDataStore.data
        .map { values ->
            UiPreferences(
                themeMode = values[THEME_MODE].enumOrDefault(ThemeMode.SYSTEM),
                colorSource = values[COLOR_SOURCE].enumOrDefault(ColorSource.DYNAMIC),
                customSeedArgb = values[CUSTOM_SEED] ?: ColorSource.INDIGO.seedArgb!!,
            )
        }
        .catch { emit(UiPreferences()) }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.uiPreferencesDataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setColorSource(source: ColorSource) {
        context.uiPreferencesDataStore.edit { it[COLOR_SOURCE] = source.name }
    }

    suspend fun setCustomSeed(argb: Int) {
        context.uiPreferencesDataStore.edit {
            it[CUSTOM_SEED] = argb or 0xFF000000.toInt()
            it[COLOR_SOURCE] = ColorSource.CUSTOM.name
        }
    }

    private inline fun <reified T : Enum<T>> String?.enumOrDefault(default: T): T =
        enumValues<T>().firstOrNull { it.name == this@enumOrDefault } ?: default

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_SOURCE = stringPreferencesKey("color_source")
        val CUSTOM_SEED = intPreferencesKey("custom_seed_argb")
    }
}

internal fun parseHexColor(value: String): Int? {
    val normalized = value.trim().removePrefix("#")
    if (normalized.length != 6 || normalized.any { it.digitToIntOrNull(16) == null }) return null
    return normalized.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
}

internal fun formatHexColor(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
