package com.vera.pinkvoice.ui

import android.Manifest
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Handler
import android.os.Looper
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.AudioOutputInfo
import com.vera.pinkvoice.audio.EngineStatus
import com.vera.pinkvoice.audio.LatencyInfo
import com.vera.pinkvoice.audio.PitchMath
import com.vera.pinkvoice.audio.VoiceEngine
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

private data class Readout(
    val note: String = "--",
    val cents: Float = 0f,
    val hz: Float = 0f,
    val level: Float = 0f,
    val voiced: Boolean = false,
    /** 窗口内的平均音高（Hz），无则 null。 */
    val avgHz: Float? = null,
    /** 窗口内的平均响度（dBFS）。 */
    val avgDb: Float = -60f,
    /** 窗口内的峰值响度（dBFS）。 */
    val peakDb: Float = -60f,
)

/**
 * 主界面。
 *
 * 信息层级（从上到下）：
 *   1. 状态条   —— 运行与否、当前输出设备、设置入口
 *   2. 读数卡   —— 歌手最需要的信息：音名 + 音分偏差（带指针表）
 *   3. 曲线卡   —— 实时音高轨迹 + 男/女声参考音域带
 *   4. 控制条   —— 开始/停止 + 返听音量
 * 调参类（对齐补偿、延迟明细、外放提示）收进底部设置面板，不占主界面空间。
 */
