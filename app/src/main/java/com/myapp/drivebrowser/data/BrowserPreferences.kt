package com.myapp.drivebrowser.data

import android.content.Context
import android.net.Uri
import com.myapp.drivebrowser.model.AppThemeMode
import com.myapp.drivebrowser.model.UserAgentProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single source of truth for persisted settings, bookmarks, per-site permissions and
 * the saved tab session. Backed by one SharedPreferences file.
 */
object BrowserPreferences {

    private const val PREFS_NAME = "drive_browser_prefs"

    private const val KEY_HOME_PAGE = "home_page"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_UA_PROFILE = "ua_profile"
    private const val KEY_DARK_PAGES = "dark_pages"
    private const val KEY_PERSISTENT_URL = "persistent_url"
    private const val KEY_RESTORE_TABS = "restore_tabs"
    private const val KEY_DESKTOP_DEFAULT = "desktop_default"
    private const val KEY_AD_BLOCK = "ad_block_enabled"
    private const val KEY_GLOBAL_SCALE = "global_scale_percent"
    private const val KEY_RESUME_LAST_PAGE = "resume_last_page"
    private const val KEY_LAST_PAGE = "last_page_url"
    private const val KEY_FAB_ENABLED = "fab_enabled"
    private const val KEY_FAB_MODE = "fab_mode"
    private const val KEY_FAB_POSITION = "fab_position"
    private const val KEY_QUICK_LINKS = "quick_links"
    private const val KEY_START_BACKGROUND = "start_background_uri"

    const val QUICK_LINK_SLOTS = 6
    private const val KEY_BOOKMARKS = "bookmarks"
    private const val KEY_TAB_SESSION = "tab_session"
    private const val KEY_ALLOWED_CLEAR_HOSTS = "allowed_cleartext_hosts"
    private const val KEY_ALLOWED_LOCATION_HOSTS = "allowed_location_hosts"
    private const val KEY_ALLOWED_MIC_HOSTS = "allowed_mic_hosts"

    private const val DEFAULT_URL = "https://www.google.com/"
    private const val SEARCH_TEMPLATE = "https://www.google.com/search?q=%s"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Simple scalar settings ----

    fun getHomePageUrl(context: Context): String? =
        prefs(context).getString(KEY_HOME_PAGE, null)?.takeIf { it.isNotBlank() }

    fun setHomePageUrl(context: Context, url: String?) {
        prefs(context).edit().putString(KEY_HOME_PAGE, url?.trim().orEmpty()).apply()
    }

