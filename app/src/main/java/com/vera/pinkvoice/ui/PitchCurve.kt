package com.vera.pinkvoice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.PitchHistory
import com.vera.pinkvoice.audio.PitchMath
import kotlin.math.ln
import kotlin.math.roundToInt

/** 曲线重绘间隔：20 fps。曲线是慢变量，再快也看不出差别。 */
private const val CURVE_FRAME_INTERVAL_NS = 50_000_000L

/**
 * 实时音高曲线。
 *
 * 纵轴为对数频率轴，横轴为时间；背景色带标出男声 / 女声参考音域，
 * 右侧虚线 + 圆点指示"此刻听到"的音高。
 *
 * **分两层画**：静态层（色带 / 参考线 / 坐标标签）与动态层（曲线本体）。
 * 静态层的内容一帧都不变，却要占掉每帧 5 次文字测量与 5 个图元绘制；
 * 不读 tick 之后它只在自身参数变化时才重绘。
 *
 * @param offsetMs 视觉对齐偏移：曲线整体滞后多少毫秒，用来补偿
 *   "采集 → 播放" 的延迟（蓝牙耳机通常额外 100~300 ms）。
 * @param active 引擎是否在运行。为 false 时停止重绘，避免空闲时白烧 CPU。
 */
@Composable
fun PitchCurve(
    history: PitchHistory,
    offsetMs: Long,
    modifier: Modifier = Modifier,
    windowMs: Long = CURVE_WINDOW_MS,
    /**
     * 频率轴的可见范围。用户可以上下滑动平移、双指缩放来调整它。
     */
    range: FrequencyRange,
    textMeasurer: TextMeasurer,
    active: Boolean = true,
    /**
     * 窗口内的平均音高（Hz），用于画一条水平参考线。
     * null 表示窗口内没有检测到音高，不画。
     */
    avgHz: Float? = null,
) {
    // 重绘心跳（数据本身由引擎线程写入，不走 Compose 状态）
    //
    // 只在引擎运行时跳。停止监听后曲线内容不变（引擎不清空历史），
    // 留着最后一帧即可；继续空转的话，真机实测前台空闲要吃掉 90% CPU——
    // 比真正跑分析还高。
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(active) {
        if (!active) {
            tick.longValue++   // 补最后一帧，保证停机瞬间的状态被画出来
            return@LaunchedEffect
        }
        var last = 0L
        while (true) {
            withFrameNanos {
                // 限速 20 fps。本机屏幕是 120 Hz，不限速就是每秒 120 次重组；
                // 实测 30 fps → 20 fps 省约 12% CPU，曲线平滑度看不出差别。
                if (it - last >= CURVE_FRAME_INTERVAL_NS) {
                    last = it
                    tick.longValue = it
                }
            }
        }
    }

    val palette = LocalVoicePalette.current

    // 绘图区高度。手势回调里拿不到 DrawScope 的尺寸，
    // 所以在这里记一份——平移时要用它换算"拖 1 像素 = 多少频率"。
    val plotHeight = remember { mutableIntStateOf(0) }

    // 上下滑动 = 平移可见频率范围；双指捏合 = 缩放。
    //
    // 用 transformable 而不是手写 pointerInput：它已经处理好了
    // 双指、锚点（捏合时以两指中点为中心）和防抖。
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        range.zoom(zoomChange)
        // panChange 是 Offset，这里只取纵向分量（横向平移对频率轴没意义）
        range.pan(panChange.y, plotHeight.intValue.toFloat())
    }

    // 复用缓冲，避免每帧分配（remember 必须在 composable 层，不能放进 DrawScope）
    val outT = remember { LongArray(4096) }
    val outHz = remember { FloatArray(4096) }
    val outClarity = remember { FloatArray(4096) }
    val outRms = remember { FloatArray(4096) }

    Box(
        modifier
            .transformable(transformState)
            .onSizeChanged { plotHeight.intValue = it.height },
    ) {
        // ---- 静态层：音域色带 / 半音辅助线 / 音名 + 频率 ----
        // 不读 tick，因此只在自身参数变化时才重绘。
        // 再用 graphicsLayer 把它提升成独立硬件层：内容不变时合成器直接复用
        // 上一帧的纹理，不必每帧重新上传。
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            // 范围在**绘制时**读取（而不是作为参数传进来）：
            // 这样滑动只触发重绘，不会引起重组。
            val fMin = range.min
            val fMax = range.max
            val plotLeft = LEFT_AXIS_DP.toPx()
            val plotRight = (size.width - RIGHT_AXIS_DP.toPx()).coerceAtLeast(plotLeft + 1f)
            val plotWidth = plotRight - plotLeft
            drawBands(textMeasurer, plotLeft, plotWidth, size.height, fMin, fMax, palette)

            // 半音网格：每半音一条淡线，看得出当前唱在哪个音级。
            // 落在音域色带里的线要更淡，否则网格会盖住色带本身。
            for (midi in midiRange(fMin, fMax)) {
                val hz = PitchMath.midiToHz(midi).toFloat()
                val y = yFor(hz, size.height, fMin, fMax)
                val isOctave = midi % 12 == 0
                val inBand = hz in MaleRange || hz in FemaleRange
                val alpha = when {
                    isOctave -> if (inBand) 0.55f else 1f
                    inBand -> 0.18f
                    else -> 0.45f
                }
                drawLine(
                    color = palette.grid.copy(alpha = alpha),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = if (isOctave) 1.4f else 1f,
                )
            }

            // 全音标注：**音名在曲线区左边，频率在曲线区右边**。
            //
            // 之前两者都挤在左侧一栏里：要么中间留大片空白，
            // 要么挤成一团。现在左右分栏——左边一列音名贴着左边缘，
            // 右边一列频率贴着右边缘，曲线区在中间。
            // 两个数各自有稳定的对齐边，读起来是一行行"音名 → 频率"。
            //
            // 标签按所在音域着色：女声区粉色、男声区蓝色、其余中性灰。
            for (midi in midiRange(fMin, fMax)) {
                if (!isWholeTone(midi)) continue
                val hz = PitchMath.midiToHz(midi)
                val y = yFor(hz.toFloat(), size.height, fMin, fMax)
                val color = bandColorOf(hz.toFloat(), palette)
                val isOctave = midi % 12 == 0

                drawLabel(
                    textMeasurer = textMeasurer,
                    text = PitchMath.midiToName(midi),
                    x = 0f,
                    y = y - 7f,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = color,
                        fontWeight = if (isOctave) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
                // 频率右对齐到画布右边缘
                val hzText = hz.roundToInt().toString()
                val hzSize = textMeasurer.measure(
                    hzText,
                    TextStyle(fontSize = 9.sp, color = palette.axisLabel),
                ).size
                drawLabel(
                    textMeasurer = textMeasurer,
                    text = hzText,
                    x = size.width - hzSize.width,
                    y = y - 6.5f,
                    style = TextStyle(fontSize = 9.sp, color = palette.axisLabel),
                )
            }
        }

        // ---- 动态层：曲线本体 + 当前音高指示 ----
        Canvas(Modifier.fillMaxSize()) {
            // 依赖 tick，保证每帧重绘
            @Suppress("UNUSED_EXPRESSION")
            tick.longValue

            val fMin = range.min
            val fMax = range.max
            val w = size.width
            val h = size.height
            val plotLeft = LEFT_AXIS_DP.toPx()
            val plotRight = (w - RIGHT_AXIS_DP.toPx()).coerceAtLeast(plotLeft + 1f)
            val plotWidth = plotRight - plotLeft

            val endNs = history.latestTimeNs - offsetMs * 1_000_000L
            val startNs = endNs - windowMs * 1_000_000L

            val n = history.copyRange(startNs, endNs, outT, outHz, outClarity, outRms)
            if (n > 0) {
                val path = Path()
                var started = false
                var lastT = 0L
                for (i in 0 until n) {
                    val t = outT[i]
                    val hz = outHz[i]
                    val cl = outClarity[i]
                    val x = plotLeft + plotWidth *
                        ((t - startNs).toFloat() / (windowMs * 1_000_000f)).coerceIn(0f, 1f)
                    if (hz <= 0f || cl < 0.3f) {
                        started = false
                        continue
                    }
                    val y = yFor(hz, h, fMin, fMax)
                    // 时间上出现大跳变说明中间断了（清音/无声），断开曲线
                    if (started && t - lastT > 120_000_000L) started = false
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                    lastT = t
                }
                // 底下垫一层宽而淡的描边，做出发光感
                drawPath(
                    path = path,
                    color = palette.trace.copy(alpha = 0.18f),
                    style = Stroke(width = 9.dp.toPx()),
                )
                drawPath(
                    path = path,
                    color = palette.trace,
                    style = Stroke(width = 2.6.dp.toPx()),
                )
            }

            // 窗口平均音高参考线：练声时能一眼看出"我整体在多高"
            //
            // 颜色跟着它所在的音域走：平均音高落在女声区就是粉色，
            // 落在男声区就是蓝色。这样虚线和背景色带、左侧音名、
            // 右上角的大音名是同一套语义，不用再解释"这个蓝是什么意思"。
            if (avgHz != null && avgHz > 0f) {
                val y = yFor(avgHz, h, fMin, fMax)
                drawLine(
                    color = bandColorOf(avgHz, palette).copy(alpha = 0.55f),
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1.2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(10f, 6f),
                        phase = 0f,
                    ),
                )
            }

            // 右侧当前音高指示（取"此刻听到"的那一点）
            //
            // 用**实线** + 圆点，和平均音高的虚线区分开——
            // 两条都是虚线的时候很容易看混，图例只解释了一条。
            // 颜色跟着当前音高所在的音域走。
            val heard = history.sampleAt(endNs)
            if (heard != null && heard.hz > 0f && heard.clarity >= 0.3f) {
                val y = yFor(heard.hz, h, fMin, fMax)
                val ink = bandColorOf(heard.hz, palette)
                drawLine(
                    color = ink,
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1.4.dp.toPx(),
                )
                drawCircle(
                    ink,
                    radius = 5.dp.toPx(),
                    center = Offset(plotRight - 6.dp.toPx(), y),
                )
            }
        }
    }
}

