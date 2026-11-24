package com.vera.pinkvoice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.pinkvoice.audio.FormantNormalizer
import com.vera.pinkvoice.audio.VoiceEngine
import com.vera.pinkvoice.audio.VowelRecognizer
import com.vera.pinkvoice.audio.VowelTarget

/**
 * F1/F2 页的内容：元音图 + 稳态度 + 音色轴。
 *
 * F0 页回答「我现在多高」，这一页回答「我这个音**准不准、稳不稳**」。
 *
 * 三块内容：
 * 1. **元音图** —— 你在元音空间里的位置，和目标的距离
 * 2. **稳态度** —— 帧与帧之间的抖动（这是"稳态训练"的核心指标）
 * 3. **音色轴** —— 薄虚↔厚实（需要个人基准才有意义）
 *
 * 头部、控制条、导航栏由根页面 [PinkVoiceApp] 统一管。
 */
@Composable
fun FormantContent(
    engine: VoiceEngine,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    val quality by engine.qualityState.collectAsState()

    var target by remember { mutableStateOf(VowelTarget.A) }
    // 轨迹：最近 ~3 秒的位置
    val trail = remember { mutableStateListOf<Pair<Float, Float>>() }

    // 只在训练页打开时才跑 LPC（监测页不需要）
    LaunchedEffect(Unit) {
        engine.qualityAnalysisEnabled = true
    }
    // 轨迹维护：跟着 quality 走
    LaunchedEffect(quality.formants.f1, quality.formants.f2) {
        if (quality.valid && quality.formants.f1 > 0f) {
            trail.add(quality.formants.f1 to quality.formants.f2)
            while (trail.size > 90) trail.removeAt(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 10.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // ---------------- 声道长度 ----------------
        //
        // 折算到标准声道长度是元音图能跨人比较的前提，
        // 所以一直显示着——用户能看到自己的基准值。
        Text(
            text = "声道 ${"%.1f".format(FormantNormalizer.userLengthCm)} cm" +
                "（折算到 17.5 cm 标准，元音图才能跨人比较）",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------- 目标元音 ----------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (v in VowelTarget.entries) {
                FilterChip(
                    selected = target == v,
                    onClick = { target = v },
                    label = { Text(v.label, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---------------- 元音图 ----------------
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.cardBorder),
        ) {
            Column(Modifier.padding(10.dp)) {
                VowelChart(
                    f1 = quality.formants.f1,
                    f2 = quality.formants.f2,
                    trail = trail.toList(),
                    recognized = quality.recognized,
                    target = target,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "F1 ${quality.formants.f1.toInt()} Hz",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "F2 ${quality.formants.f2.toInt()} Hz",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = quality.recognized?.let { "像「${it.label}」" } ?: "—",
                        fontSize = 11.sp,
                        color = palette.trace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // 到目标的距离 —— 训练时最该看的一个数。
                //
                // 归一化 Hz 空间里的欧氏距离，F2 的权重 1.4 倍
                // （舌位变化比开口度更容易被听出来）。
                val dist = VowelRecognizer.distanceTo(
                    target,
                    quality.formants.f1,
                    quality.formants.f2,
                )
                Text(
                    text = when {
                        !quality.valid -> "离「${target.label}」——"
                        dist <= 80f -> "离「${target.label}」很近 ✓"
                        dist <= 180f -> "离「${target.label}」还差一点"
                        else -> "离「${target.label}」还差不少"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        !quality.valid -> MaterialTheme.colorScheme.onSurfaceVariant
                        dist <= 80f -> palette.inTune
                        dist <= 180f -> palette.nearTune
                        else -> palette.offTune
                    },
                )
            }
        }

        // ---------------- 稳态度 + 音色轴 ----------------
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, palette.cardBorder),
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AxisGauge(
                    value = quality.stability * 2f - 1f,
                    label = "稳态度",
                    valueText = "${(quality.stability * 100).toInt()}%",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    leftLabel = "抖",
                    rightLabel = "稳",
                )
                AxisGauge(
                    value = quality.timbre.position,
                    label = "音色",
                    valueText = quality.timbre.label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                    leftLabel = "薄/虚",
                    rightLabel = "厚/实",
                )
                // 基准按钮
                Button(
                    onClick = {
                        if (quality.capturingBaseline) {
                            engine.cancelBaselineCapture()
                        } else {
                            engine.startBaselineCapture()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (quality.capturingBaseline) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (quality.capturingBaseline) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        },
                    ),
                ) {
                    Text(
                        text = when {
                            quality.capturingBaseline -> "取消（${(quality.baselineProgress * 100).toInt()}%）"
                            quality.timbre.hasBaseline -> "重录基准"
                            else -> "录基准：念 /a/ 3 秒"
                        },
                        fontSize = 13.sp,
                    )
                }
                if (quality.capturingBaseline) {
                    LinearProgressIndicator(
                        progress = { quality.baselineProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "念一个「啊」，拉长、保持不变",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (!quality.timbre.hasBaseline) {
                    Text(
                        text = "厚薄没有绝对标准，得跟自己的声音比。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
