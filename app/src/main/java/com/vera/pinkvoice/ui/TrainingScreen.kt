package com.vera.pinkvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.VoiceEngine

/**
 * F1/F2 页的内容：共振峰分析结果。
 *
 * F0 页回答「我现在多高」，这一页回答「我的**共振峰**是多少、稳不稳」。
 *
 * ## 只显示分析结果，不做评判
 *
 * 这一页**只显示测出来的共振峰**，不显示任何「目标」：
 *
 * - 没有目标区
 * - 没有「离目标还差多少」
 * - 没有「保持 N 秒算达标」
 * - 没有元音身份判定
 *
 * 原因：目标区那套东西的数值是**我们自己定的**（人口平均值 × 男女比值），
 * 既不是用户的需求，也不能保证可达 —— 用户的元音空间本来就偏离人口平均。
 * 显示一个够不到的目标，只会让界面一直在说「没达标」。
 *
 * 分析口径本身是可靠的 —— 和参照工具逐帧对照，
 * 中位误差 F1 8.6 Hz、F2 19.2 Hz（见 `docs/vowel-training.md`）。
 *
 * ## 头部、控制条、导航栏由根页面 [PinkVoiceApp] 统一管。
 */
@Composable
fun FormantContent(
    engine: VoiceEngine,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    val quality by engine.qualityState.collectAsState()
    val status by engine.status.collectAsState()
    val running = status is com.vera.pinkvoice.audio.EngineStatus.Running

    // 只在训练页打开时才跑 LPC（监测页不需要）
    LaunchedEffect(Unit) {
        engine.qualityAnalysisEnabled = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- 说明 ----------------
        Text(
            text = "共振峰分析：F1 = 开口度，F2 = 舌位前后",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // ---------------- 轨迹 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.cardBorder),
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "最近 8 秒",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (quality.valid) "正在测量" else "等待声音",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                FormantTrace(
                    history = engine.formantHistory,
                    f1 = quality.formants.f1,
                    f2 = quality.formants.f2,
                    active = running,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Spacer(Modifier.height(6.dp))

                // ---- 读数 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FormantReadout(
                        label = "F1",
                        value = quality.formants.f1,
                        hint = "开口度",
                        active = quality.valid,
                        modifier = Modifier.weight(1f),
                    )
                    FormantReadout(
                        label = "F2",
                        value = quality.formants.f2,
                        hint = "舌位前后",
                        active = quality.valid,
                        modifier = Modifier.weight(1f),
                    )
                    FormantReadout(
                        label = "F3",
                        value = quality.formants.f3,
                        hint = "",
                        active = quality.valid,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---------------- 分析口径 ----------------
        //
        // 让用户知道这些数字是怎么来的 —— 报一个来源不明的数字，
        // 用户没法判断该信几分。
        Text(
            text = "口径：11 kHz 分析 · 50 ms 高斯窗 · 10 阶 LPC · 预加重 50 Hz",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一个共振峰的读数。
 */
@Composable
private fun FormantReadout(
    label: String,
    value: Float,
    hint: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (active && value > 0f) "${value.toInt()} Hz" else "—",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (active && value > 0f) {
                MaterialTheme.colorScheme.onBackground
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        if (hint.isNotEmpty()) {
            Text(
                text = hint,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
