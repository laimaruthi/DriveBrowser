package com.myapp.drivebrowser.tabs

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import com.myapp.drivebrowser.data.BrowserPreferences
import com.myapp.drivebrowser.web.BrowserCallbacks
import com.myapp.drivebrowser.web.configureWebView
import com.myapp.drivebrowser.web.releaseCompletely
import com.myapp.drivebrowser.web.updateDesktopMode

/** Events the active tab raises to the host UI. */
data class TabCallbacks(
    val onActiveTabUpdated: (title: String, url: String, canBack: Boolean, canForward: Boolean) -> Unit = { _, _, _, _ -> },
    val onProgress: (Int) -> Unit = {},
    val onTabsChanged: () -> Unit = {},
    val onCleartextRequested: (Uri, () -> Unit, () -> Unit, () -> Unit) -> Unit = { _, _, _, c -> c() },
    val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    val onExitFullscreen: () -> Unit = {},
    val onMicRequest: (PermissionRequest) -> Unit = { it.deny() },
    val onGeolocationRequest: (String?, GeolocationPermissions.Callback?) -> Unit = { _, c -> c?.invoke(null, false, false) },
    val onDownload: (Uri) -> Unit = {}
)

class TabManager(
    private val activity: Activity,
    private val container: FrameLayout,
    private val callbacks: TabCallbacks
) {
    private val tabs = mutableListOf<BrowserTab>()
    private var activeId: Long = -1
    private var nextId: Long = 1
    private var desktopMode: Boolean = BrowserPreferences.isDesktopDefault(activity)

    val tabList: List<BrowserTab> get() = tabs
    val count: Int get() = tabs.size
    val activeTab: BrowserTab? get() = tabs.firstOrNull { it.id == activeId }
    val isDesktopMode: Boolean get() = desktopMode

    @SuppressLint("SetJavaScriptEnabled")
    fun createTab(url: String?, activate: Boolean): BrowserTab {
        val webView = WebView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        val tab = BrowserTab(nextId++, webView, "", url.orEmpty())

        configureWebView(
            webView = webView,
            callbacks = buildCallbacks(tab),
            useDesktopMode = desktopMode,
            userAgentProfile = BrowserPreferences.getUserAgentProfile(activity),
            allowDarkPages = BrowserPreferences.isDarkPagesEnabled(activity)
        )

        container.addView(webView)
        tabs.add(tab)

        if (!url.isNullOrBlank()) webView.loadUrl(url)
        if (activate) switchTo(tab.id) else { persistSession(); callbacks.onTabsChanged() }
        return tab
    }

    private fun buildCallbacks(tab: BrowserTab) = BrowserCallbacks(
        onUrlChange = { url ->
            tab.currentUrl = url
            if (tab.id == activeId) emitActive()
            persistSession()
        },
        onTitleChange = { title ->
            tab.title = title.orEmpty()
            if (tab.id == activeId) emitActive()
            callbacks.onTabsChanged()
        },
        onProgressChange = { if (tab.id == activeId) callbacks.onProgress(it) },
        onShowDownloadPrompt = { if (tab.id == activeId) callbacks.onDownload(it) },
        onCleartextNavigationRequested = { uri, once, host, cancel ->
            if (tab.id == activeId) callbacks.onCleartextRequested(uri, once, host, cancel) else cancel()
        },
        onEnterFullscreen = { v, cb -> callbacks.onEnterFullscreen(v, cb) },
        onExitFullscreen = { callbacks.onExitFullscreen() },
        onPermissionRequest = { callbacks.onMicRequest(it) },
        onGeolocationPermissionRequest = { origin, cb -> callbacks.onGeolocationRequest(origin, cb) }
    )

    private fun emitActive() {
        val tab = activeTab ?: return
        callbacks.onActiveTabUpdated(tab.title, tab.currentUrl, tab.webView.canGoBack(), tab.webView.canGoForward())
    }

    fun switchTo(tabId: Long) {
        val selected = tabs.firstOrNull { it.id == tabId } ?: return
        if (activeId != tabId) activeTab?.webView?.onPause()
        activeId = tabId
        tabs.forEach { it.webView.visibility = if (it.id == tabId) View.VISIBLE else View.GONE }
        selected.webView.onResume()
        emitActive()
        callbacks.onTabsChanged()
        persistSession()
    }

    fun closeTab(tabId: Long) {
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return
        val tab = tabs.removeAt(index)
        tab.webView.releaseCompletely()
        if (tab.id == activeId) {
            val next = tabs.getOrNull(index) ?: tabs.getOrNull(index - 1)
            if (next != null) switchTo(next.id) else { activeId = -1; callbacks.onTabsChanged() }
        } else {
            callbacks.onTabsChanged()
        }
        persistSession()
    }

    fun loadUrl(url: String) {
        val tab = activeTab ?: createTab(null, activate = true)
        tab.webView.loadUrl(url)
    }

    fun goBack(): Boolean {
        val wv = activeTab?.webView ?: return false
        return if (wv.canGoBack()) { wv.goBack(); true } else false
    }

    fun goForward() { activeTab?.webView?.takeIf { it.canGoForward() }?.goForward() }
    fun reload() { activeTab?.webView?.reload() }

    fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        val profile = BrowserPreferences.getUserAgentProfile(activity)
        activeTab?.webView?.updateDesktopMode(enabled, profile)
    }

    fun persistSession() {
        if (!BrowserPreferences.isRestoreTabs(activity)) return
        BrowserPreferences.saveTabSession(activity, tabs.map { it.currentUrl }.filter { it.isNotBlank() })
    }

    fun restoreSessionOrHome() {
        val home = BrowserPreferences.getHomePageUrl(activity)
        val session = if (home == null && BrowserPreferences.isRestoreTabs(activity))
            BrowserPreferences.loadTabSession(activity) else emptyList()
        when {
            home != null -> createTab(home, activate = true)
            session.isNotEmpty() -> {
                session.forEachIndexed { i, url -> createTab(url, activate = i == 0) }
            }
            else -> createTab(null, activate = true) // blank -> start page
        }
    }

    fun pauseActive() { activeTab?.webView?.onPause() }
    fun resumeActive() { activeTab?.webView?.onResume() }

    fun destroyAll() {
        tabs.forEach { it.webView.releaseCompletely() }
        tabs.clear()
    }
}
