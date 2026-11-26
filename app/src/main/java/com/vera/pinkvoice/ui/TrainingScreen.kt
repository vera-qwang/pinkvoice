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
import com.vera.pinkvoice.audio.VowelTarget
import com.vera.pinkvoice.audio.VowelZone

/** 保持多少秒算达标。 */
private const val HOLD_TARGET_SECONDS = 10f

/**
 * 一个方向的偏差（音分）。
 *
 * 显示「偏高 / 偏低」而不是带正负号的数字 ——
 * 用户要的是「往哪调」。
 */
@Composable
private fun DeviationChip(
    label: String,
    /** 偏差（Hz），正数偏高。 */
    deltaHz: Float,
    /** 该频率上的容差半径（Hz）。 */
    toleranceHz: Float,
    active: Boolean,
    /** 读数是否可信（不可信时显示「读数不稳」而不是偏差）。 */
    credible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    // 容差按音分算（见 VowelZone），但显示的是 Hz 偏差 ——
    // 所以判定要换算回去，不能直接拿 Hz 比。
    val inTol = kotlin.math.abs(deltaHz) <= toleranceHz
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                !active -> "—"
                !credible -> "读数不稳"
                inTol -> "✓ 正好"
                // 差太远时别显示一长串数字 —— 截断掉，
                // 用户关心的是方向，不是精确数值。
                deltaHz > 999f -> "偏高 >999"
                deltaHz < -999f -> "偏低 >999"
                deltaHz > 0 -> "偏高 ${deltaHz.toInt()}"
                else -> "偏低 ${(-deltaHz).toInt()}"
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !active -> MaterialTheme.colorScheme.outline
                !credible -> MaterialTheme.colorScheme.onSurfaceVariant
                inTol -> palette.inTune
                else -> palette.nearTune
            },
        )
    }
}

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

    // 分轴偏差（Hz）—— 正数偏高，负数偏低。
    //
    // 显示用 Hz，判定用音分（[VowelZone]）：
    // 音分对**算法**合理（频率是等比关系，每个元音难度一样），
    // 但对**用户**没有直觉 —— "偏低 2213 音分"没人看得懂，
    // "低了 527 Hz"才能想象出该往哪调。
    val devF1 = quality.formants.f1 - target.f1
    val devF2 = quality.formants.f2 - target.f2
    // 保持进度
    val holdProgress = (quality.holdSeconds / HOLD_TARGET_SECONDS).coerceIn(0f, 1f)

    // F2 的读数可信吗？
    //
    // 信号不好的时候 F2 会整个消失，界面上显示的其实是 F3。
    // 与其报一个错的值，不如说「读数不稳」——
    // 训练时更怕的是照着一个错的数字练。
    val f2Credible = quality.valid &&
        VowelZone.isF2Credible(quality.formants.f2, target, quality.recognized)

    // 是否在目标区里。
    //
    // F2 读数不可信时**只用 F1 判定** —— F1 在低频区，
    // 反而是信号差的时候最靠得住的那个。
    val inZone = quality.valid &&
        kotlin.math.abs(devF1) <= VowelZone.radiusHz(target.f1) &&
        (!f2Credible || kotlin.math.abs(devF2) <= VowelZone.radiusHz(target.f2))


    // 只在训练页打开时才跑 LPC（监测页不需要）
    LaunchedEffect(Unit) {
        engine.qualityAnalysisEnabled = true
    }
    // 告诉引擎当前目标 —— 保持进度要靠它计时
    LaunchedEffect(target) {
        engine.setVowelTarget(target)
    }
    // 离开页面时把目标清掉，免得后台还在计时
    DisposableEffect(Unit) {
        onDispose { engine.setVowelTarget(null) }
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
                // ---- 指令：用户到底该做什么 ----
                //
                // 之前只有「离「啊」还差一点」这种描述，
                // 用户不知道该发什么音、保持多久、怎么算对。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "持续发「${target.label}」，把点保持在圈里",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "目标 F1 ${target.f1.toInt()} / F2 ${target.f2.toInt()}",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                VowelChart(
                    f1 = quality.formants.f1,
                    f2 = quality.formants.f2,
                    trail = trail.toList(),
                    recognized = quality.recognized,
                    target = target,
                    inZone = inZone,
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
                        text = if (quality.valid) {
                            "F1 ${quality.formants.f1.toInt()} Hz"
                        } else {
                            "F1 —"
                        },
                        fontSize = 11.sp,
                        color = if (quality.valid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        text = if (quality.valid) {
                            "F2 ${quality.formants.f2.toInt()} Hz"
                        } else {
                            "F2 —"
                        },
                        fontSize = 11.sp,
                        color = if (quality.valid) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(
                        text = quality.recognized?.let { "像「${it.label}」" } ?: "—",
                        fontSize = 11.sp,
                        color = if (inZone) palette.inTune else palette.trace,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                // ---- 偏差：分轴显示 ----
                //
                // 一个笼统的"距离"没用 —— 用户不知道该往哪调。
                // 分成 F1 和 F2 两个方向，各自说偏高还是偏低：
                // F1 高 = 嘴张小了，F2 高 = 舌位靠前了。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeviationChip(
                        label = "F1",
                        deltaHz = devF1,
                        toleranceHz = VowelZone.radiusHz(target.f1),
                        active = quality.valid,
                        modifier = Modifier.weight(1f),
                    )
                    DeviationChip(
                        label = "F2",
                        deltaHz = devF2,
                        toleranceHz = VowelZone.radiusHz(target.f2),
                        active = quality.valid,
                        // 读数不可信就不显示偏差数字 —— 显示一个错的数字更糟
                        credible = f2Credible,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ---- 保持进度 ----
                //
                // 有了目标区还不够 —— 用户要知道「保持多久算练到了」。
                // 连续保持在圈里才算，中途掉出来要重新计时。
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { holdProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = palette.inTune,
                    trackColor = palette.meterTrack,
                )
                Text(
                    text = when {
                        !quality.valid -> "还没听到声音"
                        quality.holdSeconds >= HOLD_TARGET_SECONDS -> "✓ 保持 ${HOLD_TARGET_SECONDS.toInt()} 秒，达标"
                        else -> "已保持 ${"%.1f".format(quality.holdSeconds)} 秒 / 目标 ${HOLD_TARGET_SECONDS.toInt()} 秒"
                    },
                    fontSize = 11.sp,
                    fontWeight = if (quality.holdSeconds >= HOLD_TARGET_SECONDS) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    color = when {
                        !quality.valid -> MaterialTheme.colorScheme.onSurfaceVariant
                        quality.holdSeconds >= HOLD_TARGET_SECONDS -> palette.inTune
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
