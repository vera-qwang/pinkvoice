package com.vera.pinkvoice.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.Prefs
import com.vera.pinkvoice.audio.AudioOutputInfo
import com.vera.pinkvoice.audio.EngineStatus
import com.vera.pinkvoice.audio.ReplayAction
import com.vera.pinkvoice.audio.VoiceEngine
import kotlinx.coroutines.delay

/**
 * App 的根页面。
 *
 * ## 结构
 *
 * ```
 * ┌──────────────────────────────┐
 * │ 头部：品牌 + 设备 + 设置       │  ← 两个页面共用
 * ├──────────────────────────────┤
 * │                              │
 * │ 页面内容（F0 或 F1/F2）       │  ← 各页面只管内容
 * │                              │
 * ├──────────────────────────────┤
 * │ 控制条：监听 / 回放            │  ← 两个页面共用
 * ├──────────────────────────────┤
 * │ 底部导航：F0 │ F1/F2           │  ← 页面切换
 * └──────────────────────────────┘
 * ```
 *
 * 头部和控制条**共享**是关键：之前两个页面各画各的，
 * 切换时一会儿有标题一会儿没有，控件位置也跳来跳去。
 */
@Composable
fun PinkVoiceApp(
    engine: VoiceEngine,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val palette = LocalVoicePalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var tab by remember { mutableStateOf(AppTab.F0) }

    // ---------------- 设备 ----------------
    //
    // 头部要显示"当前输出到哪"，控制条也要据此决定能不能返听。
    var device by remember { mutableStateOf(AudioOutputInfo.current(audioManager)) }

    // **没有外接耳机就不返听**。
    //
    // 手机内置扬声器返听时，麦克风会拾取自己的监听声形成正反馈啸叫，
    // 分析到的也是被延迟污染的声音。与其把音量限到 35% 还留着这个坑，
    // 不如直接不返听——只做音高监测，接上耳机自动恢复。
    val monitoringAvailable = device.kind != AudioOutputInfo.Kind.SPEAKER

    // 实时检测输出设备变化。
    //
    // 系统切换音频路由是异步的（设备回调先到、路由后定），
    // 所以回调里延迟一小会儿再读一次，读到的才是真正生效的设备。
    DisposableEffect(audioManager) {
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        lateinit var scheduleRefresh: () -> Unit
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                scheduleRefresh()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                scheduleRefresh()
            }
        }
        scheduleRefresh = {
            pending?.let { handler.removeCallbacks(it) }
            val r = Runnable {
                pending = null
                device = AudioOutputInfo.current(audioManager)
            }
            pending = r
            handler.postDelayed(r, DEVICE_SETTLE_MS)
        }
        audioManager.registerAudioDeviceCallback(callback, handler)
        onDispose {
            pending?.let { handler.removeCallbacks(it) }
            audioManager.unregisterAudioDeviceCallback(callback)
        }
    }

    // 设备变化后要重新判定能不能返听。运行中切换会用引擎的运行时接口
    // 立刻关掉/开启返听通道——蓝牙耳机被拔掉时必须马上关，
    // 否则声音会从手机扬声器播出来，麦克风再拾取形成啸叫。
    LaunchedEffect(monitoringAvailable) {
        engine.applyMonitoringEnabled(monitoringAvailable)
    }

    // ---------------- 设置项 ----------------
    var showSettings by remember { mutableStateOf(false) }

    // 返听音量 0..2（0%–200%），默认 100%
    var gain by remember { mutableFloatStateOf(1.0f) }
    // 返听降噪强度：只影响耳朵里听到的，不影响曲线
    var noiseLevel by remember { mutableFloatStateOf(DEFAULT_NOISE_LEVEL) }
    // 回放窗口长度（秒）。存进偏好里——用户明确选过的选项，
    // 不该每次开 App 都退回默认。
    var replayWindow by remember {
        mutableIntStateOf(
            Prefs.replayWindowSeconds(context, VoiceEngine.DEFAULT_REPLAY_SECONDS)
        )
    }

    LaunchedEffect(gain) { engine.monitorGain = gain }
    LaunchedEffect(noiseLevel) { engine.noiseReductionLevel = noiseLevel }
    LaunchedEffect(replayWindow) {
        engine.replayWindowSeconds = replayWindow
        Prefs.setReplayWindowSeconds(context, replayWindow)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(palette.backgroundTop, palette.backgroundBottom),
                ),
            )
            // 安全区在根上处理：导航栏要贴着屏幕底边，
            // 但内容不能被状态栏/手势条盖住。
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AppHeader(
            device = device,
            monitoringAvailable = monitoringAvailable,
            onSettings = { showSettings = true },
        )

        Box(Modifier.weight(1f)) {
            when (tab) {
                AppTab.F0 -> MonitorContent(engine = engine)
                AppTab.FORMANTS -> FormantContent(engine = engine)
            }
        }

        ControlBar(engine = engine)

        AppNavBar(current = tab, onSelect = { tab = it })
    }

    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            engine = engine,
            device = device,
            monitoringAvailable = monitoringAvailable,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            noiseLevel = noiseLevel,
            onNoiseLevelChange = { noiseLevel = it },
            gain = gain,
            onGainChange = { gain = it },
            replayWindow = replayWindow,
            onReplayWindowChange = { replayWindow = it },
        )
    }
}

