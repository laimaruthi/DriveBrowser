package com.myapp.drivebrowser.model

import androidx.appcompat.app.AppCompatDelegate

/** User-selectable theme mode, persisted by its [storageKey]. */
enum class AppThemeMode(val storageKey: String, val nightMode: Int) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromKey(key: String?): AppThemeMode =
            entries.firstOrNull { it.storageKey == key } ?: SYSTEM
    }
}
