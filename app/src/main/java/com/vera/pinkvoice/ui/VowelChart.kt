package com.vera.pinkvoice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.FeminineTarget
import com.vera.pinkvoice.audio.VowelTarget
import kotlin.math.roundToInt

/**
 * 元音图。
 *
 * 横轴 F2（越往右舌位越前）、纵轴 F1（越往下嘴张得越开）。
 * 这是语音学里标准的元音图坐标系。
 *
 * ## 显示什么
 *
 * - **灰色小点**：所有目标元音的位置（/a/ /i/ /u/ …）
 * - **蓝色大点**：你现在的位置
 * - **粉色轨迹**：最近几秒走过的路径（能看出你从哪个元音滑到哪个）
 *
 * ## 坐标是归一化过的
 *
 * 折算到 17.5 cm 标准声道，所以不同人的图可以对齐。
 * 没校准声道长度之前，图是按标准值画的 ——
 * 对男性基本准，对女性会整体偏高。
 */
@Composable
fun VowelChart(
    /** 当前归一化 F1（Hz），0 表示没有有效数据。 */
    f1: Float,
    /** 当前归一化 F2（Hz）。 */
    f2: Float,
    /** 最近的轨迹点（归一化坐标）。 */
    trail: List<Pair<Float, Float>>,
    /** 识别出的元音，用来高亮。 */
    recognized: VowelTarget?,
    /** 当前目标元音，画成靶心。 */
    target: VowelTarget?,
    /** 当前是否在目标区内（靶心高亮）。 */
    inZone: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = palette.axisLabel)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val padL = 30.dp.toPx()
            val padR = 12.dp.toPx()
            val padT = 10.dp.toPx()
            val padB = 22.dp.toPx()
            val w = size.width - padL - padR
            val h = size.height - padT - padB
            if (w <= 0f || h <= 0f) return@Canvas

            // F1 纵向范围 200–1000，F2 横向范围 600–3200
            //
            // F2 上限取 3200 而不是 3000：/i/ 的女性化目标中心在 2748，
            // 区域半径 330 —— 画到 3000 的话会有一半在图外。
            fun xOf(f2Hz: Float) = padL + (f2Hz - 600f) / (3200f - 600f) * w
            fun yOf(f1Hz: Float) = padT + (1000f - f1Hz) / (1000f - 200f) * h

            // 网格：F1 500 / 1000，F2 1000 / 2000
            //
            // palette.grid 是**不透明**的（浅色主题下几乎是黑），
            // 强度要靠 alpha 控制 —— 直接用会把图画成一片黑线。
            val gridColor = palette.grid.copy(alpha = 0.14f)
            for (g in intArrayOf(500, 1000)) {
                val y = yOf(g.toFloat())
                drawLine(gridColor, Offset(padL, y), Offset(padL + w, y), 1.dp.toPx())
                drawText(
                    measurer,
                    "$g",
                    topLeft = Offset(0f, y - 6.dp.toPx()),
                    style = labelStyle,
                )
            }
            for (g in intArrayOf(1000, 2000, 3000)) {
                val x = xOf(g.toFloat())
                drawLine(gridColor, Offset(x, padT), Offset(x, padT + h), 1.dp.toPx())
                drawText(
                    measurer,
                    "$g",
                    topLeft = Offset(x - 8.dp.toPx(), padT + h + 4.dp.toPx()),
                    style = labelStyle,
                )
            }
            // 边框
            drawRect(
                gridColor,
                topLeft = Offset(padL, padT),
                size = Size(w, h),
                style = Stroke(1.dp.toPx()),
            )

            // ---------------- 目标区域云 ----------------
            //
            // 每个元音画一片**区域**，不是画一个点。
            //
            // 目标是统计范围 —— 真实说话人的共振峰散布很宽，
            // 落在区域里就算达标，不必非对着中心点。
            //
            // 区域半径两个轴用**同一个百分比**（见 FeminineTarget.SPREAD），
            // 所以在图上是个正圆。元音图两个轴的跨度差 3 倍，
            // 用同样的 Hz 半径会画成一条窄缝，根本瞄不准。
            for (v in VowelTarget.entries) {
                val isTarget = v == target
                val isRecognized = v == recognized
                val c = Offset(xOf(FeminineTarget.centerF2(v)), yOf(FeminineTarget.centerF1(v)))
                // 半径用**音分**换算成像素，而不是直接的 Hz。
                //
                // 两个轴的跨度差 3 倍多（F1 800 Hz vs F2 2600 Hz），
                // 直接用 Hz 换算的话，同样 ±80 Hz 在 F1 方向占 1/10、
                // 在 F2 方向只占 1/32 —— 画出来是竖条，
                // 看着别扭，用户也判断不出「区有多大」。
                // **半径必须由 Hz 换算成像素** —— 和判定用的是同一个半径。
                //
                // 之前这里把「音分」直接当成了「轴跨度的百分比」：
                //   ry = 120 / 1000 * h   → 恒定 96 Hz
                //   rx = 120 / 1200 * w   → 恒定 260 Hz
                // 而判定用的是 FeminineTarget.radiusF1/F2（中心频率的 ±7.18%）。
                // 结果图上画的圈比判定用的大 1.4~4.2 倍，
                // 用户把点移进圈里，判定却说没到位。
                //
                // 现在两边都来自同一对 Hz 半径，图上的圈就是判定范围本身。
                // 形状是椭圆 —— 元音图两个轴的跨度差 3 倍多，
                // 用 Hz 半径画出来必然不是一个正圆，这是坐标轴的固有结果。
                val ry = (FeminineTarget.radiusF1(v) / (1000f - 200f) * h)
                    .coerceAtLeast(4.dp.toPx())
                val rx = (FeminineTarget.radiusF2(v) / (3200f - 600f) * w)
                    .coerceAtLeast(4.dp.toPx())
                val tint = when {
                    isTarget -> palette.trace
                    isRecognized -> palette.primaryInk
                    else -> palette.axisLabel
                }
                // 区域填充
                drawOval(
                    color = tint.copy(alpha = if (isTarget) 0.14f else 0.05f),
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = Size(rx * 2, ry * 2),
                )
                // 区域描边
                drawOval(
                    color = tint.copy(alpha = if (isTarget) 0.85f else 0.30f),
                    topLeft = Offset(c.x - rx, c.y - ry),
                    size = Size(rx * 2, ry * 2),
                    style = Stroke(if (isTarget) 2.dp.toPx() else 1.dp.toPx()),
                )
                // 区域中心
                drawCircle(tint.copy(alpha = if (isTarget) 1f else 0.5f), 2.5.dp.toPx(), c)
                // 标签
                //
                // 位置要**钳制在画布内** —— drawText 用「画布尺寸 − 文字位置」
                // 当布局约束，位置越界就会算出负的 maxWidth，直接崩。
                // （/i/ 的目标区靠右，不钳制必崩。）
                val labelSize = measurer.measure(
                    v.label,
                    TextStyle(fontSize = 10.sp),
                ).size
                val labelX = (c.x + rx + 3.dp.toPx())
                    .coerceAtMost(size.width - labelSize.width - 1.dp.toPx())
                    .coerceAtLeast(0f)
                drawText(
                    measurer,
                    v.label,
                    topLeft = Offset(labelX, c.y - 6.dp.toPx()),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = if (isTarget) palette.trace else palette.axisLabel,
                    ),
                )
            }

            // 轨迹
            //
            // 画在坐标区**里面**（裁剪），而且遇到大跳跃要断开 ——
            // 静音段前后 F1/F2 会突然变好几百 Hz，连起来就是
            // 横穿整张图的直线，看着像乱线。
            if (trail.size >= 2) {
                clipRect(padL, padT, padL + w, padT + h) {
                    for (i in 0 until trail.size - 1) {
                        val a = trail[i]
                        val b = trail[i + 1]
                        if (a.first <= 0f || b.first <= 0f) continue
                        // 跳跃太大 → 断开，不画这条
                        val jump = kotlin.math.abs(b.first - a.first) +
                            kotlin.math.abs(b.second - a.second)
                        if (jump > 300f) continue
                        val alpha = (i.toFloat() / trail.size * 0.7f + 0.15f)
                        drawLine(
                            color = palette.trace.copy(alpha = alpha),
                            start = Offset(xOf(a.second), yOf(a.first)),
                            end = Offset(xOf(b.second), yOf(b.first)),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }

            // 当前位置
            //
            // 在目标区里就变成绿色 —— 一眼看出「到位了」。
            if (f1 > 0f && f2 > 0f) {
                val c = Offset(xOf(f2), yOf(f1))
                val dot = if (inZone) palette.inTune else palette.primaryInk
                drawCircle(dot.copy(alpha = 0.18f), 11.dp.toPx(), c)
                drawCircle(dot, 5.dp.toPx(), c)
            }
        }
    }
}

