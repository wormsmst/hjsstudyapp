package com.example.adminmemo

import android.content.Context

/** 다크 테마 / 글자 크기 등 앱 전역 설정 저장소 */
object AppPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_THEME = "theme_mode"       // "system" | "light" | "dark"
    private const val KEY_FONT_SCALE = "font_scale"  // Float, 기본 1.0f
    private const val KEY_WIDGET_COLOR = "widget_bg_color"
    private const val KEY_WIDGET_OPACITY = "widget_opacity"   // 0~100
    private const val KEY_WIDGET_FONT_SCALE = "widget_font_scale"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    private const val KEY_LOCAL_SYNC_AT = "local_sync_at"
    private const val KEY_CLOUD_SYNC_AT = "cloud_sync_at"
    private const val KEY_EXAM_DATE = "exam_date_millis"
    private const val KEY_EXAM_TITLE = "exam_date_title"
    private const val KEY_EXAM1_DATE = "exam1_date_millis"
    private const val KEY_EXAM2_DATE = "exam2_date_millis"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME, "system") ?: "system"

    fun setThemeMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME, mode).apply()
    }

    fun getFontScale(context: Context): Float =
        prefs(context).getFloat(KEY_FONT_SCALE, 1.0f)

    fun setFontScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SCALE, scale).apply()
    }

    /** 위젯 배경색 (ARGB 중 RGB만 사용, 투명도는 별도 저장) 기본은 흰색 */
    fun getWidgetColor(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_COLOR, 0xFFFFFF)

    fun setWidgetColor(context: Context, colorRgb: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_COLOR, colorRgb).apply()
    }

    fun getWidgetOpacity(context: Context): Int =
        prefs(context).getInt(KEY_WIDGET_OPACITY, 100)

    fun setWidgetOpacity(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_OPACITY, percent.coerceIn(10, 100)).apply()
    }

    fun getWidgetFontScale(context: Context): Float =
        prefs(context).getFloat(KEY_WIDGET_FONT_SCALE, 1.0f)

    fun setWidgetFontScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_WIDGET_FONT_SCALE, scale).apply()
    }

    fun getGeminiApiKey(context: Context): String =
        prefs(context).getString(KEY_GEMINI_API_KEY, "") ?: ""

    fun setGeminiApiKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, key).apply()
    }

    fun getLocalSyncTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LOCAL_SYNC_AT, 0L)

    fun setLocalSyncTimestamp(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_LOCAL_SYNC_AT, millis).apply()
    }

    fun getCloudSyncTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_CLOUD_SYNC_AT, 0L)

    fun setCloudSyncTimestamp(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_CLOUD_SYNC_AT, millis).apply()
    }

    /** 예전 단일 시험일. 1차가 비어 있으면 여기 값을 1차로 옮긴다. */
    fun getExamDateMillis(context: Context): Long = getExam1DateMillis(context)

    fun setExamDateMillis(context: Context, millis: Long) {
        setExam1DateMillis(context, millis)
    }

    fun getExam1DateMillis(context: Context): Long {
        val p = prefs(context)
        val v = p.getLong(KEY_EXAM1_DATE, 0L)
        if (v > 0L) return v
        val legacy = p.getLong(KEY_EXAM_DATE, 0L)
        if (legacy > 0L) {
            p.edit().putLong(KEY_EXAM1_DATE, legacy).apply()
            return legacy
        }
        return 0L
    }

    fun setExam1DateMillis(context: Context, millis: Long) {
        prefs(context).edit()
            .putLong(KEY_EXAM1_DATE, millis)
            .putLong(KEY_EXAM_DATE, millis)
            .apply()
    }

    fun getExam2DateMillis(context: Context): Long =
        prefs(context).getLong(KEY_EXAM2_DATE, 0L)

    fun setExam2DateMillis(context: Context, millis: Long) {
        prefs(context).edit().putLong(KEY_EXAM2_DATE, millis).apply()
    }

    fun getExamTitle(context: Context): String =
        prefs(context).getString(KEY_EXAM_TITLE, "시험일") ?: "시험일"

    fun setExamTitle(context: Context, title: String) {
        val t = title.trim().ifBlank { "시험일" }
        prefs(context).edit().putString(KEY_EXAM_TITLE, t).apply()
    }

    private const val KEY_MEMORY_RESET_ONE = "memory_reset_all_to_one"

    fun applyMemoryResetToOneIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_MEMORY_RESET_ONE, false)) return
        CardStore.resetAllMemoryLevels(context, 1)
        p.edit().putBoolean(KEY_MEMORY_RESET_ONE, true).apply()
    }

    private const val KEY_PURGE_CASE_STUDY = "purge_case_study_cards"

    fun applyCaseStudyPurgeIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(KEY_PURGE_CASE_STUDY, false)) return
        CardStore.purgeCaseStudyCards(context)
        p.edit().putBoolean(KEY_PURGE_CASE_STUDY, true).apply()
    }

    private const val KEY_TTS_VOICE = "tts_voice_name"
    private const val KEY_TTS_RATE = "tts_speech_rate"

    fun getTtsVoiceName(context: Context): String =
        prefs(context).getString(KEY_TTS_VOICE, "") ?: ""

    fun setTtsVoiceName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_TTS_VOICE, name).apply()
    }

    fun getTtsRate(context: Context): Float =
        prefs(context).getFloat(KEY_TTS_RATE, 0.92f)

    fun setTtsRate(context: Context, rate: Float) {
        prefs(context).edit().putFloat(KEY_TTS_RATE, rate.coerceIn(0.7f, 1.3f)).apply()
    }

    private const val KEY_TTS_REPEAT = "tts_repeat_count"

    fun getTtsRepeat(context: Context): Int =
        prefs(context).getInt(KEY_TTS_REPEAT, 1).coerceIn(1, 10)

    fun setTtsRepeat(context: Context, count: Int) {
        prefs(context).edit().putInt(KEY_TTS_REPEAT, count.coerceIn(1, 10)).apply()
    }

    private const val KEY_TTS_THINK = "tts_think_seconds"
    private const val KEY_TTS_CUE = "tts_cue_sound"
    private const val KEY_TODAY_TTS_COUNT = "today_tts_count"
    private const val KEY_TODAY_TTS_REPEAT = "today_tts_repeat"

    fun getTtsThinkSeconds(context: Context): Int =
        prefs(context).getInt(KEY_TTS_THINK, 5).coerceIn(3, 12)

    fun setTtsThinkSeconds(context: Context, sec: Int) {
        prefs(context).edit().putInt(KEY_TTS_THINK, sec.coerceIn(3, 12)).apply()
    }

    fun getTtsCueEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS_CUE, true)

    fun setTtsCueEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TTS_CUE, on).apply()
    }

    fun getTodayTtsCount(context: Context): Int =
        prefs(context).getInt(KEY_TODAY_TTS_COUNT, 5).coerceIn(3, 12)

    fun setTodayTtsCount(context: Context, n: Int) {
        prefs(context).edit().putInt(KEY_TODAY_TTS_COUNT, n.coerceIn(3, 12)).apply()
    }

    fun getTodayTtsRepeat(context: Context): Int =
        prefs(context).getInt(KEY_TODAY_TTS_REPEAT, 4).coerceIn(1, 10)

    fun setTodayTtsRepeat(context: Context, n: Int) {
        prefs(context).edit().putInt(KEY_TODAY_TTS_REPEAT, n.coerceIn(1, 10)).apply()
    }

    fun markQuizOpened(context: Context) {
        prefs(context).edit().putBoolean("quiz_opened", true).apply()
    }

    fun showQuizTile(context: Context): Boolean {
        if (prefs(context).getBoolean("quiz_opened", false)) return true
        return !DailyQuestStore.everRecallDone(context)
    }
}
