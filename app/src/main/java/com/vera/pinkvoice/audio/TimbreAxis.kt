package com.vera.pinkvoice.audio

/**
 * 音色轴：从「薄/虚/前共振」到「厚/实/后共振」的连续量。
 *
 * ## 为什么是一个轴而不是几个独立指标
 *
 * 「虚实」「前共振」「后共振」「厚薄」在频谱上是**高度相关**的 ——
 * 它们描述的是同一件事的不同说法。分开做几个指标，用户会看到
 * 互相矛盾的数字（这个说虚、那个说前），反而困惑。
 *
 * 所以合成成一条轴，同时把子指标（F1、谱倾斜）显示出来，
 * 让用户知道**为什么**现在是这一端。
 *
 * ## 为什么必须用个人基准
 *
 * 「厚」和「薄」没有绝对标准：
 * - 男声天生比女声低频多
 * - 喉腔大小、面部骨骼各不相同
 * - 手机麦克风的频响 + 蓝牙链路的染色也不同
 *
 * 任何固定的绝对阈值都是错的。做法是让用户录一段
 * **自己觉得理想的**声音当基准，之后显示「比你的基准偏亮还是偏暗」。
 *
 * ## 怎么算
 *
 * 两个子特征，各自相对基准做标准化，再加权：
 *
 * ```
 * 轴位置 = 0.6 × z(谱倾斜) + 0.4 × z(F1)
 * ```
 *
 * `z()` 是相对基准的标准化值（减去基准均值再除以基准标准差）。
 *
 * - **谱倾斜**（负值 = 低频多 = 厚 = 后共振）
 * - **F1**（大 = 嘴张得开 = 偏前）
 *
 * 权重 0.6/0.4：谱倾斜对「厚薄」更直接，F1 辅助。
 */
class TimbreAxis(
    /** 基准的统计量。 */
    val baseline: Baseline = Baseline(),
) {

    /**
     * 个人基准。
     *
     * 录一段"理想的声音"，收集这些量的均值和标准差。
     */
    class Baseline {
        var tiltMean: Float = 0f
        var tiltStd: Float = 1f
        var f1Mean: Float = 0f
        var f1Std: Float = 1f
        var sampleCount: Int = 0
            private set

        /** 有没有足够的基准数据。 */
        val valid: Boolean get() = sampleCount >= MIN_SAMPLES

        /** 标准差太小会让 z 值爆掉，给个下限。 */
        private val minStd = 1.5f

        fun add(tilt: Float, f1: Float) {
            if (sampleCount == 0) {
                tiltMean = tilt
                f1Mean = f1
                tiltStd = 0f
                f1Std = 0f
                sampleCount = 1
                return
            }
            // 增量式的均值/方差（Welford）
            val n = sampleCount.toDouble()
            val newN = n + 1
            val dTilt = tilt - tiltMean
            tiltMean += dTilt / newN.toFloat()
            tiltStd += (dTilt * (tilt - tiltMean) - tiltStd) / newN.toFloat()
            val dF1 = f1 - f1Mean
            f1Mean += dF1 / newN.toFloat()
            f1Std += (dF1 * (f1 - f1Mean) - f1Std) / newN.toFloat()
            sampleCount++
        }

        fun reset() {
            tiltMean = 0f
            tiltStd = 0f
            f1Mean = 0f
            f1Std = 0f
            sampleCount = 0
        }

        fun zTilt(value: Float): Float =
            (value - tiltMean) / maxOf(tiltStd, minStd)

        fun zF1(value: Float): Float =
            (value - f1Mean) / maxOf(f1Std, minStd)
    }

    /**
     * 当前的位置。
     *
     * @param position -1（薄/虚/前）.. +1（厚/实/后）
     * @param tilt 谱倾斜（dB/oct）
     * @param f1 归一化后的 F1（Hz）
     * @param hasBaseline 有没有可用基准
     */
    data class Reading(
        val position: Float,
        val tilt: Float,
        val f1: Float,
        val hasBaseline: Boolean,
    ) {
        /** 中文描述。 */
        val label: String
            get() = when {
                !hasBaseline -> "未设基准"
                position > 0.5f -> "厚实"
                position > 0.15f -> "偏厚"
                position > -0.15f -> "居中"
                position > -0.5f -> "偏薄"
                else -> "薄虚"
            }
    }

    /**
     * 计算当前的位置。
     *
     * 没有基准时返回 0（居中）并标明 `hasBaseline = false`。
     */
    fun read(tilt: Float, f1: Float): Reading {
        if (!baseline.valid) {
            return Reading(0f, tilt, f1, false)
        }
        // 谱倾斜：更负 = 低频多 = 厚 = +1 方向
        val zTilt = -baseline.zTilt(tilt)
        val zF1 = -baseline.zF1(f1)
        val pos = (0.6f * zTilt + 0.4f * zF1).coerceIn(-1f, 1f)
        return Reading(pos, tilt, f1, true)
    }

    companion object {
        /** 基准至少要多少个样本。 */
        const val MIN_SAMPLES = 20
    }
}
