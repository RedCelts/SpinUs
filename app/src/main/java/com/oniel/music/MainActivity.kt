package com.oniel.music

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.oniel.music.ui.OnielMusicApp
import com.oniel.music.ui.theme.OnielMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContent {
                OnielMusicTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        OnielMusicApp()
                    }
                }
            }
        } catch (e: Throwable) {
            // Never let a startup failure kill the app with no explanation:
            // fall back to a plain (non-Compose) error screen.
            showStartupError(e)
        }
    }

    /** Plain Android Views only — works even when Compose itself is broken. */
    private fun showStartupError(e: Throwable) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "Oniel Music couldn't start"
            textSize = 20f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        val detail = TextView(this).apply {
            text = "Something went wrong while opening the app.\n\n" +
                "${e.javaClass.simpleName}: ${e.message ?: "no message"}\n\n" +
                "Try closing and reopening the app. If it keeps happening, " +
                "reinstalling usually fixes it."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        layout.addView(title)
        layout.addView(detail)
        setContentView(layout)
    }
}