/**
 * 一条横向的指示条（稳态度 / 音色轴都用它）。
 *
 * @param value -1..+1，-1 在左、+1 在右
 * @param center 是否有"中间"标记
 */
@Composable
fun AxisGauge(
    value: Float,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    leftLabel: String = "",
    rightLabel: String = "",
) {
    val palette = LocalVoicePalette.current
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontSize = 10.sp, color = palette.axisLabel)

    Canvas(modifier) {
        val h = size.height
        val trackY = 16.dp.toPx()
        val trackH = 6.dp.toPx()
        val padL = 2.dp.toPx()
        val padR = 2.dp.toPx()
        val w = size.width - padL - padR

        // 轨道
        drawRoundRect(
            color = palette.meterTrack,
            topLeft = Offset(padL, trackY),
            size = Size(w, trackH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackH / 2),
        )
        // 中线
        val midX = padL + w / 2
        drawLine(
            palette.axisLabel.copy(alpha = 0.5f),
            Offset(midX, trackY - 3.dp.toPx()),
            Offset(midX, trackY + trackH + 3.dp.toPx()),
            1.dp.toPx(),
        )
        // 指针
        val x = padL + (value.coerceIn(-1f, 1f) + 1f) / 2f * w
        drawCircle(palette.primaryInk, 6.dp.toPx(), Offset(x, trackY + trackH / 2))

        // 文字
        drawText(measurer, label, topLeft = Offset(padL, 0f), style = style)
        val vt = measurer.measure(valueText, style)
        drawText(
            measurer,
            valueText,
            topLeft = Offset(padL + w - vt.size.width, 0f),
            style = TextStyle(fontSize = 10.sp, color = palette.primaryInk),
        )
        if (leftLabel.isNotEmpty()) {
            drawText(measurer, leftLabel, topLeft = Offset(padL, trackY + trackH + 4.dp.toPx()), style = style)
        }
        if (rightLabel.isNotEmpty()) {
            val lt = measurer.measure(leftLabel, style)
            drawText(
                measurer,
                rightLabel,
                topLeft = Offset(padL + w - lt.size.width, trackY + trackH + 4.dp.toPx()),
                style = style,
            )
        }
    }
}
