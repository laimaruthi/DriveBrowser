package com.myapp.drivebrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import com.myapp.drivebrowser.data.BrowserPreferences

/**
 * Invisible activity registered as a share target for `text/plain`. Extracts the first URL from
 * the shared text and saves it as a bookmark, then finishes immediately.
 */
class ShareBookmarkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleShare()
        finish()
    }

    private fun handleShare() {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") {
            toast(getString(R.string.share_bookmark_failed)); return
        }
        val shared = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val url = extractUrl(shared)
        if (url == null) {
            toast(getString(R.string.share_bookmark_failed)); return
        }
        val title = subject.ifBlank { shared.replace(url, "").trim().ifBlank { url } }
        BrowserPreferences.addBookmark(this, title, url)
        toast(getString(R.string.share_bookmark_saved))
    }

    private fun extractUrl(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        if (!matcher.find()) return null
        val found = matcher.group()
        return if (found.contains("://")) found else "https://$found"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
