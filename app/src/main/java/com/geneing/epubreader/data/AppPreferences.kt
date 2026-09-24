package com.geneing.epubreader.data

import android.content.Context

enum class ThemeMode(val storageKey: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromStorageKey(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: SYSTEM
    }
}

enum class ReaderFontFamily(val storageKey: String, val label: String) {
    BOOK_DEFAULT("book", "Book default"),
    SERIF("serif", "Serif"),
    SANS_SERIF("sans-serif", "Sans serif");

    companion object {
        fun fromStorageKey(value: String?): ReaderFontFamily =
            entries.firstOrNull { it.storageKey == value } ?: BOOK_DEFAULT
    }
}

enum class SpeechEngine(val storageKey: String, val label: String) {
    ANDROID_SYSTEM("android-system", "Android System TTS"),
    POCKET("pocket", "Pocket TTS");

    companion object {
        fun fromStorageKey(value: String?): SpeechEngine =
            entries.firstOrNull { it.storageKey == value } ?: ANDROID_SYSTEM
    }
}

object AppPreferences {
    private const val PREFERENCES_FILE = "app_preferences"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_READER_FONT = "reader_font"
    private const val KEY_READER_FONT_SCALE = "reader_font_scale"
    private const val KEY_SPEECH_ENGINE = "speech_engine"
    private const val KEY_SPEECH_RATE = "speech_rate"
    private const val KEY_PLAYBACK_GRACE_MINUTES = "playback_grace_minutes"
    private const val KEY_RESUME_ON_BLUETOOTH_RECONNECT = "resume_on_bluetooth_reconnect"
    private const val KEY_RESUME_AFTER_LONG_INTERRUPTION = "resume_after_long_interruption"

    fun themeMode(context: Context): ThemeMode = ThemeMode.fromStorageKey(
        context.applicationContext
            .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME_MODE, ThemeMode.SYSTEM.storageKey),
    )

    fun setThemeMode(context: Context, themeMode: ThemeMode) {
        preferences(context).edit().putString(KEY_THEME_MODE, themeMode.storageKey).apply()
    }

    fun readerFontFamily(context: Context): ReaderFontFamily = ReaderFontFamily.fromStorageKey(
        preferences(context).getString(KEY_READER_FONT, ReaderFontFamily.BOOK_DEFAULT.storageKey),
    )

    fun setReaderFontFamily(context: Context, family: ReaderFontFamily) {
        preferences(context).edit().putString(KEY_READER_FONT, family.storageKey).apply()
    }

    fun readerFontScale(context: Context): Float =
        preferences(context).getFloat(KEY_READER_FONT_SCALE, DEFAULT_FONT_SCALE)

    fun setReaderFontScale(context: Context, scale: Float) {
        preferences(context).edit().putFloat(KEY_READER_FONT_SCALE, scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE)).apply()
    }

    fun speechEngine(context: Context): SpeechEngine = SpeechEngine.fromStorageKey(
        preferences(context).getString(KEY_SPEECH_ENGINE, SpeechEngine.ANDROID_SYSTEM.storageKey),
    )

    fun setSpeechEngine(context: Context, engine: SpeechEngine) {
        // Pocket TTS remains visible in Settings but is not selectable before its engine is installed.
        if (engine == SpeechEngine.ANDROID_SYSTEM) {
            preferences(context).edit().putString(KEY_SPEECH_ENGINE, engine.storageKey).apply()
        }
    }

    fun speechRate(context: Context): Float = preferences(context).getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)

    fun setSpeechRate(context: Context, rate: Float) {
        preferences(context).edit().putFloat(KEY_SPEECH_RATE, rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)).apply()
    }

    fun playbackGraceMinutes(context: Context): Int = preferences(context)
        .getInt(KEY_PLAYBACK_GRACE_MINUTES, DEFAULT_PLAYBACK_GRACE_MINUTES)
        .coerceIn(MIN_PLAYBACK_GRACE_MINUTES, MAX_PLAYBACK_GRACE_MINUTES)

    fun setPlaybackGraceMinutes(context: Context, minutes: Int) {
        preferences(context).edit()
            .putInt(KEY_PLAYBACK_GRACE_MINUTES, minutes.coerceIn(MIN_PLAYBACK_GRACE_MINUTES, MAX_PLAYBACK_GRACE_MINUTES))
            .apply()
    }

    fun resumeOnBluetoothReconnect(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RESUME_ON_BLUETOOTH_RECONNECT, false)

    fun setResumeOnBluetoothReconnect(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_RESUME_ON_BLUETOOTH_RECONNECT, enabled).apply()
    }

    fun resumeAfterLongInterruption(context: Context): Boolean =
        preferences(context).getBoolean(KEY_RESUME_AFTER_LONG_INTERRUPTION, false)

    fun setResumeAfterLongInterruption(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_RESUME_AFTER_LONG_INTERRUPTION, enabled).apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    const val DEFAULT_FONT_SCALE = 1.0f
    const val MIN_FONT_SCALE = 0.8f
    const val MAX_FONT_SCALE = 1.6f
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val MIN_SPEECH_RATE = 0.5f
    const val MAX_SPEECH_RATE = 2.0f
    const val DEFAULT_PLAYBACK_GRACE_MINUTES = 5
    const val MIN_PLAYBACK_GRACE_MINUTES = 0
    const val MAX_PLAYBACK_GRACE_MINUTES = 10
}
