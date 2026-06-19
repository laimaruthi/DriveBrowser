package com.myapp.drivebrowser.update

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer version of the app.
 *
 * Note: the GitHub API only returns releases for a PUBLIC repository to an unauthenticated
 * client. If the repo is private the request returns 404 and [Result.error] is set.
 */
object UpdateChecker {

    private const val OWNER = "laimaruthi"
    private const val REPO = "DriveBrowser"
    private const val LATEST_API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

    data class Result(
        val updateAvailable: Boolean,
        val currentVersion: String,
        val latestVersion: String?,
        val downloadUrl: String,
        val error: String?
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val main = Handler(Looper.getMainLooper())

    /** Asynchronously checks for an update; [onResult] is always invoked on the main thread. */
    fun check(currentVersion: String, onResult: (Result) -> Unit) {
        val request = Request.Builder()
            .url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                post(onResult, Result(false, currentVersion, null, RELEASES_PAGE, e.message ?: "Network error"))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        val msg = if (it.code == 404) "No public releases found" else "HTTP ${it.code}"
                        post(onResult, Result(false, currentVersion, null, RELEASES_PAGE, msg))
                        return
                    }
                    val body = it.body?.string().orEmpty()
                    val latest = runCatching { JSONObject(body).optString("tag_name") }
                        .getOrNull()?.removePrefix("v")?.trim()
                    if (latest.isNullOrEmpty()) {
                        post(onResult, Result(false, currentVersion, null, RELEASES_PAGE, "Could not read latest version"))
                        return
                    }
                    val newer = isNewer(latest, currentVersion)
                    post(onResult, Result(newer, currentVersion, latest, RELEASES_PAGE, null))
                }
            }
        })
    }

    private fun post(onResult: (Result) -> Unit, result: Result) = main.post { onResult(result) }

    /** True if [latest] is a higher version than [current] (numeric, dot-separated). */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.split('.', '-').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
}