/**
 * 音域标签的纵向位置。
 *
 * @param bandStart 音域下限（Hz）
 * @param bandEnd   音域上限（Hz）
 * @return 标签的 y 坐标；音域完全不在可见范围内时返回 null（不画标签）
 */
internal fun bandLabelY(
    bandStart: Float,
    bandEnd: Float,
    fMin: Float,
    fMax: Float,
    height: Float,
): Float? {
    // 只画和可见范围有交集的那一段
    val visStart = maxOf(fMin, bandStart)
    val visEnd = minOf(fMax, bandEnd)
    if (visEnd <= visStart) return null
    // 交集的高频端在上方
    return yFor(visEnd, height, fMin, fMax)
}

private fun DrawScope.drawBands(
    textMeasurer: TextMeasurer,
    left: Float,
    width: Float,
    height: Float,
    fMin: Float,
    fMax: Float,
    palette: VoicePalette,
) {
    // 女声区
    val femaleTop = yFor(FemaleRange.endInclusive, height, fMin, fMax)
    val femaleBottom = yFor(FemaleRange.start, height, fMin, fMax)
    drawRect(
        color = palette.femaleBand,
        topLeft = Offset(left, femaleTop),
        size = Size(width, femaleBottom - femaleTop),
    )
    // 男声区
    val maleTop = yFor(MaleRange.endInclusive, height, fMin, fMax)
    val maleBottom = yFor(MaleRange.start, height, fMin, fMax)
    drawRect(
        color = palette.maleBand,
        topLeft = Offset(left, maleTop),
        size = Size(width, maleBottom - maleTop),
    )
    // 标签只在音域**可见**时画。
    //
    // 之前是无条件画，位置取自 yFor(音域边界)。而 yFor 会把频率钳到
    // [fMin, fMax]，所以范围滑到男声区以下时，"女声"标签会被放到画布
    // **下方**——drawText 拿「画布尺寸 − 位置」当布局约束，算出负的
    // maxHeight，直接抛 IllegalArgumentException 把 App 干翻。
    bandLabelY(FemaleRange.start, FemaleRange.endInclusive, fMin, fMax, height)?.let { labelY ->
        drawLabel(
            textMeasurer = textMeasurer,
            text = "女声",
            x = left + 8.dp.toPx(),
            y = labelY + 5.dp.toPx(),
            style = TextStyle(
                fontSize = 10.sp,
                color = palette.trace.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
            ),
        )
    }
    bandLabelY(MaleRange.start, MaleRange.endInclusive, fMin, fMax, height)?.let { labelY ->
        drawLabel(
            textMeasurer = textMeasurer,
            text = "男声",
            x = left + 8.dp.toPx(),
            y = labelY + 5.dp.toPx(),
            style = TextStyle(
                fontSize = 10.sp,
                color = palette.primaryInk.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/**
 * 画一段文字，位置自动夹在画布内。
 *
 * drawText 会用「画布尺寸 − 文字位置」当布局约束，位置越界就会算出
 * 负的 maxWidth/maxHeight，Compose 直接抛异常。夹一下就不会。
 */
private fun DrawScope.drawLabel(
    textMeasurer: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
) {
    val safeX = x.coerceIn(0f, (size.width - 1f).coerceAtLeast(0f))
    val safeY = y.coerceIn(0f, (size.height - 1f).coerceAtLeast(0f))
    drawText(
        textMeasurer = textMeasurer,
        text = text,
        topLeft = Offset(safeX, safeY),
        style = style,
    )
}

/**
 * 坐标栏宽度：音名在曲线区左边，频率在曲线区右边。
 *
 * 左栏只放音名（"C4" 两个字符，10 sp 约 18 dp），
 * 右栏只放频率（三位数，9 sp 约 20 dp）。
 */
private val LEFT_AXIS_DP = 22.dp
private val RIGHT_AXIS_DP = 26.dp

/** 自然音的半音偏移（C D E F G A B）。 */
private val NATURAL_PITCH_CLASSES = intArrayOf(0, 2, 4, 5, 7, 9, 11)

private fun isWholeTone(midi: Int): Boolean = midi % 12 in NATURAL_PITCH_CLASSES

/** 落在 [fMin, fMax] 内的所有半音的 MIDI 号。 */
private fun midiRange(fMin: Float, fMax: Float): IntRange {
    val lo = kotlin.math.ceil(PitchMath.hzToMidi(fMin.toDouble())).toInt()
    val hi = kotlin.math.floor(PitchMath.hzToMidi(fMax.toDouble())).toInt()
    return lo..hi
}

/**
 * 某个频率所在音域的标签色。
 *
 * 女声区优先判定：分界线 165 Hz 归粉色区，所以 E3（164.8 Hz）算蓝色、
 * F3（174.6 Hz）算粉色，与色带画出来的边界一致。
 *
 * 坐标轴刻度和读数卡的大音名共用这个判定，两处颜色永远一致。
 */
internal fun bandColorOf(hz: Float, palette: VoicePalette): Color = when {
    hz in FemaleRange -> palette.trace
    hz in MaleRange -> palette.primaryInk
    else -> palette.axisLabel
}

/** 对数频率轴：频率越高越靠上。 */
internal fun yFor(hz: Float, height: Float, fMin: Float, fMax: Float): Float {
    val l = ln(hz.coerceIn(fMin, fMax).toDouble())
    val t = ((l - ln(fMin.toDouble())) / (ln(fMax.toDouble()) - ln(fMin.toDouble()))).toFloat()
    return height - t * height
}
