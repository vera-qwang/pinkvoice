package com.vera.pinkvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.VoiceEngine

/** 顶部的页面。 */
enum class AppTab(val label: String) {
    /** 监测：实时音高曲线。 */
    MONITOR("监测"),

    /** 训练：元音图、稳态度、音色轴。 */
    TRAIN("训练"),
}

/**
 * App 的根页面：监测 / 训练两个标签。
 *
 * ## 为什么要单独一页
 *
 * 不只是「主页面挤」：
 *
 * - **数据不同**：监测画 f0 曲线，训练要 F1/F2 轨迹和音色轴
 * - **交互不同**：监测是「打开就看」，训练是「选目标 → 练 → 看反馈」，
 *   需要参照物
 * - **性能**：LPC 分析只在训练页跑，监测页照旧轻量
 */
@Composable
fun PinkVoiceApp(
    engine: VoiceEngine,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val palette = LocalVoicePalette.current
    var tab by remember { mutableStateOf(AppTab.MONITOR) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(palette.backgroundTop, palette.backgroundBottom),
                ),
            )
            // 安全区在根上处理：标签栏要贴着屏幕底边，
            // 但内容不能被状态栏/手势条盖住。
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                AppTab.MONITOR -> VoiceScreen(
                    engine = engine,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    modifier = Modifier.fillMaxSize(),
                )

                AppTab.TRAIN -> TrainingScreen(
                    engine = engine,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // ---------------- 标签栏 ----------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (t in AppTab.entries) {
                TabButton(
                    label = t.label,
                    selected = tab == t,
                    onClick = { tab = t },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 标签栏里的一个按钮。 */
@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Box(
            modifier = Modifier.padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
