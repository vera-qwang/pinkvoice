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
        // debug 版默认打开共振峰诊断日志（release 不打）
        val debuggable =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        com.vera.pinkvoice.audio.Diagnostics.enabled = debuggable
        if (debuggable) {
            // debug 专用：把最近录到的音频导出来做离线对照
            registerReceiver(
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                        val f = java.io.File(
                            getExternalFilesDir(null),
                            "live-${System.currentTimeMillis()}.wav",
                        )
                        val sec = engine.dumpRecentAudio(f, 20)
                        android.util.Log.i("PinkVoiceDiag", "DUMP ${f.absolutePath} ${sec}s")
                    }
                },
                android.content.IntentFilter("com.vera.pinkvoice.DUMP_AUDIO"),
                android.content.Context.RECEIVER_EXPORTED,
            )
        }
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