/**
 * 头部：品牌 + 设备状态 + 设置入口。
 *
 * 两个页面共用同一个 —— 之前训练页自己画了一个「元音训练」标题，
 * 和这里的样式对不上，切换时明显跳。
 */
@Composable
private fun AppHeader(
    device: AudioOutputInfo.Device,
    monitoringAvailable: Boolean,
    onSettings: () -> Unit,
) {
    val palette = LocalVoicePalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 标题分两段："Pink" 用品牌粉，"Voice" 保持中性色。
        // 用富文本而不是两段 Text，否则两者的基线会差一点点。
        Text(
            text = androidx.compose.ui.text.buildAnnotatedString {
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = palette.trace,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append("Pink")
                }
                withStyle(
                    androidx.compose.ui.text.SpanStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append("Voice")
                }
            },
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        DeviceChip(device.kind, monitoringAvailable, onSettings)
        IconButton(onClick = onSettings) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 控制条：监听 / 回放。
 *
 * 两个页面共用 —— 麦克风是全局的事，不该每页各画一套。
 */
@Composable
private fun ControlBar(engine: VoiceEngine) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val status by engine.status.collectAsState()
    val running = status is EngineStatus.Running
    val replaying = engine.replaying.collectAsState().value

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    // 回放提示（比如"还没录到东西"），null = 不显示
    var replayHint by remember { mutableStateOf<String?>(null) }
    // 提示显示几秒后自动消失——一直挂着占地方
    LaunchedEffect(replayHint) {
        if (replayHint != null) {
            delay(3000)
            replayHint = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    when {
                        running -> engine.stop()
                        granted -> engine.start()
                        else -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (running) {
                        MaterialTheme.colorScheme.surfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (running) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                ),
            ) {
                if (running) {
                    StopGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    StatusDot(Color.White, 10.dp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        running -> "停止监听"
                        !granted -> "授予麦克风权限"
                        else -> "开始监听"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }

            // 回放：和监听按钮并排。
            //
            // 只在监听中才有意义（没录到东西可放）。
            // 扬声器模式也能用——回放播的是**已经录下的**声音，
            // 不会形成正反馈啸叫（实时返听才有那个问题）。
            if (running) {
                Button(
                    onClick = {
                        // 状态判断交给引擎——UI 看到的 replaying 可能滞后一帧，
                        // 拿它来决定"开始还是停止"会点不动。
                        when (engine.toggleReplay()) {
                            ReplayAction.STARTED -> replayHint = null
                            ReplayAction.STOPPED -> replayHint = null
                            ReplayAction.FAILED -> replayHint = "还没录到声音，稍等一下再试"
                        }
                    },
                    modifier = Modifier
                        .width(84.dp)
                        .height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (replaying) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        contentColor = if (replaying) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Text(
                        text = if (replaying) "■ 停止" else "▶ 回放",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }

        replayHint?.let {
            Text(
                text = it,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        (status as? EngineStatus.Error)?.let { err ->
            Text(
                text = err.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
