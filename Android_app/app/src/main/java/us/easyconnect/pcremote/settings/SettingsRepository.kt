package us.easyconnect.pcremote.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User-tunable touchpad behaviour. Defaults match the old hard-coded constants. */
data class Settings(
    /** Pointer speed multiplier applied to finger travel before it's sent. */
    val sensitivity: Float = DEFAULT_SENSITIVITY,
    /** Content follows the fingers (phone-style) instead of mouse-wheel direction. */
    val invertScroll: Boolean = false,
    /** Tapping with two fingers also right-clicks. Hold-to-right-click always works. */
    val twoFingerRightClick: Boolean = false,
) {
    companion object {
        const val DEFAULT_SENSITIVITY = 1.6f
        const val MIN_SENSITIVITY = 0.4f
        const val MAX_SENSITIVITY = 4.0f
    }
}

/**
 * Persists [Settings] in SharedPreferences and exposes them as a [StateFlow] so
 * Compose recomposes and the live gesture loop can read fresh values.
 *
 * Deliberately SharedPreferences rather than DataStore: three scalars don't
 * justify another dependency in a ~10 MB APK.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("pcremote_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    // Sanitising on *load* as well as on set is deliberate: it self-heals an
    // install that already persisted a bad value, without the user having to
    // clear app storage.
    private fun load() = Settings(
        sensitivity = sanitizeSensitivity(prefs.getFloat(KEY_SENSITIVITY, Settings.DEFAULT_SENSITIVITY)),
        invertScroll = prefs.getBoolean(KEY_INVERT_SCROLL, false),
        twoFingerRightClick = prefs.getBoolean(KEY_TWO_FINGER_RIGHT_CLICK, false),
    )

    fun setSensitivity(value: Float) {
        val clean = sanitizeSensitivity(value)
        prefs.edit().putFloat(KEY_SENSITIVITY, clean).apply()
        _settings.value = _settings.value.copy(sensitivity = clean)
    }

    /**
     * Guards the one setting that can silently kill the touchpad.
     *
     * `coerceIn` does NOT filter NaN — every comparison against NaN is false, so
     * `NaN.coerceIn(0.4f, 4f)` returns NaN, which then gets persisted. A NaN (or
     * zero) sensitivity makes every pointer delta round to 0, so the app stops
     * calling `move()` entirely: the cursor is dead while the UI still cheerfully
     * says "Connected", and the bad value survives restarts. Only wiping app
     * storage would clear it — a genuinely baffling failure.
     */
    private fun sanitizeSensitivity(value: Float): Float =
        if (!value.isFinite()) {
            Settings.DEFAULT_SENSITIVITY
        } else {
            value.coerceIn(Settings.MIN_SENSITIVITY, Settings.MAX_SENSITIVITY)
        }

    fun setInvertScroll(value: Boolean) {
        prefs.edit().putBoolean(KEY_INVERT_SCROLL, value).apply()
        _settings.value = _settings.value.copy(invertScroll = value)
    }

    fun setTwoFingerRightClick(value: Boolean) {
        prefs.edit().putBoolean(KEY_TWO_FINGER_RIGHT_CLICK, value).apply()
        _settings.value = _settings.value.copy(twoFingerRightClick = value)
    }

    /** Restores every setting to its default. */
    fun reset() {
        prefs.edit().clear().apply()
        _settings.value = Settings()
    }

    private companion object {
        const val KEY_SENSITIVITY = "sensitivity"
        const val KEY_INVERT_SCROLL = "invert_scroll"
        const val KEY_TWO_FINGER_RIGHT_CLICK = "two_finger_right_click"
    }
}
