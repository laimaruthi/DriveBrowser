package com.myapp.drivebrowser.web

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Bridges the web `SpeechRecognition` / `webkitSpeechRecognition` API to Android's native
 * [SpeechRecognizer]. A small JS polyfill ([POLYFILL_JS]) is injected into each page; it posts
 * commands over a [WebMessageCompat] channel named [BRIDGE_OBJECT_NAME], and recognition events
 * are dispatched back into the page.
 *
 * Security: a command is only honored when it originates from the main frame and its origin
 * matches the page currently loaded in the WebView ([isTrustedCaller]). Microphone access is
 * gated by [onRequestMicrophoneAccess], which the host resolves via the per-site consent flow.
 */
class SpeechRecognitionBridge(
    webView: WebView,
    private val onRequestMicrophoneAccess: (origin: String?) -> Unit
) : RecognitionListener {

    private enum class Command { START, STOP, ABORT }

    private val webViewRef = WeakReference(webView)
    private var recognizer: SpeechRecognizer? = null
    private var pendingLang: String? = null

    fun handleWebMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        currentPageUrl: String?
    ) {
        if (!isTrustedCaller(sourceOrigin, isMainFrame, currentPageUrl)) return
        val payload = message.data ?: return
        val (command, lang) = parseCommand(payload) ?: return
        when (command) {
            Command.START -> {
                pendingLang = lang.orEmpty()
                webViewRef.get()?.post { onRequestMicrophoneAccess(sourceOrigin.toString()) }
            }
            Command.STOP -> webViewRef.get()?.post { recognizer?.stopListening() }
            Command.ABORT -> webViewRef.get()?.post {
                recognizer?.cancel(); stopInternal(); dispatch("end")
            }
        }
    }

    /** Called by the host once the user's microphone consent decision is known. */
    fun onPermissionResult(granted: Boolean) {
        val lang = pendingLang
        pendingLang = null
        if (granted && lang != null) startListening(lang)
        else { dispatchError("not-allowed"); dispatch("end") }
    }

    fun destroy() = stopInternal()

    // ---- Native recognition ----

    private fun startListening(lang: String) {
        val context = webViewRef.get()?.context ?: return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            dispatchError("service-not-allowed"); dispatch("end"); return
        }
        stopInternal()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                if (lang.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            }
            sr.startListening(intent)
        }
    }

    private fun stopInternal() {
        recognizer?.apply {
            runCatching { stopListening() }
            runCatching { destroy() }
        }
        recognizer = null
    }

    // ---- Validation / parsing ----

    private fun isTrustedCaller(sourceOrigin: Uri, isMainFrame: Boolean, currentPageUrl: String?): Boolean {
        if (!isMainFrame) return false
        val pageUri = currentPageUrl
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() }
            ?: return false
        return normalizedOrigin(sourceOrigin) == normalizedOrigin(pageUri)
    }

    private fun normalizedOrigin(uri: Uri): String {
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        return "$scheme://$host:$port"
    }

    private fun parseCommand(payload: String): Pair<Command, String?>? = try {
        val json = JSONObject(payload)
        when (json.optString("type").lowercase()) {
            "start" -> Command.START to json.optString("lang")
            "stop" -> Command.STOP to null
            "abort" -> Command.ABORT to null
            else -> null
        }
    } catch (_: JSONException) { null }

    // ---- Dispatch back into the page ----

    private fun dispatch(eventType: String) = eval("window.__sr_event&&window.__sr_event('$eventType')")

    private fun dispatchError(code: String) = eval("window.__sr_event&&window.__sr_event('error','$code')")

    private fun dispatchResults(matches: List<String>, confidences: FloatArray?, isFinal: Boolean) {
        val alts = JSONArray()
        matches.forEachIndexed { i, text ->
            alts.put(JSONObject().apply {
                put("transcript", text)
                put("confidence", (confidences?.getOrNull(i) ?: 0.9f).toDouble())
            })
        }
        val escaped = JSONObject().apply { put("a", alts); put("f", isFinal) }
            .toString()
            .replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        eval("window.__sr_event&&window.__sr_event('result','$escaped')")
    }

    private fun eval(js: String) {
        val webView = webViewRef.get() ?: return
        webView.post { webView.evaluateJavascript(js, null) }
    }

    // ---- RecognitionListener ----

    override fun onReadyForSpeech(params: Bundle?) { dispatch("start"); dispatch("audiostart") }
    override fun onBeginningOfSpeech() = dispatch("speechstart")
    override fun onEndOfSpeech() { dispatch("speechend"); dispatch("audioend") }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        if (matches.isNullOrEmpty()) { dispatchError("no-speech"); dispatch("end"); return }
        dispatchResults(matches, confidences, isFinal = true)
        dispatch("end")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (matches.isNullOrEmpty()) return
        dispatchResults(matches, null, isFinal = false)
    }

    override fun onError(error: Int) {
        val code = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
            SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
            else -> "aborted"
        }
        dispatchError(code); dispatch("end")
    }

    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    companion object {
        const val BRIDGE_OBJECT_NAME = "_SpeechBridgeChannel"

        val POLYFILL_JS = """
            (function(){
                if(window.__sr_polyfill) return;
                window.__sr_polyfill = true;
                var bridge = window.$BRIDGE_OBJECT_NAME;
                var active = null;
                window.__sr_event = function(type, data) {
                    if(!active) return;
                    var r = active;
                    if(type === 'result') {
                        try {
                            var d = JSON.parse(data);
                            var alts = d.a;
                            var result = {isFinal: d.f, length: alts.length};
                            for(var i = 0; i < alts.length; i++) result[i] = alts[i];
                            var results = {length: 1, 0: result};
                            if(r.onresult) r.onresult({resultIndex: 0, results: results});
                        } catch(e) {}
                    } else if(type === 'error') {
                        if(r.onerror) r.onerror({error: data});
                    } else {
                        var handler = r['on' + type];
                        if(handler) { try { handler(new Event(type)); } catch(e) { handler({}); } }
                    }
                    if(type === 'end') active = null;
                };
                function postToNative(payload) {
                    if(!bridge || typeof bridge.postMessage !== 'function') {
                        window.__sr_event('error', 'service-not-allowed');
                        window.__sr_event('end');
                        return;
                    }
                    try { bridge.postMessage(JSON.stringify(payload)); }
                    catch(e) {
                        window.__sr_event('error', 'service-not-allowed');
                        window.__sr_event('end');
                    }
                }
                function SR() {
                    this.lang = ''; this.continuous = false; this.interimResults = false;
                    this.maxAlternatives = 1;
                    this.onresult = null; this.onerror = null; this.onstart = null; this.onend = null;
                    this.onspeechstart = null; this.onspeechend = null;
                    this.onaudiostart = null; this.onaudioend = null; this.onnomatch = null;
                }
                SR.prototype.start = function() { active = this; postToNative({type:'start', lang:this.lang||''}); };
                SR.prototype.stop = function() { postToNative({type:'stop'}); };
                SR.prototype.abort = function() { postToNative({type:'abort'}); };
                SR.prototype.addEventListener = function(t, fn) { this['on'+t] = fn; };
                SR.prototype.removeEventListener = function(t, fn) { if(this['on'+t]===fn) this['on'+t]=null; };
                window.SpeechRecognition = SR;
                window.webkitSpeechRecognition = SR;
            })();
        """.trimIndent()
    }
}