    fun getThemeMode(context: Context): AppThemeMode =
        AppThemeMode.fromKey(prefs(context).getString(KEY_THEME, null))

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.storageKey).apply()
    }

    fun getUserAgentProfile(context: Context): UserAgentProfile =
        UserAgentProfile.fromKey(prefs(context).getString(KEY_UA_PROFILE, null))

    fun setUserAgentProfile(context: Context, profile: UserAgentProfile) {
        prefs(context).edit().putString(KEY_UA_PROFILE, profile.storageKey).apply()
    }

    fun isDarkPagesEnabled(context: Context) = prefs(context).getBoolean(KEY_DARK_PAGES, false)
    fun setDarkPagesEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_DARK_PAGES, v).apply()

    fun isPersistentUrlBar(context: Context) = prefs(context).getBoolean(KEY_PERSISTENT_URL, true)
    fun setPersistentUrlBar(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_PERSISTENT_URL, v).apply()

    fun isRestoreTabs(context: Context) = prefs(context).getBoolean(KEY_RESTORE_TABS, true)
    fun setRestoreTabs(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_RESTORE_TABS, v).apply()

    fun isDesktopDefault(context: Context) = prefs(context).getBoolean(KEY_DESKTOP_DEFAULT, false)
    fun setDesktopDefault(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_DESKTOP_DEFAULT, v).apply()

    fun isAdBlockEnabled(context: Context) = prefs(context).getBoolean(KEY_AD_BLOCK, true)
    fun setAdBlockEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_AD_BLOCK, v).apply()

    /** Page/UI zoom percentage (50–200). */
    fun getGlobalScalePercent(context: Context) =
        prefs(context).getInt(KEY_GLOBAL_SCALE, 100).coerceIn(50, 200)
    fun setGlobalScalePercent(context: Context, percent: Int) =
        prefs(context).edit().putInt(KEY_GLOBAL_SCALE, percent.coerceIn(50, 200)).apply()

    fun isResumeLastPage(context: Context) = prefs(context).getBoolean(KEY_RESUME_LAST_PAGE, false)
    fun setResumeLastPage(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_RESUME_LAST_PAGE, v).apply()

    fun getLastPageUrl(context: Context): String? =
        prefs(context).getString(KEY_LAST_PAGE, null)?.takeIf { it.isNotBlank() }
    fun setLastPageUrl(context: Context, url: String?) =
        prefs(context).edit().putString(KEY_LAST_PAGE, url?.takeIf { it.isNotBlank() }.orEmpty()).apply()

    // ---- Floating quick-action button ----

    fun isFabEnabled(context: Context) = prefs(context).getBoolean(KEY_FAB_ENABLED, true)
    fun setFabEnabled(context: Context, v: Boolean) =
        prefs(context).edit().putBoolean(KEY_FAB_ENABLED, v).apply()

    fun getFabMode(context: Context) =
        com.myapp.drivebrowser.model.QuickActionButtonMode.fromKey(prefs(context).getString(KEY_FAB_MODE, null))
    fun setFabMode(context: Context, mode: com.myapp.drivebrowser.model.QuickActionButtonMode) =
        prefs(context).edit().putString(KEY_FAB_MODE, mode.storageKey).apply()

    fun getFabPosition(context: Context) =
        com.myapp.drivebrowser.model.QuickActionButtonPosition.fromKey(prefs(context).getString(KEY_FAB_POSITION, null))
    fun setFabPosition(context: Context, position: com.myapp.drivebrowser.model.QuickActionButtonPosition) =
        prefs(context).edit().putString(KEY_FAB_POSITION, position.storageKey).apply()

    // ---- Start-page quick links (exactly QUICK_LINK_SLOTS, empty url = empty slot) ----

    fun getQuickLinks(context: Context): List<Bookmark> {
        val raw = prefs(context).getString(KEY_QUICK_LINKS, null)
        val parsed = raw?.let {
            runCatching {
                val arr = JSONArray(it)
                buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i)
                        add(Bookmark(o?.optString("title").orEmpty(), o?.optString("url").orEmpty()))
                    }
                }
            }.getOrNull()
        } ?: defaultQuickLinks()
        return (parsed + List(QUICK_LINK_SLOTS) { Bookmark("", "") }).take(QUICK_LINK_SLOTS)
    }

    fun setQuickLink(context: Context, index: Int, title: String, url: String) {
        if (index !in 0 until QUICK_LINK_SLOTS) return
        val list = getQuickLinks(context).toMutableList()
        list[index] = Bookmark(title.trim(), url.trim())
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().apply { put("title", it.title); put("url", it.url) }) }
        prefs(context).edit().putString(KEY_QUICK_LINKS, arr.toString()).apply()
    }

    private fun defaultQuickLinks() = listOf(
        Bookmark("Google", "https://www.google.com/"),
        Bookmark("YouTube", "https://m.youtube.com/"),
        Bookmark("Maps", "https://maps.google.com/"),
        Bookmark("Wikipedia", "https://www.wikipedia.org/"),
        Bookmark("News", "https://news.google.com/"),
        Bookmark("Weather", "https://weather.com/")
    )

    // ---- Start-page background ----

    fun getStartBackgroundUri(context: Context): String? =
        prefs(context).getString(KEY_START_BACKGROUND, null)?.takeIf { it.isNotBlank() }
    fun setStartBackgroundUri(context: Context, uri: String?) =
        prefs(context).edit().putString(KEY_START_BACKGROUND, uri?.takeIf { it.isNotBlank() }.orEmpty()).apply()

    // ---- URL helpers ----

    fun defaultUrl(): String = DEFAULT_URL

    fun toSearchUrl(query: String): String = SEARCH_TEMPLATE.format(Uri.encode(query))

    /** Turns raw address-bar text into a loadable URL, or a search query URL. */
    fun normalizeToUrlOrSearch(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return DEFAULT_URL
        val looksLikeUrl = text.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) ||
            (text.contains('.') && !text.contains(' '))
        if (!looksLikeUrl) return toSearchUrl(text)
        return if (text.contains("://")) text else "https://$text"
    }

    // ---- Bookmarks ----

    fun getBookmarks(context: Context): List<Bookmark> {
        val raw = prefs(context).getString(KEY_BOOKMARKS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val url = o.optString("url")
                    if (url.isNotBlank()) add(Bookmark(o.optString("title"), url))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun addBookmark(context: Context, title: String, url: String) {
        if (url.isBlank()) return
        val current = getBookmarks(context).toMutableList()
        if (current.any { it.url == url }) return
        current.add(Bookmark(title.ifBlank { url }, url))
        saveBookmarks(context, current)
    }

    fun removeBookmark(context: Context, url: String) {
        saveBookmarks(context, getBookmarks(context).filterNot { it.url == url })
    }

    fun isBookmarked(context: Context, url: String?): Boolean =
        url != null && getBookmarks(context).any { it.url == url }

    private fun saveBookmarks(context: Context, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(org.json.JSONObject().apply {
                put("title", b.title)
                put("url", b.url)
            })
        }
        prefs(context).edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    // ---- Tab session ----

    fun saveTabSession(context: Context, urls: List<String>) {
        val arr = JSONArray()
        urls.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_TAB_SESSION, arr.toString()).apply()
    }

    fun loadTabSession(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_TAB_SESSION, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    // ---- Per-site permission allow-lists (exact host match, no wildcards) ----

    fun isHostAllowedCleartext(context: Context, host: String?) =
        isHostAllowed(context, KEY_ALLOWED_CLEAR_HOSTS, host)

    fun addAllowedCleartextHost(context: Context, host: String) =
        addAllowedHost(context, KEY_ALLOWED_CLEAR_HOSTS, host)

    fun clearAllowedCleartextHosts(context: Context) =
        prefs(context).edit().remove(KEY_ALLOWED_CLEAR_HOSTS).apply()

    fun isHostAllowedLocation(context: Context, host: String?) =
        isHostAllowed(context, KEY_ALLOWED_LOCATION_HOSTS, host)

    fun addAllowedLocationHost(context: Context, host: String) =
        addAllowedHost(context, KEY_ALLOWED_LOCATION_HOSTS, host)

    fun isHostAllowedMic(context: Context, host: String?) =
        isHostAllowed(context, KEY_ALLOWED_MIC_HOSTS, host)

    fun addAllowedMicHost(context: Context, host: String) =
        addAllowedHost(context, KEY_ALLOWED_MIC_HOSTS, host)

    fun clearSitePermissions(context: Context) {
        prefs(context).edit()
            .remove(KEY_ALLOWED_LOCATION_HOSTS)
            .remove(KEY_ALLOWED_MIC_HOSTS)
            .apply()
    }

    private fun isHostAllowed(context: Context, key: String, host: String?): Boolean {
        val normalized = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return false
        val raw = prefs(context).getString(key, null) ?: return false
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).any { arr.optString(it).equals(normalized, ignoreCase = true) }
        }.getOrDefault(false)
    }

    private fun addAllowedHost(context: Context, key: String, host: String) {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return
        val list = runCatching {
            val arr = JSONArray(prefs(context).getString(key, null))
            buildList { for (i in 0 until arr.length()) add(arr.optString(i)) }.toMutableList()
        }.getOrDefault(mutableListOf())
        if (list.any { it.equals(normalized, ignoreCase = true) }) return
        list.add(normalized)
        val out = JSONArray().apply { list.forEach { put(it) } }
        prefs(context).edit().putString(key, out.toString()).apply()
    }

    data class Bookmark(val title: String, val url: String)
}
