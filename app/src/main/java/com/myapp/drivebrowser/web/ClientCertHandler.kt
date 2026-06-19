package com.myapp.drivebrowser.web

import android.app.Activity
import android.content.Context
import android.security.KeyChain
import android.webkit.ClientCertRequest

/**
 * Responds to WebView client-certificate (mutual TLS) requests. Remembers the chosen KeyChain
 * alias per host:port (encrypted at rest via [CryptoHelper]) so the user isn't re-prompted, and
 * falls back to the system certificate chooser when no choice is stored.
 */
object ClientCertHandler {

    private const val PREF_NAME = "client_cert_prefs"

    fun handleClientCertRequest(activity: Activity, request: ClientCertRequest) {
        val host = request.host
        val port = request.port
        val prefKey = if (port >= 0) "$host:$port" else host

        val prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedAlias = prefs.getString(prefKey, null)?.let { CryptoHelper.decrypt(it) }

        if (savedAlias != null) {
            proceedWithAlias(activity, request, savedAlias) {
                prefs.edit().remove(prefKey).apply()
                activity.runOnUiThread { chooseAlias(activity, request, prefs, prefKey, host, port) }
            }
        } else {
            chooseAlias(activity, request, prefs, prefKey, host, port)
        }
    }

    private fun chooseAlias(
        activity: Activity,
        request: ClientCertRequest,
        prefs: android.content.SharedPreferences,
        prefKey: String,
        host: String,
        port: Int
    ) {
        KeyChain.choosePrivateKeyAlias(
            activity,
            { alias ->
                if (alias == null) { request.cancel(); return@choosePrivateKeyAlias }
                CryptoHelper.encrypt(alias)?.let { prefs.edit().putString(prefKey, it).apply() }
                proceedWithAlias(activity, request, alias) { activity.runOnUiThread { request.cancel() } }
            },
            request.keyTypes, request.principals, host, port, null
        )
    }

    private fun proceedWithAlias(activity: Activity, request: ClientCertRequest, alias: String, onFail: () -> Unit) {
        Thread {
            runCatching {
                val key = KeyChain.getPrivateKey(activity, alias)
                val chain = KeyChain.getCertificateChain(activity, alias)
                if (key != null && chain != null) {
                    activity.runOnUiThread { request.proceed(key, chain) }
                } else onFail()
            }.onFailure { onFail() }
        }.start()
    }
}
