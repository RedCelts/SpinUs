package com.oniel.music.ui

import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Badge count: unread recommendations + friend requests waiting for you. */
object Inbox {
    var unread by mutableIntStateOf(0)
    /** Bumped whenever something arrives, so open screens (Friends) reload. */
    var version by mutableIntStateOf(0)
}

/** A friend QR link (spinus://friend/<code>) the app was opened with, waiting to be used. */
object PendingLinks {
    var friendCode by mutableStateOf<String?>(null)

    fun take(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "spinus" && uri.host == "friend") {
            uri.lastPathSegment?.takeIf { it.isNotBlank() }?.let { friendCode = it }
        }
    }
}
