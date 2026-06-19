package com.myapp.drivebrowser.web

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.myapp.drivebrowser.R
import com.myapp.drivebrowser.adblock.AdBlocker
import com.myapp.drivebrowser.data.BrowserPreferences
import com.myapp.drivebrowser.model.UserAgentProfile
import java.io.ByteArrayInputStream

/** Callbacks the host activity supplies so the WebView can drive the UI. */
data class BrowserCallbacks(
    val onUrlChange: (String) -> Unit = {},
    val onTitleChange: (String?) -> Unit = {},
    val onFaviconReceived: (String, Bitmap?) -> Unit = { _, _ -> },
    val onProgressChange: (Int) -> Unit = {},
    val onShowDownloadPrompt: (Uri) -> Unit = {},
    val onCleartextNavigationRequested: (
        uri: Uri,
        allowOnce: () -> Unit,
        allowHostPermanently: () -> Unit,
        cancel: () -> Unit
    ) -> Unit = { _, _, _, cancel -> cancel() },
    val onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit = { _, _ -> },
    val onExitFullscreen: () -> Unit = {},
    val onPermissionRequest: (PermissionRequest) -> Unit = { it.deny() },
    val onGeolocationPermissionRequest: (String?, GeolocationPermissions.Callback?) -> Unit =
        { _, cb -> cb?.invoke(null, false, false) }
)

private val ALLOW_ONCE_TAG = R.id.allow_once_tag

fun configureWebView(
    webView: WebView,
    callbacks: BrowserCallbacks,
    useDesktopMode: Boolean,
    userAgentProfile: UserAgentProfile,
    allowDarkPages: Boolean
) {
    with(webView) {
        setBackgroundColor(Color.TRANSPARENT)
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = true
        WebView.setWebContentsDebuggingEnabled(false)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            allowFileAccess = false
            // Block active mixed content (scripts/iframes) on HTTPS pages; allow passive
            // (images) — the secure default, instead of MIXED_CONTENT_ALWAYS_ALLOW.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            safeBrowsingEnabled = true
            offscreenPreRaster = true
        }

        applyPageDarkening(allowDarkPages)
        applyBrowserIdentity(userAgentProfile, useDesktopMode)

        CookieManager.getInstance().also {
            it.setAcceptCookie(true)
            // Third-party cookies off by default for privacy.
            it.setAcceptThirdPartyCookies(this, false)
        }

        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (handleCleartextIfNeeded(view, request.url, callbacks, onPageStart = false)) return true
                return shouldHandExternally(request.url)
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                // Never block the top-level document — only ad/tracker sub-resources.
                if (!request.isForMainFrame && AdBlocker.shouldBlock(request.url?.toString())) {
                    return blockedResponse()
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { callbacks.onUrlChange(it) }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(SpeechRecognitionBridge.POLYFILL_JS, null)
                url?.let { callbacks.onUrlChange(it) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val show = when (error.errorCode) {
                    ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_UNKNOWN,
                    ERROR_PROXY_AUTHENTICATION -> true
                    else -> false
                }
                if (show) loadErrorPage(view, request.url?.toString(), error.errorCode, error.description?.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (!request.isForMainFrame) return
                val code = errorResponse.statusCode
                if (code in 400..599 && code != 429) {
                    loadErrorPage(view, request.url?.toString(), code, errorResponse.reasonPhrase)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                val activity = view.context as? android.app.Activity
                if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                    SslErrorHandlerHelper.handleSslError(activity, handler, error)
                } else {
                    handler.cancel()
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) =
                callbacks.onProgressChange(newProgress)

            override fun onReceivedTitle(view: WebView?, title: String?) =
                callbacks.onTitleChange(title)

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                val pageUrl = view?.url?.takeIf { it.isNotBlank() } ?: return
                callbacks.onFaviconReceived(pageUrl, icon)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) callbacks.onEnterFullscreen(view, callback)
                else super.onShowCustomView(view, callback)
            }

            override fun onHideCustomView() {
                callbacks.onExitFullscreen()
                super.onHideCustomView()
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val allowed = setOf(
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE
                )
                val grantable = request.resources.filter { it in allowed }.toTypedArray()
                if (grantable.isEmpty()) { request.deny(); return }
                if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in grantable) {
                    callbacks.onPermissionRequest(request)
                } else {
                    this@with.post { request.grant(grantable) }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) =
                callbacks.onGeolocationPermissionRequest(origin, callback)

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean =
                false
        }

        setDownloadListener(DownloadListener { url, _, _, _, _ ->
            url?.takeIf { it.isNotBlank() }?.toUri()?.let(callbacks.onShowDownloadPrompt)
        })
    }
}

