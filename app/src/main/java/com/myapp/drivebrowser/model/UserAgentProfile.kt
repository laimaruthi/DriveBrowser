package com.myapp.drivebrowser.model

/** Identity the WebView presents to sites, in mobile or desktop form. */
enum class UserAgentProfile(val storageKey: String) {
    ANDROID_CHROME("chrome"),
    SAFARI("safari");

    companion object {
        fun fromKey(key: String?): UserAgentProfile =
            entries.firstOrNull { it.storageKey == key } ?: ANDROID_CHROME
    }
}
