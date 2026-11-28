package com.vera.pinkvoice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.FeminineTarget
import com.vera.pinkvoice.audio.FormantHistory
import com.vera.pinkvoice.audio.VowelTarget
import kotlin.math.ln
import kotlin.math.pow

/** 轨迹的时间窗口（毫秒）。够看清稳不稳，又不至于挤。 */
private const val TRACE_WINDOW_MS = 8000L

/** 轨迹重绘间隔：20 fps。曲线是慢变量，再快也看不出差别。 */
private const val TRACE_FRAME_INTERVAL_NS = 50_000_000L

/**
 * F1/F2 轨迹图。
 *
 * ## 为什么是「时间序列」而不是元音图
 *
 * 之前这一页画的是**元音图**（F1-F2 散点 + 目标区域云）。
 * 那个设计有几个实际问题：
 *
 * 1. **看不出稳不稳。** 训练要练的就是「保持」——
 *    散点图上只有一个点，稳不稳全靠用户盯着数字猜。
 *    波形是一条线，平不平一眼就看出来。
 * 2. **六个目标区域叠在一起是浆糊。** 每个元音一个椭圆，
 *    在窄屏上互相重叠，用户没法判断自己在哪个区域里。
 *    改成「每个轴一条带」就清楚多了。
 * 3. **引入了错误的因果。** 一个点 + 一个黑点，
 *    很容易读成「落在点上=练好了」——
 *    而目标区本来就是一片分布，不是一个点。
 * 4. **和 F0 页的交互模型不一致。** 那边是「横轴=时间」的曲线，
 *    这边是散点，用户要学两套读图方式。
 *
 * ## 画什么
 *
 * - **上下两条带**：F1（上）、F2（下），横轴都是时间
 * - **横向的阴影带**：当前目标元音的目标区（±120 音分）
 * - **曲线**：最近 8 秒的 F1/F2
 * - **右端圆点**：当前值
 *
 * ## 纵轴用对数刻度
 *
 * 这样两个轴的**跨度一样宽**（都是 2.6 个八度），
 * 目标带在两条带上**一样高** —— 用户可以直接用眼睛比
 * 「我离带子还差多少」，不用在脑子里换算 Hz。
 */
@Composable
fun FormantTrace(
    /** 共振峰历史。 */
    history: FormantHistory,
    /** 当前目标元音；null 就不画目标带。 */
    target: VowelTarget?,
    /** 当前显示的 F1（Hz），0 表示没有有效数据。 */
    f1: Float,
    /** 当前显示的 F2（Hz）。 */
    f2: Float,
    /** 现在是否在目标区内。 */
    inZone: Boolean,
    /** 引擎是否在运行。停了就不重绘，别白烧 CPU。 */
    active: Boolean = true,
    modifier: Modifier = Modifier,
    windowMs: Long = TRACE_WINDOW_MS,
) {
    val palette = LocalVoicePalette.current
    val measurer = rememberTextMeasurer()

    // 曲线在滚动，必须每帧重绘。
    //
    // 限速 20 fps —— 本机屏幕是 120 Hz，不限速就是每秒 120 次重组。
    // 和 PitchCurve 用同一个间隔。
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) {
            tick.longValue++
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos {
                if (it - last >= TRACE_FRAME_INTERVAL_NS) {
                    last = it
                    tick.longValue++
                }
            }
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // 依赖 tick，保证每帧重绘
            @Suppress("UNUSED_EXPRESSION")
            tick.longValue

            val padL = 34.dp.toPx()
            val padR = 8.dp.toPx()
            val padT = 6.dp.toPx()
            val padB = 16.dp.toPx()
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            if (w <= 0f || h <= 0f) return@Canvas

            val stripH = (h - 10.dp.toPx()) / 2f
            val f1Top = padT
            val f2Top = padT + stripH + 10.dp.toPx()

            // ---- 取历史 ----
            val cap = 1024
            val times = LongArray(cap)
            val v1 = FloatArray(cap)
            val v2 = FloatArray(cap)
            val endNs = history.latestTimeNs
            val startNs = endNs - windowMs * 1_000_000L
            val n = history.copyRange(startNs, endNs, times, v1, v2)

            drawStrip(
                top = f1Top, height = stripH,
                label = "F1",
                minHz = F1_MIN, maxHz = F1_MAX,
                values = v1, count = n, times = times,
                startNs = startNs, endNs = endNs,
                bandCenter = target?.let { FeminineTarget.centerF1(it) },
                bandRadius = target?.let { FeminineTarget.radiusF1(it) },
                currentHz = f1,
                inZone = inZone,
                palette = palette,
                measurer = measurer,
            )
            drawStrip(
                top = f2Top, height = stripH,
                label = "F2",
                minHz = F2_MIN, maxHz = F2_MAX,
                values = v2, count = n, times = times,
                startNs = startNs, endNs = endNs,
                bandCenter = target?.let { FeminineTarget.centerF2(it) },
                bandRadius = target?.let { FeminineTarget.radiusF2(it) },
                currentHz = f2,
                inZone = inZone,
                palette = palette,
                measurer = measurer,
            )

            // ---- 时间轴 ----
            val axisY = f2Top + stripH
            drawLine(
                color = palette.grid.copy(alpha = 0.25f),
                start = Offset(padL, axisY),
                end = Offset(padL + w, axisY),
                strokeWidth = 1.dp.toPx(),
            )
            drawText(
                measurer,
                "−${windowMs / 1000}秒",
                topLeft = Offset(padL, axisY + 1.dp.toPx()),
                style = TextStyle(fontSize = 9.sp, color = palette.axisLabel),
            )
            drawText(
                measurer,
                "现在",
                topLeft = Offset(
                    (padL + w - measurer.measure("现在", TextStyle(fontSize = 9.sp)).size.width)
                        .coerceAtLeast(padL),
                    axisY + 1.dp.toPx(),
                ),
                style = TextStyle(fontSize = 9.sp, color = palette.axisLabel),
            )
        }
    }
}

