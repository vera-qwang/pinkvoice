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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.AudioOutputInfo
import com.vera.pinkvoice.audio.EngineStatus
import com.vera.pinkvoice.audio.LatencyInfo
import com.vera.pinkvoice.audio.NoiseGate
import com.vera.pinkvoice.audio.PitchMath
import com.vera.pinkvoice.Prefs
import com.vera.pinkvoice.audio.ReplayAction
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
/**
 * F0 页的内容：读数卡 + 曲线卡。
 *
 * 头部、控制条、底部导航、设置面板都由根页面 [PinkVoiceApp] 统一管 ——
 * 两个页面共用一套外壳，切换时不会一会儿有标题一会儿没有。
 */
@Composable
fun MonitorContent(
    engine: VoiceEngine,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val status by engine.status.collectAsState()
    val running = status is EngineStatus.Running

    // 频率轴的可见范围：曲线上滑动 / 双指缩放可以调
    val freqRange = rememberFrequencyRange()

    // 音量/降噪/回放窗口由根页面统一管（两个页面共用）。

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
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
            // 卡片分成两段：曲线（占满剩余空间）+ 底部信息条。
            //
            // 之前底部信息是用 `align(BottomStart)` 压在曲线上的——
            // 窄屏（360 dp 那种）上信息条会变高，然后**盖住**曲线的底部。
            // 改成 Column 分段后，结构上就不可能重叠：
            // 曲线在 weight(1f) 的 Box 里，信息条在它下面，各占各的。
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    PitchCurve(
                        history = engine.history,
                        offsetMs = engine.autoOffsetMs.collectAsState().value.toLong(),
                        modifier = Modifier
                            .fillMaxSize()
                            // 下边距给最下面那条音名标签留位：
                            // 标签画在 y-7px 处、字高约 27px，会往下探出绘图区一点。
                            // 只留 2 dp——标签探出去的部分不多，留多了底部空一截。
                            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
                        textMeasurer = textMeasurer,
                        active = running,
                        avgHz = readout.avgHz,
                        range = freqRange,
                    )
                }

                // 底部信息条：频率范围 / 对齐值，一行。
                //
                // 原来这里还有一句图例（"虚线 = 平均音高 · 实线 = 当前音高"），
                // 窄屏放不下会折行，压到曲线上；压短了也还是占地方。
                // 直接删掉——虚线和实线的含义在设置面板里有说明，
                // 曲线本身还有颜色区分（虚线/实线 + 圆点），不至于看不懂。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 与绘图区左缘对齐（卡片内边距 14 + 坐标栏 22）
                        .padding(start = 36.dp, end = 16.dp, top = 0.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 频率范围。点一下回到默认。
                    FrequencyRangeChip(range = freqRange)
                    Text(
                        text = "对齐 ${engine.autoOffsetMs.collectAsState().value.roundToInt()} ms",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
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

}

/** UI 刷新限速：30 fps。屏幕是 120 Hz，但曲线与读数都是慢变量。 */
private const val UI_FRAME_INTERVAL_MS = 33L

/**
 * 设备变化后等多久再读。
 *
 * 系统切换音频路由是异步的：AudioDeviceCallback 先回调，
 * 路由过一会儿才定。读太早会拿到还没切换的旧设备。
 */
internal const val DEVICE_SETTLE_MS = 400L

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
internal fun DeviceChip(
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
internal fun SettingsSheet(
    onDismiss: () -> Unit,
    engine: VoiceEngine,
    device: AudioOutputInfo.Device,
    monitoringAvailable: Boolean,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    noiseLevel: Float,
    onNoiseLevelChange: (Float) -> Unit,
    replayWindow: Int,
    onReplayWindowChange: (Int) -> Unit,
    gain: Float,
    onGainChange: (Float) -> Unit,
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
                text = "曲线上有两条参考线：「虚线」= 窗口平均音高，" +
                    "「实线 + 圆点」= 当前音高。颜色跟着所在音域走（女声区粉、男声区蓝）。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "在曲线上「上下滑动」可以调整可见的频率范围，" +
                    "「双指捏合」可以缩放。默认覆盖 80–270 Hz，" +
                    "也就是男声区和女声区。改过的范围点一下右下角就能复位。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // --- 返听音量 ---
            //
            // 两个滑块（返听音量 / 降噪）用**一样的结构**：
            // 「图标 + 数值」一行，滑块在下面占满整行。
            //
            // 之前返听音量是图标在上、滑块在下，降噪却是数值在左、
            // 滑块在右——两个长得不一样，看着像两个不同的东西。
            SectionLabel("返听音量")
            LabeledSlider(
                value = gain,
                // 返听不可用时滑块置灰不可拖——避免让人以为调了音量有效果
                onValueChange = { if (monitoringAvailable) onGainChange(it) },
                enabled = monitoringAvailable,
                valueText = if (monitoringAvailable) {
                    "${(gain * 100).roundToInt()}%"
                } else {
                    "不可用"
                },
                // 0%–200%。超过 100% 走软件增益（硬件音量封顶 1.0）。
                valueRange = 0f..2f,
            )

            // --- 降噪 ---
            SectionLabel("降噪")
            Text(
                text = "压掉返听里的环境底噪（空调、远处说话、风扇）。" +
                    "只影响耳朵里听到的——曲线和读数仍然是真实的声音，" +
                    "所以降噪不会让轻声唱的音高丢失。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LabeledSlider(
                value = noiseLevel,
                onValueChange = onNoiseLevelChange,
                enabled = true,
                valueText = if (noiseLevel <= 0f) {
                    "关闭"
                } else {
                    "${noiseGateThresholdDb(noiseLevel).roundToInt()} dB"
                },
            )

            // --- 回放 ---
            SectionLabel("回放")
            Text(
                text = "回放最近录下的一段。音频只存在内存里，" +
                    "关掉 App 就没了——不落盘、不上传。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                REPLAY_WINDOW_OPTIONS.forEachIndexed { index, seconds ->
                    SegmentedButton(
                        selected = replayWindow == seconds,
                        onClick = { onReplayWindowChange(seconds) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = REPLAY_WINDOW_OPTIONS.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Text("${seconds}秒")
                    }
                }
            }

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
/**
 * 一条带数值标签的滑块。
 *
 * 两个滑块（返听音量 / 降噪）共用，所以：
 * - 长度一样（数值列定宽，两条对齐）
 * - 样式一样（小圆点拇指，不是 M3 那个竖条）
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LabeledSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    enabled: Boolean,
    valueText: String,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = primary,
                activeTrackColor = primary,
                inactiveTrackColor = track,
                disabledThumbColor = MaterialTheme.colorScheme.outline,
                disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
                disabledInactiveTrackColor = track,
            ),
            // M3 默认的拇指是一条竖线，又高又抢眼——换成小圆点。
            thumb = {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            if (enabled) primary else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ),
                )
            },
            // 默认轨道在拇指两侧各留一段缺口，这里要连续的。
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    colors = SliderDefaults.colors(
                        thumbColor = primary,
                        activeTrackColor = primary,
                        inactiveTrackColor = track,
                        disabledThumbColor = MaterialTheme.colorScheme.outline,
                        disabledActiveTrackColor = MaterialTheme.colorScheme.outline,
                        disabledInactiveTrackColor = track,
                    ),
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                )
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            text = valueText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 定宽：两条滑块才能一样长
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * 降噪的默认门限（dBFS）。
 *
 * -50 dB：比室内底噪（约 -60 dB）高一点，
 * 安静说话不会被切掉，但空调、风扇那种持续底噪会被压下去。
 */
private const val DEFAULT_NOISE_THRESHOLD_DB = -50f

/** 对应 [DEFAULT_NOISE_THRESHOLD_DB] 的降噪强度（0..1）。 */
internal val DEFAULT_NOISE_LEVEL =
    (DEFAULT_NOISE_THRESHOLD_DB - NoiseGate.MIN_THRESHOLD_DB) /
        (NoiseGate.MAX_THRESHOLD_DB - NoiseGate.MIN_THRESHOLD_DB)

/** 回放窗口的可选长度（秒）。 */
private val REPLAY_WINDOW_OPTIONS = intArrayOf(10, 20, 30)

/**
 * 降噪强度对应的门限（dBFS）。
 *
 * 和 [com.vera.pinkvoice.audio.NoiseGate] 里的映射保持一致：
 * 0 → -70 dB（等于不处理），1 → -25 dB。
 */
internal fun noiseGateThresholdDb(level: Float): Float =
    NoiseGate.MIN_THRESHOLD_DB +
        (NoiseGate.MAX_THRESHOLD_DB - NoiseGate.MIN_THRESHOLD_DB) * level.coerceIn(0f, 1f)

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
