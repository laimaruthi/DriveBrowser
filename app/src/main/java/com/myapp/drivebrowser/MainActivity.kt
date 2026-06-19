package com.myapp.drivebrowser

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myapp.drivebrowser.data.BrowserPreferences
import com.myapp.drivebrowser.databinding.ActivityMainBinding
import com.myapp.drivebrowser.permissions.PermissionManager
import com.myapp.drivebrowser.settings.SettingsActivity
import com.myapp.drivebrowser.tabs.TabCallbacks
import com.myapp.drivebrowser.tabs.TabManager
import com.myapp.drivebrowser.ui.ThemeManager
import com.myapp.drivebrowser.ui.adapters.BookmarkAdapter
import com.myapp.drivebrowser.ui.adapters.StartPageAdapter
import com.myapp.drivebrowser.ui.adapters.TabAdapter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var tabManager: TabManager

    private lateinit var tabAdapter: TabAdapter
    private lateinit var bookmarkAdapter: BookmarkAdapter
    private lateinit var startPageAdapter: StartPageAdapter

    // Fullscreen video
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // Pending permission state
    private var pendingMicRequest: PermissionRequest? = null
    private var pendingMicHost: String? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingSpeechResult: ((Boolean) -> Unit)? = null
    private var pendingSpeechHost: String? = null

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val request = pendingMicRequest; pendingMicRequest = null
            if (granted && request != null) {
                pendingMicHost?.let { BrowserPreferences.addAllowedMicHost(this, it) }
                request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            } else request?.deny()
        }

    private val speechMicPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val result = pendingSpeechResult; pendingSpeechResult = null
            if (granted) pendingSpeechHost?.let { BrowserPreferences.addAllowedMicHost(this, it) }
            pendingSpeechHost = null
            result?.invoke(granted)
        }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            finishGeoDecision(granted)
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyStoredTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tabManager = TabManager(this, binding.webViewContainer, buildTabCallbacks())

        setupAdapters()
        setupToolbar()
        setupAddressBar()
        setupMenu()
        setupBackHandling()

        tabManager.restoreSessionOrHome()
        refreshStartPage()
        updateContentVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionManager.hasNotifications(this)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- Tab callbacks ----

    private fun buildTabCallbacks() = TabCallbacks(
        onActiveTabUpdated = { _, url, canBack, canForward ->
            if (!binding.addressEdit.hasFocus()) {
                binding.addressEdit.setText(displayUrl(url))
            }
            binding.btnBack.isEnabled = canBack || tabManager.count > 1
            binding.btnForward.isEnabled = canForward
            updateContentVisibility()
        },
        onProgress = { p ->
            binding.progressBar.isVisible = p in 1..99
            binding.progressBar.progress = p
        },
        onTabsChanged = {
            binding.tabCount.text = tabManager.count.toString()
            if (binding.panelRecycler.isVisible && binding.panelTitle.text == getString(R.string.tabs)) {
                tabAdapter.submit(tabManager.tabList)
            }
        },
        onCleartextRequested = { uri, once, host, cancel -> showCleartextDialog(uri, once, host, cancel) },
        onEnterFullscreen = { view, cb -> enterFullscreen(view, cb) },
        onExitFullscreen = { exitFullscreen() },
        onMicRequest = { request -> handleMicRequest(request) },
        onSpeechMicRequest = { origin, result -> handleSpeechMicRequest(origin, result) },
        onGeolocationRequest = { origin, cb -> handleGeoRequest(origin, cb) },
        onDownload = { uri -> openExternally(uri) }
    )

    // ---- Setup ----

    private fun setupAdapters() {
        tabAdapter = TabAdapter(
            onOpen = { id -> tabManager.switchTo(id); hidePanel() },
            onClose = { id -> tabManager.closeTab(id); tabAdapter.submit(tabManager.tabList); updateContentVisibility() }
        )
        bookmarkAdapter = BookmarkAdapter(
            onOpen = { url -> loadUrl(url); hidePanel() },
            onDelete = { url -> BrowserPreferences.removeBookmark(this, url); bookmarkAdapter.submit(BrowserPreferences.getBookmarks(this)); refreshStartPage() }
        )
        startPageAdapter = StartPageAdapter(onOpen = { url -> loadUrl(url) })
        binding.startPageRecycler.layoutManager = GridLayoutManager(this, 3)
        binding.startPageRecycler.adapter = startPageAdapter
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener { if (!tabManager.goBack() && tabManager.count == 0) finish() }
        binding.btnForward.setOnClickListener { tabManager.goForward() }
        binding.btnReload.setOnClickListener { tabManager.reload() }
        binding.btnHome.setOnClickListener {
            val home = BrowserPreferences.getHomePageUrl(this)
            if (home != null) loadUrl(home) else showStartPage()
        }
        binding.btnTabs.setOnClickListener { showTabsPanel() }
        binding.btnMenu.setOnClickListener { showMenuPanel() }
        binding.btnClear.setOnClickListener { binding.addressEdit.setText("") }
        binding.scrim.setOnClickListener { hidePanel() }
        binding.btnPanelClose.setOnClickListener { hidePanel() }
    }

    private fun setupAddressBar() {
        binding.addressEdit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val text = binding.addressEdit.text?.toString().orEmpty()
                if (text.isNotBlank()) {
                    loadUrl(BrowserPreferences.normalizeToUrlOrSearch(text))
                    binding.addressEdit.clearFocus()
                }
                true
            } else false
        }
        binding.addressEdit.addTextChangedListener {
            binding.btnClear.isVisible = !it.isNullOrEmpty() && binding.addressEdit.hasFocus()
        }
        binding.addressEdit.setOnFocusChangeListener { _, hasFocus ->
            binding.btnClear.isVisible = hasFocus && !binding.addressEdit.text.isNullOrEmpty()
            if (!hasFocus) binding.addressEdit.setText(displayUrl(tabManager.activeTab?.currentUrl.orEmpty()))
        }
    }

    private fun setupMenu() {
        binding.menuNewTab.setOnClickListener { tabManager.createTab(BrowserPreferences.getHomePageUrl(this), true); hidePanel(); updateContentVisibility() }
        binding.menuBookmark.setOnClickListener { toggleBookmark(); hidePanel() }
        binding.menuBookmarks.setOnClickListener { showBookmarksPanel() }
        binding.menuDesktop.setOnClickListener {
            tabManager.setDesktopMode(!tabManager.isDesktopMode); hidePanel()
        }
        binding.menuSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java)); hidePanel()
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                customView != null -> exitFullscreen()
                binding.panel.isVisible -> hidePanel()
                tabManager.goBack() -> { /* navigated back */ }
                else -> finish()
            }
        }
    }

    // ---- Navigation ----

    private fun loadUrl(url: String) {
        tabManager.loadUrl(url)
        showWebView()
    }

    private fun displayUrl(url: String): String =
        if (url.isBlank() || url.startsWith("file:///android_asset/")) "" else url

    // ---- Start page / content visibility ----

    private fun updateContentVisibility() {
        val url = tabManager.activeTab?.currentUrl.orEmpty()
        if (url.isBlank()) showStartPage() else showWebView()
    }

    private fun showStartPage() {
        binding.startPageScroll.isVisible = true
        binding.webViewContainer.isVisible = false
    }

    private fun showWebView() {
        binding.startPageScroll.isVisible = false
        binding.webViewContainer.isVisible = true
    }

    private fun refreshStartPage() {
        val defaults = listOf(
            StartPageAdapter.Slot("Google", "https://www.google.com/"),
            StartPageAdapter.Slot("YouTube", "https://m.youtube.com/"),
            StartPageAdapter.Slot("Maps", "https://maps.google.com/"),
            StartPageAdapter.Slot("Wikipedia", "https://www.wikipedia.org/")
        )
        val bookmarks = BrowserPreferences.getBookmarks(this)
            .map { StartPageAdapter.Slot(it.title, it.url) }
        val seen = HashSet<String>()
        val combined = (defaults + bookmarks).filter { seen.add(it.url) }
        startPageAdapter.submit(combined)
    }

    // ---- Panel (menu / tabs / bookmarks) ----

    private fun showMenuPanel() {
        binding.panelTitle.text = getString(R.string.menu)
        binding.menuList.isVisible = true
        binding.panelRecycler.isVisible = false
        binding.menuBookmark.setText(
            if (BrowserPreferences.isBookmarked(this, tabManager.activeTab?.currentUrl))
                R.string.remove_bookmark else R.string.add_bookmark
        )
        openPanel()
    }

    private fun showTabsPanel() {
        binding.panelTitle.text = getString(R.string.tabs)
        binding.menuList.isVisible = false
        binding.panelRecycler.isVisible = true
        binding.panelRecycler.layoutManager = LinearLayoutManager(this)
        binding.panelRecycler.adapter = tabAdapter
        tabAdapter.submit(tabManager.tabList)
        openPanel()
    }

    private fun showBookmarksPanel() {
        binding.panelTitle.text = getString(R.string.bookmarks)
        binding.menuList.isVisible = false
        binding.panelRecycler.isVisible = true
        binding.panelRecycler.layoutManager = LinearLayoutManager(this)
        binding.panelRecycler.adapter = bookmarkAdapter
        bookmarkAdapter.submit(BrowserPreferences.getBookmarks(this))
        openPanel()
    }

    private fun openPanel() {
        binding.scrim.isVisible = true
        binding.panel.isVisible = true
    }

    private fun hidePanel() {
        binding.scrim.isVisible = false
        binding.panel.isVisible = false
    }

    private fun toggleBookmark() {
        val tab = tabManager.activeTab ?: return
        val url = tab.currentUrl
        if (url.isBlank()) return
        if (BrowserPreferences.isBookmarked(this, url)) {
            BrowserPreferences.removeBookmark(this, url)
        } else {
            BrowserPreferences.addBookmark(this, tab.title.ifBlank { url }, url)
        }
        refreshStartPage()
    }

    // ---- Permission flows ----

    private fun handleMicRequest(request: PermissionRequest) {
        val host = request.origin?.host?.lowercase()
        pendingMicRequest = request
        pendingMicHost = host
        if (BrowserPreferences.isHostAllowedMic(this, host) && PermissionManager.hasAudio(this)) {
            pendingMicRequest = null
            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.permission_mic_message, host ?: (request.origin?.toString() ?: "")))
            .setNegativeButton(R.string.permission_deny) { _, _ -> pendingMicRequest = null; request.deny() }
            .setPositiveButton(R.string.permission_allow) { _, _ ->
                if (PermissionManager.hasAudio(this)) {
                    pendingMicRequest = null
                    host?.let { BrowserPreferences.addAllowedMicHost(this, it) }
                    request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            .show()
    }

    /** Microphone consent for the Web Speech API bridge. Resolves [result] with the decision. */
    private fun handleSpeechMicRequest(origin: String?, result: (Boolean) -> Unit) {
        val host = runCatching { Uri.parse(origin).host?.lowercase() }.getOrNull()
        if (BrowserPreferences.isHostAllowedMic(this, host) && PermissionManager.hasAudio(this)) {
            result(true); return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.permission_mic_message, host ?: origin.orEmpty()))
            .setNegativeButton(R.string.permission_deny) { _, _ -> result(false) }
            .setPositiveButton(R.string.permission_allow) { _, _ ->
                if (PermissionManager.hasAudio(this)) {
                    host?.let { BrowserPreferences.addAllowedMicHost(this, it) }
                    result(true)
                } else {
                    pendingSpeechResult = result
                    pendingSpeechHost = host
                    speechMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            .show()
    }

    private fun handleGeoRequest(origin: String?, callback: GeolocationPermissions.Callback?) {
        if (callback == null) return
        val host = runCatching { Uri.parse(origin).host?.lowercase() }.getOrNull()
        pendingGeoOrigin = origin
        pendingGeoCallback = callback
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.permission_location_message, host ?: origin.orEmpty()))
            .setNegativeButton(R.string.permission_deny) { _, _ -> finishGeoDecision(false) }
            .setPositiveButton(R.string.permission_allow) { _, _ ->
                if (PermissionManager.hasLocation(this)) finishGeoDecision(true)
                else locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .show()
    }

    private fun finishGeoDecision(allow: Boolean) {
        val origin = pendingGeoOrigin
        val callback = pendingGeoCallback
        pendingGeoOrigin = null
        pendingGeoCallback = null
        if (allow && origin != null) {
            runCatching { Uri.parse(origin).host?.lowercase() }.getOrNull()
                ?.let { BrowserPreferences.addAllowedLocationHost(this, it) }
        }
        callback?.invoke(origin, allow, false)
    }

    // ---- Cleartext consent ----

    private fun showCleartextDialog(uri: Uri, allowOnce: () -> Unit, allowHost: () -> Unit, cancel: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cleartext_title)
            .setMessage(getString(R.string.cleartext_message, uri.host ?: uri.toString()))
            .setCancelable(false)
            .setNeutralButton(R.string.cleartext_cancel) { _, _ -> cancel() }
            .setNegativeButton(R.string.cleartext_allow_once) { _, _ -> allowOnce() }
            .setPositiveButton(R.string.cleartext_allow_host) { _, _ -> allowHost() }
            .show()
    }

    // ---- Fullscreen video ----

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customViewCallback = callback
        binding.fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        binding.fullscreenContainer.isVisible = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.isVisible = false
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun openExternally(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    // ---- Lifecycle ----

    override fun onPause() {
        super.onPause()
        tabManager.pauseActive()
        tabManager.persistSession()
    }

    override fun onResume() {
        super.onResume()
        tabManager.resumeActive()
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }
}
