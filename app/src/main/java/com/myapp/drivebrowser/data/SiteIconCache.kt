package com.myapp.drivebrowser.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Caches first-party site icons (favicons) to disk, keyed by host, so the start page and
 * bookmark list stay branded without refetching on every launch.
 */
object SiteIconCache {

    private var dir: File? = null
    private val memory = ConcurrentHashMap<String, Bitmap>()

    fun init(context: Context) {
        if (dir == null) {
            dir = File(context.applicationContext.cacheDir, "site_icons").apply { mkdirs() }
        }
    }

    fun hostOf(url: String?): String? =
        runCatching { Uri.parse(url).host?.lowercase() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun save(url: String?, bitmap: Bitmap?) {
        val host = hostOf(url) ?: return
        if (bitmap == null || bitmap.width <= 1) return
        memory[host] = bitmap
        val file = fileFor(host) ?: return
        runCatching {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Returns a cached icon for the URL's host, or null if none is stored. */
    fun load(url: String?): Bitmap? {
        val host = hostOf(url) ?: return null
        memory[host]?.let { return it }
        val file = fileFor(host)?.takeIf { it.exists() } ?: return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }
            .getOrNull()
            ?.also { memory[host] = it }
    }

    private fun fileFor(host: String): File? {
        val safe = host.replace(Regex("[^a-z0-9.-]"), "_")
        return dir?.let { File(it, "$safe.png") }
    }
}
