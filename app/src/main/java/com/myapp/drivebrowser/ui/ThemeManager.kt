package com.myapp.drivebrowser.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.myapp.drivebrowser.data.BrowserPreferences
import com.myapp.drivebrowser.model.AppThemeMode

/** Applies the persisted day/night theme mode globally. */
object ThemeManager {

    fun applyStoredTheme(context: Context) {
        applyThemeMode(BrowserPreferences.getThemeMode(context))
    }

    fun applyThemeMode(mode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
    }
}
