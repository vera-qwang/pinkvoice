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
import com.vera.pinkvoice.audio.FeminineTarget
import com.vera.pinkvoice.audio.VoiceEngine
import com.vera.pinkvoice.audio.VowelTarget
import com.vera.pinkvoice.audio.VowelZone

/** 保持多少秒算达标。 */
private const val HOLD_TARGET_SECONDS = 10f

/**
 * 一个方向上的读数：实测值、目标、差多少。
 *
 * 显示「偏高 / 偏低」而不是带正负号的数字 ——
 * 用户要的是「往哪调」。
 */
@Composable
private fun DeviationChip(
    label: String,
    /** 实测值（Hz）。 */
    value: Float,
    /** 目标区域中心（Hz）。 */
    target: Float,
    /** 目标区域半径（Hz）。 */
    radius: Float,
    active: Boolean,
    /** 读数是否可信（不可信时显示「读数不稳」而不是偏差）。 */
    credible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val palette = LocalVoicePalette.current
    val delta = value - target
    val inTol = kotlin.math.abs(delta) <= radius
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
                !credible -> "${value.toInt()} Hz · 读数不稳"
                inTol -> "${value.toInt()} Hz ✓"
                // 差太远时别显示一长串数字 —— 截断掉，
                // 用户关心的是方向，不是精确数值。
                delta > 999f -> "${value.toInt()} Hz 偏高 >999"
                delta < -999f -> "${value.toInt()} Hz 偏低 >999"
                delta > 0 -> "${value.toInt()} Hz 偏高 ${delta.toInt()}"
                else -> "${value.toInt()} Hz 偏低 ${(-delta).toInt()}"
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

    // 整体前移进度（相对参考值）
    val shiftF1 = FeminineTarget.shiftPercentF1(quality.formants.f1, target)
    val shiftF2 = FeminineTarget.shiftPercentF2(quality.formants.f2, target)
    // 保持进度
    val holdProgress = (quality.holdSeconds / HOLD_TARGET_SECONDS).coerceIn(0f, 1f)

    // F2 的读数可信吗？
    //
    // 信号不好的时候 F2 会整个消失，界面上显示的其实是 F3。
    // 与其报一个错的值，不如说「读数不稳」——
    // 训练时更怕的是照着一个错的数字练。
    val f2Credible = quality.valid &&
        VowelZone.isF2Credible(quality.formants.f2, target)

    // 是否在目标区里。
    //
    // **直接读分析器的判定结果**，不要自己再算一遍 ——
    // 之前 UI 和保持计时各判各的：
    // UI 允许「F2 不可信就只看 F1」，计时要求两个都在区。
    // 结果 F2 被判不稳时，点画成绿色「到位了」，
    // 进度条却永远 0.0 秒，自相矛盾。
    //
    // 判定用最近 12 帧的中位数 + 在区比例（分布式），
    // 起音过渡和偶发坏读数都判不出来。
    val inZone = quality.inZone


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
        // ---------------- 目标说明 ----------------
        //
        // 这一行是整个页面的**目标**。
        //
        // 之前这里写的是「声道 17.5 cm（折算到…）」——
        // 那是背后的科学工具，初学者完全看不懂，
        // 更不知道该拿它怎么办。
        //
        // 训练目标其实只有一句话：**把共振峰空间整体往前、往上抬**，
        // 同时保持元音仍然可辨认。
        Text(
            text = "目标：把共振峰空间往前、往上抬，同时保持元音可辨认",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
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
                        text = "发「${target.label}」，把点移进目标区",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "目标 F1 ${FeminineTarget.centerF1(target).toInt()}" +
                            " / F2 ${FeminineTarget.centerF2(target).toInt()}",
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
                // ---- 元音身份（和女性化目标是两回事）----
                //
                // 元音图本来的用途是**区分元音身份**（舌位前后高低），
                // 这一行只回答那个问题。
                //
                // 刻意和下面的「共振峰目标」分开显示 ——
                // 之前两者混在一起，用户会以为
                // 「落到标准元音的点 = 女性化练好了」，
                // 或者反过来「点偏了 = 失败」。两个都是错的。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "元音身份",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = quality.recognized?.let { "你在发「${it.label}」" } ?: "—",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (quality.recognized == target) {
                            palette.inTune
                        } else {
                            MaterialTheme.colorScheme.onBackground
                        },
                    )
                }

                // ---- 共振峰：相对目标区域的偏差 ----
                //
                // 分轴显示：用户要知道该往哪调。
                // F1 高 = 嘴张小了 / 舌位抬高了；F2 高 = 舌位更靠前。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeviationChip(
                        label = "F1",
                        value = quality.formants.f1,
                        target = FeminineTarget.centerF1(target),
                        radius = FeminineTarget.radiusF1(target),
                        active = quality.valid,
                        modifier = Modifier.weight(1f),
                    )
                    DeviationChip(
                        label = "F2",
                        value = quality.formants.f2,
                        target = FeminineTarget.centerF2(target),
                        radius = FeminineTarget.radiusF2(target),
                        active = quality.valid,
                        // 读数不可信就不显示偏差数字 —— 显示一个错的数字更糟
                        credible = f2Credible,
                        modifier = Modifier.weight(1f),
                    )
                }

                // ---- 整体前移进度 ----
                //
                // 「整体把共振峰空间往前、往上抬」是这个页面的真正目标，
                // 所以要有一个总览：相对参考值前移了多少，目标是多少。
                Text(
                    text = if (quality.valid) {
                        "共振峰前移　F1 ${shiftF1.toInt()}%" +
                            "　F2 ${shiftF2.toInt()}%" +
                            "　（目标 +${((target.femaleF1Ratio - 1f) * 100).toInt()}%" +
                            " / +${((target.femaleF2Ratio - 1f) * 100).toInt()}%）"
                    } else {
                        "共振峰前移 —"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // ---- 方向提示 ----
                // 差得最多的那个方向，先动它。
                //
                // F2 读数不可信时不拿它做提示 ——
                // 那是个错的数字，照它调整只会越练越偏。
                val hint = if (quality.valid) {
                    FeminineTarget.hint(
                        quality.formants.f1,
                        if (f2Credible) quality.formants.f2 else FeminineTarget.centerF2(target),
                        target,
                    )
                } else {
                    null
                }
                if (hint != null) {
                    Text(
                        text = "再调一点：$hint",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.nearTune,
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

    }
}
