package com.myapp.drivebrowser.model

/** What the floating quick-action button does when tapped. */
enum class QuickActionButtonMode(val storageKey: String) {
    MENU("menu"),
    URL_BAR("url_bar");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.storageKey == key } ?: MENU
    }
}