@Composable
fun VoiceScreen(
    engine: VoiceEngine,
    themeMode: ThemeMode = ThemeMode.System,
    onThemeModeChange: (ThemeMode) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val status by engine.status.collectAsState()
    val running = status is EngineStatus.Running

    var granted by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    var gain by remember { mutableFloatStateOf(0.55f) }
    // 频率轴的可见范围：曲线上滑动 / 双指缩放可以调
    val freqRange = rememberFrequencyRange()
    var showSettings by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf(AudioOutputInfo.current(audioManager)) }

    // **没有外接耳机就不返听**。
    //
    // 手机内置扬声器返听时，麦克风会拾取自己的监听声形成正反馈啸叫，
    // 分析到的也是被延迟污染的声音。与其把音量限到 35% 还留着这个坑，
    // 不如直接不返听——只做音高监测，接上耳机自动恢复。
    val monitoringAvailable = device.kind != AudioOutputInfo.Kind.SPEAKER

    // **实时检测输出设备变化**。
    //
    // 之前只在启动监听时读一次设备，于是插上/拔掉耳机都要重启 App 才能生效。
    // AudioDeviceCallback 会在设备增删时回调，这里据此刷新。
    //
    // 系统切换音频路由是异步的（设备回调先到、路由后定），所以回调里
    // 延迟一小会儿再读一次，读到的才是真正生效的设备。
    DisposableEffect(audioManager) {
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        // 局部函数要声明在匿名对象之前才能被它引用
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
    LaunchedEffect(gain) { engine.monitorGain = gain }

    // 视觉对齐量 = 输出延迟 - 分析延迟（推导见 README），蓝牙附加部分手动补
    //
    // 延迟是诊断值，却在界面顶层被 collect —— 每次发布都会让整屏重组
    // （读数卡 + 曲线卡 + 控制条）。这里只在设置面板打开时才订阅它，
    // 主界面对齐量则由引擎以低频（8 Hz）单独发布。
    // 视觉对齐量只在两处用到：曲线的时间轴偏移，和右下角那行"对齐 X ms"。
    //
    // 引擎每 8 Hz 发布一次这个值。如果在顶层订阅，整屏会跟着重组 8 次/秒
    // ——真机实测这一项就占 17% CPU。所以订阅点下沉到真正需要它的子组件：
    // 曲线自己订阅（只重绘），文字标签自己订阅（只重组那一行）。

    val textMeasurer = rememberTextMeasurer()
    val palette = LocalVoicePalette.current

    // 读数：采样"此刻听到"的那个采样点。
    // 限速到 UI_FRAME_INTERVAL_MS——本机屏幕是 120 Hz，withFrameNanos 会按
    // 120 Hz 唤醒，而读数是慢变量，30 fps 完全够用。
    var readout by remember { mutableStateOf(Readout()) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            delay(UI_FRAME_INTERVAL_MS)
            run {
                val offsetMs = engine.autoOffsetMs.value
                val endNs =
                    engine.history.latestTimeNs - offsetMs.toLong() * 1_000_000L
                val s = engine.history.sampleAt(endNs)
                val next = if (s == null || s.hz <= 0f || s.clarity < 0.3f) {
                    Readout()
                } else {
                    val midi = PitchMath.hzToMidiInt(s.hz.toDouble())
                    Readout(
                        note = PitchMath.midiToName(midi),
                        cents = PitchMath.centsOffNearest(s.hz.toDouble()).toFloat(),
                        hz = s.hz,
                        level = rmsToRatio(s.rms),
                        voiced = true,
                    )
                }
                // 窗口统计：平均音高与响度。窗口和曲线看到的是同一段，
                // 所以"曲线上的平均线"和"读数里的平均"永远对得上。
                val windowStart = endNs - CURVE_WINDOW_MS * 1_000_000L
                val stats = engine.history.statsIn(windowStart, endNs)
                val nextWithStats = next.copy(
                    avgHz = stats.avgHz,
                    avgDb = rmsToDb(stats.avgRms),
                    peakDb = rmsToDb(stats.peakRms),
                )

                val cur = readout
                if (nextWithStats.note != cur.note ||
                    abs(nextWithStats.cents - cur.cents) > 2f ||
                    nextWithStats.voiced != cur.voiced ||
                    abs(nextWithStats.level - cur.level) > 0.01f ||
                    nextWithStats.avgHz != cur.avgHz ||
                    abs(nextWithStats.avgDb - cur.avgDb) > 0.3f
                ) {
                    readout = nextWithStats
                }
            }
        }
    }

    // 运行时保持屏幕常亮
    DisposableEffect(running) {
        if (running) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(palette.backgroundTop, palette.backgroundBottom),
                ),
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------------- 状态条 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 标题分两段："Pink" 用品牌粉，"Voice" 保持中性色。
            // 用富文本而不是两段 Text，否则两者的基线会差一点点。
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = palette.trace,
                            fontWeight = FontWeight.Bold,
                        ),
                    ) {
                        append("Pink")
                    }
                    withStyle(
                        SpanStyle(
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
            DeviceChip(device.kind, monitoringAvailable) { showSettings = true }
            IconButton(onClick = { showSettings = true }) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---------------- 读数卡 ----------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.cardBorder),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (readout.voiced) readout.note else "—",
                        fontSize = 50.sp,
                        fontWeight = FontWeight.Bold,
                        // 大音名按当前音高所在音域着色：唱到女声区就是粉色，
                        // 在男声区是蓝色，两个音域之外是中性灰。
                        // 坐标轴刻度用的是同一个判定，两处颜色永远对得上。
                        color = if (readout.voiced) {
                            bandColorOf(readout.hz, palette)
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = if (readout.voiced) "${readout.hz.roundToInt()} Hz" else "静音",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (readout.voiced) {
                                "${if (readout.cents >= 0) "+" else ""}${readout.cents.roundToInt()} cent"
                            } else {
                                "未检出音高"
                            },
                            fontSize = 13.sp,
                            color = centsColor(readout.cents, readout.voiced, palette),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    LevelMeter(readout.level)
                }
                CentsGauge(
                    cents = readout.cents,
                    voiced = readout.voiced,
                    textMeasurer = textMeasurer,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 窗口统计：平均音高 + 平均响度 + 峰值。
                // 三格并排放在读数卡底部——都是"这段时间的整体情况"，
                // 放在一起读起来是一组指标；曲线上那条虚线是平均音高的图形表示。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatChip(
                        label = "平均音高",
                        value = readout.avgHz?.let { "${it.roundToInt()} Hz" } ?: "—",
                    )
                    StatChip(
                        label = "平均响度",
                        value = "${readout.avgDb.roundToInt()} dB",
                    )
                    StatChip(
                        label = "峰值",
                        value = "${readout.peakDb.roundToInt()} dB",
                    )
                }
            }
        }

        // ---------------- 曲线卡 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.cardBorder),
        ) {
            Box(Modifier.fillMaxSize()) {
                PitchCurve(
                    history = engine.history,
                    offsetMs = engine.autoOffsetMs.collectAsState().value.toLong(),
                    modifier = Modifier
                        .fillMaxSize()
                        // 下边距要给最下面那条音名标签留出空间：
                        // 标签画在 y-7px 处，字高约 27px，会往下探出绘图区一点，
                        // 留 26 dp 会被底部那一行切掉半截。
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 34.dp),
                    textMeasurer = textMeasurer,
                    active = running,
                    avgHz = readout.avgHz,
                    range = freqRange,
                )
                // 底部一行：图例 / 频率范围 / 对齐值。
                //
                // 三样东西都在这一行里——之前"对齐"单独放在 BottomEnd，
                // 一旦频率范围被调高，最下面那条频率标签正好和它撞上。
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        // 与绘图区左缘对齐（卡片内边距 14 + 坐标栏 22）
                        .padding(start = 36.dp, end = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 图例：说明那条虚线是窗口平均音高——数值本身在读数卡里，
                    // 这里只给一个图例，免得用户对着虚线猜它是什么。
                    Text(
                        text = "虚线 = 平均音高 · 实线 = 当前音高",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // 频率范围。点一下回到默认。
                    FrequencyRangeChip(range = freqRange)
                    Text(
                        text = "对齐 ${engine.autoOffsetMs.collectAsState().value.roundToInt()} ms",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------------- 控制条 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        running -> "停止监听"
                        !granted -> "授予麦克风权限"
                        monitoringAvailable -> "开始监听 + 返听"
                        else -> "开始监听"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // 返听音量
            Column(
                modifier = Modifier.width(136.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeakerGlyph(color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (monitoringAvailable) {
                            "返听 ${(gain * 100).roundToInt()}%"
                        } else {
                            "返听不可用"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Slider(
                    value = gain,
                    // 返听不可用时滑块置灰不可拖——避免让人以为调了音量有效果
                    onValueChange = { if (monitoringAvailable) gain = it },
                    valueRange = 0f..1f,
                    enabled = monitoringAvailable,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.height(28.dp),
                )
            }
        }

        (status as? EngineStatus.Error)?.let { err ->
            Text(
                text = err.message,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (showSettings) {
        SettingsSheet(
            onDismiss = { showSettings = false },
            engine = engine,
            device = device,
            monitoringAvailable = monitoringAvailable,
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
        )
    }
}

/** UI 刷新限速：30 fps。屏幕是 120 Hz，但曲线与读数都是慢变量。 */
private const val UI_FRAME_INTERVAL_MS = 33L

/**
 * 设备变化后等多久再读。
 *
 * 系统切换音频路由是异步的：AudioDeviceCallback 先回调，
 * 路由过一会儿才定。读太早会拿到还没切换的旧设备。
 */
private const val DEVICE_SETTLE_MS = 400L

/**
 * 曲线窗口时长（ms）。
 *
 * 曲线和"窗口平均"必须用同一个窗口，否则两个数对不上——
 * 曲线上的平均线会和读数里显示的平均频率不在同一段数据上。
 */
const val CURVE_WINDOW_MS = 6000L

/** 主题模式的中文名。 */
val ThemeMode.displayName: String
    get() = when (this) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
    }

/**
 * 输出设备芯片。
 *
 * 用内置扬声器时显示"仅监测"——返听已经关掉了，这是状态而不是错误，
 * 所以用中性色而不是红色警示。接上耳机就变回"蓝牙" / "有线"。
 */
@Composable
private fun DeviceChip(
    kind: AudioOutputInfo.Kind,
    monitoringAvailable: Boolean,
    onClick: () -> Unit,
) {
    val label = when (kind) {
        AudioOutputInfo.Kind.BLUETOOTH -> "蓝牙"
        AudioOutputInfo.Kind.WIRED -> "有线"
        AudioOutputInfo.Kind.SPEAKER -> "仅监测"
        AudioOutputInfo.Kind.OTHER -> "未知"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!monitoringAvailable) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    onDismiss: () -> Unit,
    engine: VoiceEngine,
    device: AudioOutputInfo.Device,
    monitoringAvailable: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val palette = LocalVoicePalette.current
    // 只有设置面板打开时才订阅延迟明细——面板是折叠的，不该让主界面为它重组
    val latency by engine.latency.collectAsState()
    val dropped by engine.droppedBytes.collectAsState()
    val autoOffsetMs by engine.autoOffsetMs.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "设置",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // --- 外观 ---
            SectionLabel("外观")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text(mode.displayName)
                    }
                }
            }

            // --- 频率范围 ---
            SectionLabel("频率范围")
            Text(
                text = "在曲线上**上下滑动**可以调整可见的频率范围，" +
                    "**双指捏合**可以缩放。默认覆盖 80–270 Hz，" +
                    "也就是男声区和女声区。改过的范围点一下右下角就能复位。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- 视觉对齐 ---
            SectionLabel("视觉对齐")
            Text(
                text = "曲线会自动补偿输出延迟，让它和耳朵听到的同步。" +
                    "蓝牙耳机这段延迟系统测得到（实测约 390 ms），已自动计入。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "当前对齐 ${autoOffsetMs.roundToInt()} ms（输出延迟 − 分析延迟）",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- 延迟明细 ---
            SectionLabel("延迟明细")
            DetailRow("采集（麦克风 → App）", "${latency.inputMs.roundToInt()} ms")
            DetailRow("输出（App → 扬声器）", "${latency.outputMs.roundToInt()} ms")
            DetailRow("分析（FIR + YIN）", "${latency.analysisMs.roundToInt()} ms")
            DetailRow("当前输出设备", device.name)
            DetailRow("返听丢样", "${dropped / 2} 帧")

            // --- 未接耳机提示 ---
            if (!monitoringAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(palette.meterTrack.copy(alpha = 0.45f))
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "没接耳机，已关闭返听：手机扬声器会把麦克风的声音" +
                            "再播回来，形成啸叫。当前只做音高监测。" +
                            "接上耳机或蓝牙耳机后自动恢复返听。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "麦克风音频只在设备内存中流转，不落盘、不上传。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 读数卡里的一格统计（标签 + 数值）。 */
@Composable
private fun StatChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** RMS 转成 0..1 的显示比例（-60dB..-10dB 映射到 0..1）。 */
private fun rmsToRatio(rms: Float): Float {
    val db = 20f * log10(max(rms, 1e-6f))
    return ((db + 60f) / 50f).coerceIn(0f, 1f)
}

/**
 * RMS 转成 dBFS。
 *
 * 底噪在 -60 dB 附近（真机实测静音时 rms ≈ 0.0009 → -61 dB），
 * 所以下限取 -60 dB：再往下都是底噪，显示出来没有意义。
 */
private fun rmsToDb(rms: Float): Float =
    (20f * log10(max(rms, 1e-6f))).coerceAtLeast(-60f)

private fun centsColor(cents: Float, voiced: Boolean, palette: VoicePalette): Color {
    if (!voiced) return palette.axisLabel
    return when {
        abs(cents) <= 10f -> palette.inTune
        abs(cents) <= 25f -> palette.nearTune
        else -> palette.offTune
    }
}
