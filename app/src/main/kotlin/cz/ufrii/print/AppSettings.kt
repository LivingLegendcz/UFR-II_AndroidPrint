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
    private const val KEY_SOURCE = "default_source"
    private const val KEY_STATUS_NOTIFY = "status_notify"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_SCAN_DEST = "scan_dest"
    private const val KEY_SCAN_FORMAT = "scan_format"
    private const val KEY_SCAN_AREA = "scan_area"

    private val _appearance = MutableStateFlow(Appearance.SYSTEM)
    val appearance: StateFlow<Appearance> = _appearance

    private val _defaultMode = MutableStateFlow(ColorMode.COLOR)
    val defaultMode: StateFlow<ColorMode> = _defaultMode

    private val _defaultSource = MutableStateFlow(PaperSource.AUTO)
    val defaultSource: StateFlow<PaperSource> = _defaultSource

    /** Periodic printer-status check with notifications (StatusWorker). */
    private val _statusNotify = MutableStateFlow(false)
    val statusNotify: StateFlow<Boolean> = _statusNotify

    private val _onboarded = MutableStateFlow(true)
    val onboarded: StateFlow<Boolean> = _onboarded

    private val _scanDest = MutableStateFlow(ScanDest.DOWNLOADS)
    val scanDest: StateFlow<ScanDest> = _scanDest
    private val _scanFormat = MutableStateFlow(ScanFormat.PDF)
    val scanFormat: StateFlow<ScanFormat> = _scanFormat
    /** Scan area used without a preview (Settings → Sken). */
    private val _scanArea = MutableStateFlow(AreaPreset())
    val scanArea: StateFlow<AreaPreset> = _scanArea

    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        _appearance.value = runCatching { Appearance.valueOf(p.getString(KEY_APPEARANCE, null)!!) }
            .getOrDefault(Appearance.SYSTEM)
        _defaultMode.value = runCatching { ColorMode.valueOf(p.getString(KEY_MODE, null)!!) }
            .getOrDefault(ColorMode.COLOR)
        _defaultSource.value = PaperSource.fromPpd(p.getString(KEY_SOURCE, null))
        _statusNotify.value = p.getBoolean(KEY_STATUS_NOTIFY, false)
        _onboarded.value = p.getBoolean(KEY_ONBOARDED, false)
        _scanDest.value = runCatching { ScanDest.valueOf(p.getString(KEY_SCAN_DEST, null)!!) }.getOrDefault(ScanDest.DOWNLOADS)
        _scanFormat.value = runCatching { ScanFormat.valueOf(p.getString(KEY_SCAN_FORMAT, null)!!) }.getOrDefault(ScanFormat.PDF)
        _scanArea.value = AreaPreset.decode(p.getString(KEY_SCAN_AREA, null))
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

    /** Paper source used when the system print dialog's "More options" is not opened. */
    fun setDefaultSource(ctx: Context, s: PaperSource) {
        _defaultSource.value = s
        prefs(ctx).edit().putString(KEY_SOURCE, s.ppd).apply()
    }

    fun setStatusNotify(ctx: Context, on: Boolean) {
        _statusNotify.value = on
        prefs(ctx).edit().putBoolean(KEY_STATUS_NOTIFY, on).apply()
    }

    fun setOnboarded(ctx: Context) {
        _onboarded.value = true
        prefs(ctx).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** Where a finished scan goes and in which format (remembered from the last scan). */
    fun setScanOutput(ctx: Context, dest: ScanDest, format: ScanFormat) {
        _scanDest.value = dest
        _scanFormat.value = format
        prefs(ctx).edit().putString(KEY_SCAN_DEST, dest.name).putString(KEY_SCAN_FORMAT, format.name).apply()
    }

    fun setScanArea(ctx: Context, a: AreaPreset) {
        _scanArea.value = a
        prefs(ctx).edit().putString(KEY_SCAN_AREA, a.encode()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
