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
    private const val KEY_DDAY_DATE = "dday_target_date"
    private const val KEY_LOCAL_SYNC_TIME = "local_sync_timestamp"

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

    fun getDDayDate(context: Context): String =
        prefs(context).getString(KEY_DDAY_DATE, "2025-08-30") ?: "2025-08-30"

    fun setDDayDate(context: Context, dateStr: String) {
        prefs(context).edit().putString(KEY_DDAY_DATE, dateStr).apply()
    }

    fun getLocalSyncTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LOCAL_SYNC_TIME, 0L)

    fun setLocalSyncTimestamp(context: Context, timeMs: Long) {
        prefs(context).edit().putLong(KEY_LOCAL_SYNC_TIME, timeMs).apply()
    }
}
