package cz.ufrii.print

import android.content.Context
import cz.ufrii.print.ui.theme.Appearance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * User preferences of the app UI (SharedPreferences "ufr_settings"), exposed as
 * StateFlows so Compose screens recompose when they change.
 */
object AppSettings {

    private const val PREFS = "ufr_settings"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_MODE = "default_mode"

    private val _appearance = MutableStateFlow(Appearance.SYSTEM)
    val appearance: StateFlow<Appearance> = _appearance

    private val _defaultMode = MutableStateFlow(ColorMode.COLOR)
    val defaultMode: StateFlow<ColorMode> = _defaultMode

    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        _appearance.value = runCatching { Appearance.valueOf(p.getString(KEY_APPEARANCE, null)!!) }
            .getOrDefault(Appearance.SYSTEM)
        _defaultMode.value = runCatching { ColorMode.valueOf(p.getString(KEY_MODE, null)!!) }
            .getOrDefault(ColorMode.COLOR)
        loaded = true
    }

    fun setAppearance(ctx: Context, a: Appearance) {
        _appearance.value = a
        prefs(ctx).edit().putString(KEY_APPEARANCE, a.name).apply()
    }

    /** Spec §4: the colour/mono choice on Home is remembered as the default. */
    fun setDefaultMode(ctx: Context, m: ColorMode) {
        _defaultMode.value = m
        prefs(ctx).edit().putString(KEY_MODE, m.name).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
