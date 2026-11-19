package com.vera.pinkvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.vera.pinkvoice.audio.VoiceEngine
import com.vera.pinkvoice.ui.PinkVoiceTheme
import com.vera.pinkvoice.ui.ThemeMode
import com.vera.pinkvoice.ui.PinkVoiceApp

class MainActivity : ComponentActivity() {

    private val engine = VoiceEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(Prefs.themeMode(this@MainActivity)) }
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            PinkVoiceTheme(darkTheme = dark) {
                PinkVoiceApp(
                    engine = engine,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeMode = mode
                        Prefs.setThemeMode(this@MainActivity, mode)
                    },
                )
            }
        }
    }

    /** 离开前台即释放麦克风。 */
    override fun onStop() {
        super.onStop()
        engine.stop()
    }
}
