package com.myapp.drivebrowser.adblock

import android.content.Context
import android.net.Uri
import com.myapp.drivebrowser.data.BrowserPreferences

/**
 * Host-based ad & tracker blocker. Loads a hosts-format blocklist from assets once, then answers
 * [shouldBlock] for each WebView sub-resource request by matching the request host (and its parent
 * domains) against the list.
 *
 * Thread-safe: the blocklist is built once under a lock and only read afterwards, so it is safe to
 * query from the WebView's resource-loading thread.
 */
object AdBlocker {

    private const val BLOCKLIST_ASSET = "adblock_hosts.txt"

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    private var loaded = false

    private val blockedDomains = HashSet<String>()

    /** Loads the blocklist (once) and syncs the enabled flag from preferences. */
    fun init(context: Context) {
        enabled = BrowserPreferences.isAdBlockEnabled(context)
        ensureLoaded(context.applicationContext)
    }

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    val blockedDomainCount: Int get() = blockedDomains.size

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        runCatching {
            context.assets.open(BLOCKLIST_ASSET).bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    // Accept "0.0.0.0 domain", "127.0.0.1 domain" and bare "domain" forms.
                    val domain = line.substringAfterLast(' ').substringAfterLast('\t').trim().lowercase()
                    if (domain.isNotEmpty() && domain != "localhost" && domain != "broadcasthost") {
                        blockedDomains.add(domain)
                    }
                }
            }
        }
        loaded = true
    }

    /** True if the request to [url] should be blocked. Main-frame checks are the caller's job. */
    fun shouldBlock(url: String?): Boolean {
        if (!enabled || url.isNullOrEmpty()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return isBlockedHost(host)
    }

    /** Matches the exact host and every parent domain, so subdomains of a blocked domain match too. */
    private fun isBlockedHost(host: String): Boolean {
        if (blockedDomains.contains(host)) return true
        var current = host
        var dot = current.indexOf('.')
        while (dot >= 0) {
            current = current.substring(dot + 1)
            if (current.indexOf('.') < 0) break // reached a bare TLD; stop
            if (blockedDomains.contains(current)) return true
            dot = current.indexOf('.')
        }
        return false
    }
}
