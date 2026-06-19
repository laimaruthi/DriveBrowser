package com.myapp.drivebrowser.adblock

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.myapp.drivebrowser.data.BrowserPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Host-based ad & tracker blocker. Loads a hosts-format blocklist and answers [shouldBlock] for
 * each WebView sub-resource request by matching the request host (and its parent domains).
 *
 * The list comes from (in priority order) a downloaded cache file, else the small bundled asset.
 * [updateFromUrl] fetches a full remote list (e.g. StevenBlack/hosts) and swaps it in atomically.
 */
object AdBlocker {

    private const val BUNDLED_ASSET = "adblock_hosts.txt"
    private const val CACHE_FILE = "adblock_hosts_cache.txt"
    const val DEFAULT_LIST_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    private val STALE_AFTER_MS = TimeUnit.DAYS.toMillis(7)

    @Volatile var enabled: Boolean = false
        private set

    // Swapped atomically on (re)load so concurrent shouldBlock() reads never see a half-built set.
    @Volatile private var domains: Set<String> = emptySet()
    @Volatile private var loaded = false

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val main = Handler(Looper.getMainLooper())

    val blockedDomainCount: Int get() = domains.size

    fun init(context: Context) {
        enabled = BrowserPreferences.isAdBlockEnabled(context)
        if (!loaded) {
            domains = buildSet(context.applicationContext)
            loaded = true
        }
    }

    fun setEnabled(value: Boolean) { enabled = value }

    /** True if the blocklist hasn't been refreshed within the staleness window. */
    fun isStale(context: Context, now: Long): Boolean =
        now - BrowserPreferences.getBlocklistUpdatedAt(context) > STALE_AFTER_MS

    /**
     * Downloads [url] in the background, caches it, and swaps in the new domain set.
     * [onComplete] is invoked on the main thread with success and the resulting domain count.
     */
    fun updateFromUrl(context: Context, now: Long, url: String = DEFAULT_LIST_URL, onComplete: (Boolean, Int) -> Unit = { _, _ -> }) {
        val appContext = context.applicationContext
        Thread {
            val ok = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching false
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank()) return@runCatching false
                    File(appContext.filesDir, CACHE_FILE).writeText(text)
                    true
                }
            }.getOrDefault(false)

            if (ok) {
                domains = buildSet(appContext)
                loaded = true
                BrowserPreferences.setBlocklistUpdatedAt(appContext, now)
            }
            main.post { onComplete(ok, domains.size) }
        }.start()
    }

    fun shouldBlock(url: String?): Boolean {
        if (!enabled || url.isNullOrEmpty()) return false
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() ?: return false
        return isBlockedHost(host)
    }

    private fun isBlockedHost(host: String): Boolean {
        val set = domains
        if (set.contains(host)) return true
        var current = host
        var dot = current.indexOf('.')
        while (dot >= 0) {
            current = current.substring(dot + 1)
            if (current.indexOf('.') < 0) break
            if (set.contains(current)) return true
            dot = current.indexOf('.')
        }
        return false
    }

    /** Reads the cache file if present, otherwise the bundled asset, into a domain set. */
    private fun buildSet(context: Context): Set<String> {
        val out = HashSet<String>()
        val cache = File(context.filesDir, CACHE_FILE)
        val reader: (java.io.Reader) -> Unit = { r ->
            r.buffered().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val domain = line.substringAfterLast(' ').substringAfterLast('\t').trim().lowercase()
                    if (domain.isNotEmpty() && domain != "localhost" && domain != "broadcasthost" && '.' in domain) {
                        out.add(domain)
                    }
                }
            }
        }
        runCatching {
            if (cache.exists() && cache.length() > 0) cache.reader().use(reader)
            else context.assets.open(BUNDLED_ASSET).reader().use(reader)
        }
        return out
    }
}
