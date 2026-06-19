package com.myapp.drivebrowser.model

import android.view.Gravity

/** Screen corner where the floating quick-action button sits. */
enum class QuickActionButtonPosition(val storageKey: String, val gravity: Int) {
    TOP_START("top_start", Gravity.TOP or Gravity.START),
    TOP_END("top_end", Gravity.TOP or Gravity.END),
    BOTTOM_START("bottom_start", Gravity.BOTTOM or Gravity.START),
    BOTTOM_END("bottom_end", Gravity.BOTTOM or Gravity.END);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.storageKey == key } ?: BOTTOM_END
    }
}