/** F1 轴范围（Hz）。 */
private const val F1_MIN = 200f
private const val F1_MAX = 1214f

/** F2 轴范围（Hz）。和 F1 一样是 2.6 个八度 —— 目标带在两条带上一样高。 */
private const val F2_MIN = 600f
private const val F2_MAX = 3643f

/**
 * 画一条轨迹带。
 */
private fun DrawScope.drawStrip(
    top: Float,
    height: Float,
    label: String,
    minHz: Float,
    maxHz: Float,
    values: FloatArray,
    count: Int,
    times: LongArray,
    startNs: Long,
    endNs: Long,
    bandCenter: Float?,
    bandRadius: Float?,
    currentHz: Float,
    inZone: Boolean,
    palette: VoicePalette,
    measurer: TextMeasurer,
) {
    val left = 34.dp.toPx()
    val w = size.width - left - 8.dp.toPx()
    if (w <= 0f || height <= 0f) return

    // 对数刻度
    val lo = ln(minHz.toDouble())
    val hi = ln(maxHz.toDouble())

    fun yOf(hz: Float): Float {
        if (hz <= 0f) return Float.NaN
        val v = (ln(hz.toDouble()) - lo) / (hi - lo)
        return top + height - v.toFloat() * height
    }

    fun xOf(t: Long): Float {
        if (endNs <= startNs) return left + w
        val v = (t - startNs).toDouble() / (endNs - startNs).toDouble()
        return left + v.toFloat() * w
    }

    // ---- 背景 ----
    drawRect(
        color = palette.grid.copy(alpha = 0.06f),
        topLeft = Offset(left, top),
        size = Size(w, height),
    )

    // ---- 目标带 ----
    if (bandCenter != null && bandRadius != null && bandCenter > 0f) {
        val yTop = yOf(bandCenter + bandRadius)
        val yBot = yOf(bandCenter - bandRadius)
        val tint = if (inZone) palette.inTune else palette.trace
        drawRect(
            color = tint.copy(alpha = if (inZone) 0.22f else 0.12f),
            topLeft = Offset(left, yTop),
            size = Size(w, (yBot - yTop).coerceAtLeast(2f)),
        )
        // 上下边界
        drawLine(
            color = tint.copy(alpha = if (inZone) 0.9f else 0.45f),
            start = Offset(left, yTop),
            end = Offset(left + w, yTop),
            strokeWidth = 1.dp.toPx(),
        )
        drawLine(
            color = tint.copy(alpha = if (inZone) 0.9f else 0.45f),
            start = Offset(left, yBot),
            end = Offset(left + w, yBot),
            strokeWidth = 1.dp.toPx(),
        )
        // 中心线（虚线，和 PitchCurve 一个画法）
        val yMid = yOf(bandCenter)
        drawLine(
            color = tint.copy(alpha = 0.5f),
            start = Offset(left, yMid),
            end = Offset(left + w, yMid),
            strokeWidth = 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 5.dp.toPx()),
            ),
        )
        // 目标中心频率标在带的右端
        drawText(
            measurer,
            "${bandCenter.toInt()}",
            topLeft = Offset(
                (left + w - measurer.measure("${bandCenter.toInt()}", TextStyle(fontSize = 9.sp)).size.width)
                    .coerceAtLeast(left),
                yMid - 6.dp.toPx(),
            ),
            style = TextStyle(fontSize = 9.sp, color = tint.copy(alpha = 0.85f)),
        )
    }

    // ---- 曲线 ----
    if (count >= 2) {
        val path = Path()
        var started = false
        for (i in 0 until count) {
            val v = values[i]
            if (v <= 0f) { started = false; continue }
            val x = xOf(times[i])
            val y = yOf(v)
            if (y.isNaN()) { started = false; continue }
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        clipRect(left, top, left + w, top + height) {
            drawPath(
                path = path,
                color = palette.trace.copy(alpha = 0.95f),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }

    // ---- 当前值 ----
    if (currentHz > 0f) {
        val y = yOf(currentHz)
        if (!y.isNaN()) {
            val dotColor = if (inZone) palette.inTune else palette.primaryInk
            drawCircle(dotColor.copy(alpha = 0.18f), 9.dp.toPx(), Offset(left + w, y))
            drawCircle(dotColor, 4.dp.toPx(), Offset(left + w, y))
        }
    }

    // ---- 纵轴标签 ----
    drawText(
        measurer,
        label,
        topLeft = Offset(0f, top + height / 2 - 6.dp.toPx()),
        style = TextStyle(fontSize = 11.sp, color = palette.axisLabel),
    )
    // 范围刻度（只标上下两端，中间不标 —— 免得挤）
    drawText(
        measurer,
        "${maxHz.toInt()}",
        topLeft = Offset(0f, top - 2.dp.toPx()),
        style = TextStyle(fontSize = 8.sp, color = palette.axisLabel.copy(alpha = 0.7f)),
    )
    drawText(
        measurer,
        "${minHz.toInt()}",
        topLeft = Offset(0f, top + height - 8.dp.toPx()),
        style = TextStyle(fontSize = 8.sp, color = palette.axisLabel.copy(alpha = 0.7f)),
    )
}
