package com.myapp.drivebrowser.tabs

import android.webkit.WebView
import com.myapp.drivebrowser.web.SpeechRecognitionBridge

/** One open browser tab: its WebView plus the latest known title/url. */
data class BrowserTab(
    val id: Long,
    val webView: WebView,
    var title: String,
    var currentUrl: String,
    var speechBridge: SpeechRecognitionBridge? = null
)
