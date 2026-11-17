package com.vera.pinkvoice

import android.content.Context
import com.vera.pinkvoice.ui.ThemeMode

/**
 * 轻量偏好存储。
 *
 * 目前只有"外观模式"和"回放窗口"两项，用 SharedPreferences 就够，
 * 没必要为它引入 DataStore 依赖。
 */
object Prefs {
    private const val FILE = "pinkvoice_prefs"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_REPLAY_WINDOW = "replay_window_seconds"

    fun themeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, null))
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.storageKey)
            .apply()
    }

    /**
     * 回放窗口长度（秒）。
     *
     * 用户明确选过的选项，不该每次开 App 都退回默认值。
     *
     * @param fallback 存的值不合法时用这个。
     */
    fun replayWindowSeconds(context: Context, fallback: Int): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val v = prefs.getInt(KEY_REPLAY_WINDOW, fallback)
        // 存的值可能被旧版本写坏过，越界就退回默认
        return if (v in 1..300) v else fallback
    }

    fun setReplayWindowSeconds(context: Context, seconds: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_REPLAY_WINDOW, seconds)
            .apply()
    }
}
