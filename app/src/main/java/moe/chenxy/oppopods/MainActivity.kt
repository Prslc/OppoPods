package moe.chenxy.oppopods

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.core.content.edit
import moe.chenxy.oppopods.ui.App
import moe.chenxy.oppopods.utils.applyTransparentEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val prefs = remember { getSharedPreferences("oppopods_settings", Context.MODE_PRIVATE) }
            val themeMode = remember { mutableIntStateOf(prefs.getInt("theme_mode", 0)) }
            val systemDark = isSystemInDarkTheme()
            val darkMode = when (themeMode.intValue) {
                1 -> false
                2 -> true
                else -> systemDark
            }

            DisposableEffect(darkMode) {
                applyTransparentEdgeToEdge(darkMode)

                onDispose {}
            }

            App(
                themeMode = themeMode,
                onThemeModeChange = {
                    themeMode.intValue = it
                    prefs.edit { putInt("theme_mode", it) }
                }
            )
        }
    }
}
