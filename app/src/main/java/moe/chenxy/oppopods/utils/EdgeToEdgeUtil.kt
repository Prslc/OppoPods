package moe.chenxy.oppopods.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Configures the activity window for edge-to-edge with transparent system bars.
 * Status bar icon appearance follows the app's resolved dark mode.
 */
fun ComponentActivity.applyTransparentEdgeToEdge(darkMode: Boolean) {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
    )
    window.isNavigationBarContrastEnforced = false
}

/** Resolves the dark mode from the theme_mode preference and applies edge-to-edge. */
fun ComponentActivity.applyTransparentEdgeToEdge() {
    val darkMode = when (getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE).getInt("theme_mode", 0)) {
        1 -> false
        2 -> true
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    applyTransparentEdgeToEdge(darkMode)
}
