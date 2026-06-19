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

    private val voiceSearchLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val spoken = result.data
                    ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.trim()
                if (!spoken.isNullOrEmpty()) loadUrl(BrowserPreferences.normalizeToUrlOrSearch(spoken))
            }
        }

    private val backgroundPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                BrowserPreferences.setStartBackgroundUri(this, uri.toString())
                applyStartBackground()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyStoredTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.myapp.drivebrowser.adblock.AdBlocker.init(this)
        com.myapp.drivebrowser.data.SiteIconCache.init(this)

        // Refresh the ad/tracker blocklist in the background if it's stale.
        if (BrowserPreferences.isAdBlockEnabled(this) &&
            com.myapp.drivebrowser.adblock.AdBlocker.isStale(this, System.currentTimeMillis())
        ) {
            com.myapp.drivebrowser.adblock.AdBlocker.updateFromUrl(this, System.currentTimeMillis())
        }

        tabManager = TabManager(this, binding.webViewContainer, buildTabCallbacks())

        setupAdapters()
        setupToolbar()
        setupAddressBar()
        setupMenu()
        setupBackHandling()

        tabManager.restoreSessionOrHome()
        refreshStartPage()
        setupFab()
        applyStartBackground()
        updateContentVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !PermissionManager.hasNotifications(this)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Quietly check for a newer release; notify only if one is available.
        com.myapp.drivebrowser.update.UpdateChecker.check(com.myapp.drivebrowser.BuildConfig.VERSION_NAME) { r ->
            if (r.updateAvailable) {
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.update_available_title) + ": v" + r.latestVersion,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
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
        startPageAdapter = StartPageAdapter(
            onOpen = { url -> loadUrl(url) },
            onEdit = { index -> editQuickLink(index) }
        )
        binding.startPageRecycler.layoutManager = GridLayoutManager(this, 2)
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
        binding.btnMic.setOnClickListener { startVoiceSearch() }
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
        binding.menuAddressBar.setOnClickListener {
            hidePanel(); binding.addressEdit.requestFocus(); showKeyboard()
        }
        binding.footerVersion.text = getString(R.string.menu_footer, com.myapp.drivebrowser.BuildConfig.VERSION_NAME)
        binding.btnCheckUpdates.setOnClickListener { checkForUpdatesFromMenu() }
        binding.btnGithub.setOnClickListener { openExternally(android.net.Uri.parse(GITHUB_REPO_URL)) }

        binding.btnResumeLast.setOnClickListener {
            val last = BrowserPreferences.getLastPageUrl(this)
            if (last != null) loadUrl(last)
            else android.widget.Toast.makeText(this, R.string.empty_slot_hint, android.widget.Toast.LENGTH_SHORT).show()
        }
        binding.btnPhoto.setOnClickListener { backgroundPicker.launch(arrayOf("image/*")) }
    }

    private fun configTile(
        tile: com.myapp.drivebrowser.databinding.MenuTileBinding,
        iconRes: Int,
        labelRes: Int,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        tile.tileIcon.setImageResource(iconRes)
        tile.tileLabel.setText(labelRes)
        tile.root.isEnabled = enabled
        tile.root.alpha = if (enabled) 1f else 0.4f
        tile.root.setOnClickListener { if (enabled) onClick() }
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
        val slots = BrowserPreferences.getQuickLinks(this)
            .map { StartPageAdapter.Slot(it.title, it.url) }
        startPageAdapter.submit(slots)
    }

    private fun editQuickLink(index: Int) {
        val current = BrowserPreferences.getQuickLinks(this).getOrNull(index)
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val labelInput = android.widget.EditText(this).apply {
            hint = getString(R.string.quick_link_label_hint)
            setText(current?.title.orEmpty())
            setSingleLine()
        }
        val urlInput = android.widget.EditText(this).apply {
            hint = getString(R.string.quick_link_url_hint)
            setText(current?.url.orEmpty())
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        container.addView(labelInput)
        container.addView(urlInput)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_quick_link)
            .setView(container)
            .setNeutralButton(R.string.remove) { _, _ ->
                BrowserPreferences.setQuickLink(this, index, "", ""); refreshStartPage()
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val rawUrl = urlInput.text?.toString().orEmpty().trim()
                val url = if (rawUrl.isBlank()) "" else BrowserPreferences.normalizeToUrlOrSearch(rawUrl)
                BrowserPreferences.setQuickLink(this, index, labelInput.text?.toString().orEmpty(), url)
                refreshStartPage()
            }
            .show()
    }

    private fun setupFab() {
        val fab = binding.fabQuickAction
        if (!BrowserPreferences.isFabEnabled(this)) {
            fab.visibility = View.GONE
            return
        }
        fab.visibility = View.VISIBLE
        (fab.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = BrowserPreferences.getFabPosition(this).gravity
            fab.layoutParams = it
        }
        val mode = BrowserPreferences.getFabMode(this)
        fab.setImageResource(
            if (mode == com.myapp.drivebrowser.model.QuickActionButtonMode.URL_BAR) R.drawable.ic_search else R.drawable.ic_menu
        )
        fab.setOnClickListener {
            if (mode == com.myapp.drivebrowser.model.QuickActionButtonMode.URL_BAR) {
                binding.addressEdit.requestFocus()
                showKeyboard()
            } else {
                showMenuPanel()
            }
        }
    }

    private fun startVoiceSearch() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_search_prompt))
        }
        runCatching { voiceSearchLauncher.launch(intent) }.onFailure {
            android.widget.Toast.makeText(this, R.string.voice_search_unavailable, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        binding.addressEdit.post { imm.showSoftInput(binding.addressEdit, 0) }
    }

    private fun applyStartBackground() {
        val uriStr = BrowserPreferences.getStartBackgroundUri(this)
        if (uriStr == null) {
            binding.startPageScroll.setBackgroundResource(R.drawable.bg_start_gradient)
            return
        }
        val applied = runCatching {
            contentResolver.openInputStream(android.net.Uri.parse(uriStr)).use { stream ->
                val drawable = android.graphics.drawable.Drawable.createFromStream(stream, uriStr)
                if (drawable != null) { binding.startPageScroll.background = drawable; true } else false
            }
        }.getOrDefault(false)
        if (!applied) binding.startPageScroll.setBackgroundResource(R.drawable.bg_start_gradient)
    }

    companion object {
        private const val GITHUB_REPO_URL = "https://github.com/laimaruthi/DriveBrowser"
    }

    // ---- Panel (menu / tabs / bookmarks) ----

    private fun showMenuPanel() {
        binding.panelTitle.text = getString(R.string.start_page_title)
        binding.panelSubtitle.text = getString(R.string.menu_start_page_subtitle)
        binding.menuList.isVisible = true
        binding.panelRecycler.isVisible = false

        val tab = tabManager.activeTab
        val wv = tab?.webView
        val currentUrl = tab?.currentUrl.orEmpty()
        val isWebPage = currentUrl.startsWith("http")
        val isBookmarked = BrowserPreferences.isBookmarked(this, currentUrl)

        configTile(binding.tileBack, R.drawable.ic_arrow_back, R.string.back, enabled = wv?.canGoBack() == true) { tabManager.goBack(); hidePanel() }
        configTile(binding.tileReload, R.drawable.ic_refresh, R.string.reload) { tabManager.reload(); hidePanel() }
        configTile(binding.tileForward, R.drawable.ic_arrow_forward, R.string.forward, enabled = wv?.canGoForward() == true) { tabManager.goForward(); hidePanel() }

        configTile(binding.tileBookmarks, R.drawable.ic_public, R.string.bookmarks) { showBookmarksPanel() }
        configTile(binding.tileExternal, R.drawable.ic_open_in_new, R.string.menu_external, enabled = isWebPage) { openExternally(android.net.Uri.parse(currentUrl)); hidePanel() }
        configTile(binding.tileSettings, R.drawable.ic_settings, R.string.settings_title) { startActivity(Intent(this, SettingsActivity::class.java)); hidePanel() }

        configTile(binding.tileStartPage, R.drawable.ic_home, R.string.menu_start_page_subtitle) { showStartPage(); hidePanel() }
        configTile(binding.tileTabs, R.drawable.ic_tabs, R.string.tabs) { showTabsPanel() }
        configTile(binding.tileNewTab, R.drawable.ic_add, R.string.new_tab) { tabManager.createTab(BrowserPreferences.getHomePageUrl(this), true); hidePanel(); updateContentVisibility() }

        configTile(
            binding.tileBookmarkToggle,
            if (isBookmarked) R.drawable.ic_bookmark_remove else R.drawable.ic_bookmark_add,
            if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark,
            enabled = isWebPage
        ) { toggleBookmark(); hidePanel() }
        configTile(binding.tileQr, R.drawable.ic_qr, R.string.share_qr, enabled = isWebPage) { showQrForCurrentPage(); hidePanel() }

        binding.menuDesktopSwitch.setOnCheckedChangeListener(null)
        binding.menuDesktopSwitch.isChecked = tabManager.isDesktopMode
        binding.menuDesktopSwitch.setOnCheckedChangeListener { _, checked -> tabManager.setDesktopMode(checked) }

        openPanel()
    }

    private fun checkForUpdatesFromMenu() {
        android.widget.Toast.makeText(this, R.string.update_checking, android.widget.Toast.LENGTH_SHORT).show()
        com.myapp.drivebrowser.update.UpdateChecker.check(com.myapp.drivebrowser.BuildConfig.VERSION_NAME) { r ->
            when {
                r.error != null ->
                    android.widget.Toast.makeText(this, getString(R.string.update_failed, r.error), android.widget.Toast.LENGTH_LONG).show()
                r.updateAvailable ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.update_available_title)
                        .setMessage(getString(R.string.update_available_message, r.latestVersion, r.currentVersion))
                        .setNegativeButton(R.string.update_later, null)
                        .setPositiveButton(R.string.update_download) { _, _ ->
                            openExternally(android.net.Uri.parse(r.downloadUrl))
                        }
                        .show()
                else ->
                    android.widget.Toast.makeText(this, getString(R.string.update_up_to_date, r.currentVersion), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun showQrForCurrentPage() {
        val url = tabManager.activeTab?.currentUrl?.takeIf { it.startsWith("http") }
        if (url == null) {
            android.widget.Toast.makeText(this, R.string.start_page_title, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val bitmap = com.myapp.drivebrowser.ui.QrUtils.encode(url) ?: return
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            adjustViewBounds = true
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qr_dialog_title)
            .setMessage(url)
            .setView(image)
            .setPositiveButton(R.string.close, null)
            .show()
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
        tabManager.applyGlobalScale()
        refreshStartPage()
        setupFab()
        applyStartBackground()
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }
}