/** An empty 200 response used to swallow a blocked ad/tracker request. */
private fun blockedResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

private fun loadErrorPage(view: WebView, failedUrl: String?, code: Int, message: String?) {
    val asset = "file:///android_asset/error.html?failedUrl=${Uri.encode(failedUrl.orEmpty())}" +
        "&code=$code&message=${Uri.encode(message.orEmpty())}"
    runCatching { view.loadUrl(asset) }
}

private fun shouldHandExternally(uri: Uri?): Boolean {
    val scheme = uri?.scheme?.lowercase() ?: return false
    return scheme !in setOf("http", "https", "about", "data", "javascript")
}

/** Returns true if the navigation was intercepted (a consent prompt shown). */
private fun handleCleartextIfNeeded(
    view: WebView,
    uri: Uri?,
    callbacks: BrowserCallbacks,
    onPageStart: Boolean
): Boolean {
    if (uri?.scheme?.lowercase() != "http") return false

    val asString = uri.toString()
    if (view.getTag(ALLOW_ONCE_TAG) == asString) {
        view.setTag(ALLOW_ONCE_TAG, null)
        return false
    }
    val host = uri.host?.lowercase()
    if (BrowserPreferences.isHostAllowedCleartext(view.context, host)) return false

    if (onPageStart) view.stopLoading()

    val allowOnce = {
        view.setTag(ALLOW_ONCE_TAG, asString)
        view.post { view.loadUrl(asString) }
    }
    val allowHost = {
        host?.let { BrowserPreferences.addAllowedCleartextHost(view.context, it) }
        view.setTag(ALLOW_ONCE_TAG, asString)
        view.post { view.loadUrl(asString) }
    }
    val cancel = { if (onPageStart) view.stopLoading() }
    callbacks.onCleartextNavigationRequested(uri, allowOnce, allowHost, cancel)
    return true
}

// ---- Identity / appearance helpers ----

fun WebView.updateDesktopMode(enable: Boolean, profile: UserAgentProfile) {
    applyBrowserIdentity(profile, enable); reload()
}

fun WebView.updateUserAgentProfile(profile: UserAgentProfile, desktop: Boolean) {
    applyBrowserIdentity(profile, desktop); reload()
}

fun WebView.updatePageDarkening(enabled: Boolean) {
    applyPageDarkening(enabled); reload()
}

fun WebView.releaseCompletely() {
    stopLoading()
    (parent as? android.view.ViewGroup)?.removeView(this)
    removeAllViews()
    webChromeClient = null
    webViewClient = WebViewClient()
    destroy()
}

private fun WebView.applyBrowserIdentity(profile: UserAgentProfile, desktop: Boolean) {
    settings.userAgentString = buildUserAgent(profile, desktop)
    settings.useWideViewPort = desktop
    settings.loadWithOverviewMode = desktop
    if (desktop) setInitialScale(0)
    else setInitialScale((context.resources.displayMetrics.density * 100).toInt())
}

private fun WebView.applyPageDarkening(enabled: Boolean) {
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, enabled)
    }
}

private const val CHROME_VERSION = "131.0.0.0"
private const val MOBILE_CHROME_UA =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_VERSION Mobile Safari/537.36"
private const val DESKTOP_CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROME_VERSION Safari/537.36"
private const val SAFARI_IOS_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"
private const val SAFARI_MAC_UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

private fun buildUserAgent(profile: UserAgentProfile, desktop: Boolean): String = when (profile) {
    UserAgentProfile.ANDROID_CHROME -> if (desktop) DESKTOP_CHROME_UA else MOBILE_CHROME_UA
    UserAgentProfile.SAFARI -> if (desktop) SAFARI_MAC_UA else SAFARI_IOS_UA
}
