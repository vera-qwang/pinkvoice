package com.vera.pinkvoice.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/** 状态点：运行中绿色，空闲灰色。 */
@Composable
fun StatusDot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Canvas(Modifier.size(size)) {
        drawCircle(color)
        drawCircle(Color.White.copy(alpha = 0.35f), radius = this.size.minDimension / 2.4f)
    }
}

/**
 * 音分指针表。
 *
 * 中心 0 cent 为"准"，±10 cent 为绿色区（唱准了），越偏越红。
 * 对唱歌来说这是比数字更有用的反馈形式——一眼看出偏高还是偏低。
 */
@Composable
fun CentsGauge(
    cents: Float,
    voiced: Boolean,
    textMeasurer: TextMeasurer,
    modifier: Modifier = Modifier,
    rangeCents: Float = 50f,
) {
    val palette = LocalVoicePalette.current

    // 刻度文字的测量结果缓存起来。内容（"-50" / "0" / "+50"）和字号都不变，
    // 每帧重新 measure 是浪费——读数 30 Hz 更新，就是每秒 90 次测量。
    val labelStyle = remember(palette.axisLabel) {
        TextStyle(fontSize = 10.sp, color = palette.axisLabel)
    }
    val leftText = remember(rangeCents) { "-${rangeCents.toInt()}" }
    val rightText = remember(rangeCents) { "+${rangeCents.toInt()}" }
    val rightSize = remember(textMeasurer, rightText, labelStyle) {
        textMeasurer.measure(rightText, labelStyle).size
    }
    val zeroSize = remember(textMeasurer, labelStyle) {
        textMeasurer.measure("0", labelStyle).size
    }

    Canvas(modifier.height(34.dp)) {
        val w = size.width
        val h = size.height
        val axisY = h * 0.42f
        val halfRange = w / 2f

        // 轨道
        drawRoundRect(
            color = palette.meterTrack,
            topLeft = Offset(0f, axisY - 3f),
            size = Size(w, 6f),
            cornerRadius = CornerRadius(3f),
        )
        // 唱准区 ±10 cent
        val inTuneWidth = 10f / rangeCents * halfRange
        drawRoundRect(
            color = palette.inTune.copy(alpha = 0.5f),
            topLeft = Offset(halfRange - inTuneWidth, axisY - 3f),
            size = Size(inTuneWidth * 2, 6f),
            cornerRadius = CornerRadius(3f),
        )
        // 中心刻度
        drawLine(
            color = palette.axisLabel,
            start = Offset(halfRange, axisY - 9f),
            end = Offset(halfRange, axisY + 9f),
            strokeWidth = 1.5f,
        )

        // 指针
        if (voiced) {
            val clamped = cents.coerceIn(-rangeCents, rangeCents)
            val x = halfRange + clamped / rangeCents * halfRange
            val color = when {
                abs(cents) <= 10f -> palette.inTune
                abs(cents) <= 25f -> palette.nearTune
                else -> palette.offTune
            }
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(x, axisY - 16f),
                end = Offset(x, axisY + 16f),
                strokeWidth = 7f,
            )
            drawCircle(color, radius = 7f, center = Offset(x, axisY))
            drawCircle(palette.needleInner, radius = 2.6f, center = Offset(x, axisY))
        }

        // 刻度文字（测量结果已缓存；定位避免右侧 "+50" 被裁掉）
        drawText(textMeasurer, leftText, Offset(0f, axisY + 13f), labelStyle)
        drawText(textMeasurer, "0", Offset(halfRange - zeroSize.width / 2f, axisY + 13f), labelStyle)
        drawText(
            textMeasurer,
            rightText,
            Offset(w - rightSize.width, axisY + 13f),
            labelStyle,
        )
    }
}

/** 竖直电平表：麦克风输入强度（0..1 归一化后传入）。 */
@Composable
fun LevelMeter(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    Canvas(modifier.width(8.dp).height(72.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = palette.meterTrack,
            topLeft = Offset(0f, 0f),
            size = Size(w, h),
            cornerRadius = CornerRadius(w / 2),
        )
        val ratio = level.coerceIn(0f, 1f)
        if (ratio > 0f) {
            val fillH = h * ratio
            drawRoundRect(
                color = palette.meterFill,
                topLeft = Offset(0f, h - fillH),
                size = Size(w, fillH),
                cornerRadius = CornerRadius(w / 2),
            )
        }
    }
}

/** 扬声器图标（自绘，避免引入 material-icons-extended）。 */
@Composable
fun SpeakerGlyph(
    modifier: Modifier = Modifier,
    color: Color = Color.White,
) {
    Canvas(modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.10f, h * 0.36f)
            lineTo(w * 0.30f, h * 0.36f)
            lineTo(w * 0.52f, h * 0.14f)
            lineTo(w * 0.52f, h * 0.86f)
            lineTo(w * 0.30f, h * 0.64f)
            lineTo(w * 0.10f, h * 0.64f)
            close()
        }
        drawPath(path, color)
        // 声波
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 100f,
            useCenter = false,
            topLeft = Offset(w * 0.58f, h * 0.24f),
            size = Size(w * 0.40f, h * 0.52f),
            style = Stroke(width = w * 0.09f),
        )
    }
}

/** 停止/录音的小方块图标。 */
@Composable
fun StopGlyph(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(14.dp)) {
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
            size = Size(size.width * 0.64f, size.height * 0.64f),
            cornerRadius = CornerRadius(3f),
        )
    }
}

/** 分区标题。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 键值详情行。 */
@Composable
fun DetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = label,
            fontSize = 13.sp,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        androidx.compose.material3.Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 细边框卡片背景。 */
@Composable
fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalVoicePalette.current
    Box(
        modifier = modifier.background(
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        ),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Color.Transparent),
        )
        content()
    }
}

/**
 * 频率范围指示器。
 *
 * 显示当前可见的频率范围，点一下回到默认。
 * 范围不是默认的时候前面加个 ⟲，提示"点它可以复位"。
 */
@Composable
fun FrequencyRangeChip(
    range: FrequencyRange,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    // 只读这一个 composable 里的状态，滑动时不会牵连整屏重组
    val min = range.min
    val max = range.max
    val changed = !range.isDefault
    Text(
        text = if (changed) {
            "⟲ ${min.roundToInt()}–${max.roundToInt()} Hz"
        } else {
            "${min.roundToInt()}–${max.roundToInt()} Hz"
        },
        fontSize = 11.sp,
        fontWeight = if (changed) FontWeight.Medium else FontWeight.Normal,
        color = if (changed) {
            palette.primaryInk
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = changed) { range.reset() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
