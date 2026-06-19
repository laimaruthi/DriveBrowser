package com.myapp.drivebrowser.web

import android.app.Activity
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.myapp.drivebrowser.R

/**
 * Handles WebView SSL certificate errors with an explicit user prompt.
 *
 * A "proceed" decision is remembered per (host + exact certificate), in memory only,
 * for the rest of the process. Because the specific certificate is part of the key, a
 * *different* bad certificate for the same host (e.g. an active MITM) re-prompts rather
 * than silently proceeding.
 */
object SslErrorHandlerHelper {

    private val allowed = HashSet<String>()

    fun handleSslError(activity: Activity, handler: SslErrorHandler, error: SslError) {
        val host = runCatching { Uri.parse(error.url ?: "").host?.lowercase() }.getOrNull()
        val key = host + "|" + (error.certificate?.toString() ?: "")

        if (allowed.contains(key)) { handler.proceed(); return }
        if (activity.isFinishing || activity.isDestroyed) { handler.cancel(); return }

        val reason = describe(activity, error.primaryError)
        val hostLabel = host ?: error.url.orEmpty()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.ssl_error_title)
            .setMessage(
                activity.getString(R.string.ssl_error_message, hostLabel) + "\n\n" +
                    activity.getString(R.string.ssl_error_reason_prefix) + " " + reason
            )
            .setCancelable(false)
            .setNegativeButton(R.string.ssl_error_cancel) { d, _ -> d.dismiss(); handler.cancel() }
            .setPositiveButton(R.string.ssl_error_proceed) { d, _ ->
                d.dismiss(); allowed.add(key); handler.proceed()
            }
            .show()
    }

    private fun describe(activity: Activity, code: Int): String = activity.getString(
        when (code) {
            SslError.SSL_EXPIRED -> R.string.ssl_error_expired
            SslError.SSL_IDMISMATCH -> R.string.ssl_error_idmismatch
            SslError.SSL_UNTRUSTED -> R.string.ssl_error_untrusted
            SslError.SSL_NOTYETVALID -> R.string.ssl_error_notyetvalid
            SslError.SSL_DATE_INVALID -> R.string.ssl_error_date_invalid
            else -> R.string.ssl_error_invalid
        }
    )

    fun clear() = allowed.clear()
}
