package com.vera.pinkvoice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部导航的标签页。
 *
 * 标签用**技术名**（F0 / F1/F2）而不是功能名 ——
 * 用户来这里就是为了看这些量，写「监测」「训练」反而不知道是什么。
 *
 * 新增标签只要往这个枚举里加一项，导航栏自己会排版。
 */
enum class AppTab(
    /** 导航栏上的短标签。 */
    val label: String,
    /** 图标。 */
    val icon: NavIcon,
) {
    /** 基频（音高）曲线。 */
    F0("F0", NavIcon.PITCH),

    /** 第一、第二共振峰（元音图）。 */
    FORMANTS("F1/F2", NavIcon.VOWEL),
}

/** 导航栏图标。 */
enum class NavIcon { PITCH, VOWEL }

/**
 * 底部导航栏。
 *
 * ## 为什么不��� Material3 的 `NavigationBar`
 *
 * 那个组件固定 80 dp 高，对一个曲线为主的 App 太奢侈了 ——
 * 屏幕就那么高，切掉 26 dp 是实打实的曲线长度。
 *
 * 这里用紧凑版（~52 dp），图标在上、文字在下，
 * 但仍然是标准的导航栏形态：选中态有胶囊底色，图标+文字同色。
 */
@Composable
fun AppNavBar(
    current: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (tab in AppTab.entries) {
            NavItem(
                tab = tab,
                selected = tab == current,
                onClick = { onSelect(tab) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 导航栏里的一个标签。 */
@Composable
private fun NavItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    val target = if (selected) palette.trace else MaterialTheme.colorScheme.onSurfaceVariant
    val tint by animateColorAsState(target, label = "navTint")

    val pillAlpha by animateFloatAsState(
        if (selected) 1f else 0f,
        label = "navPill",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(
                palette.meterTrack.copy(alpha = pillAlpha * 0.9f),
            )
            .clickableNoRipple(onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            NavIconCanvas(tab.icon, tint)
        }
        Text(
            text = tab.label,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint,
            maxLines = 1,
        )
    }
}

/** 导航图标本体。 */
@Composable
private fun NavIconCanvas(icon: NavIcon, tint: Color) {
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        when (icon) {
            // 音高曲线：一条起伏的折线
            NavIcon.PITCH -> {
                val path = Path().apply {
                    moveTo(w * 0.08f, h * 0.72f)
                    cubicTo(w * 0.28f, h * 0.16f, w * 0.44f, h * 0.16f, w * 0.54f, h * 0.55f)
                    cubicTo(w * 0.64f, h * 0.92f, w * 0.82f, h * 0.86f, w * 0.92f, h * 0.30f)
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = 2.2.dp.toPx()),
                )
            }

            // 共振峰轨迹：两条横带 + 一条曲线
            //
            // 原来画的是元音图（方框 + 三个点）。
            // 现在 F1/F2 页是时间序列，图标跟着改成「轨迹」的样子 ——
            // 图标要和页面讲同一件事。
            NavIcon.VOWEL -> {
                val inset = w * 0.12f
                val plotW = w - inset * 2
                // 两条带（F1 上、F2 下）
                for (i in 0..1) {
                    val top = inset + i * (h - inset * 2) * 0.55f
                    drawLine(
                        color = tint.copy(alpha = 0.40f),
                        start = Offset(inset, top),
                        end = Offset(inset + plotW, top),
                        strokeWidth = 1.4.dp.toPx(),
                    )
                }
                // 曲线：F1 那条带上的一道起伏
                val path = androidx.compose.ui.graphics.Path()
                val baseY = inset + (h - inset * 2) * 0.55f
                for (i in 0..6) {
                    val t = i / 6f
                    val x = inset + plotW * t
                    val y = baseY + kotlin.math.sin(t * 5.2f) * (h - inset * 2) * 0.14f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = tint, style = Stroke(width = 1.8.dp.toPx()))
            }
        }
    }
}

/** 不带涟漪的点击（导航栏自己有选中态动画，涟漪会显得吵）。 */
@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}
