package com.vera.pinkvoice

import android.content.Context
import com.vera.pinkvoice.ui.ThemeMode

/**
 * 轻量偏好存储。
 *
 * 目前只有"外观模式"一项，用 SharedPreferences 就够，
 * 没必要为它引入 DataStore 依赖。
 */
object Prefs {
    private const val FILE = "pinkvoice_prefs"
    private const val KEY_THEME_MODE = "theme_mode"

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
}
