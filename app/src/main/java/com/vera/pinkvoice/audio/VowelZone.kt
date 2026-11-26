package com.vera.pinkvoice.audio

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * 元音的**目标区**：离目标元音多近算「到位」。
 *
 * ## 为什么用音分而不是 Hz
 *
 * 两个原因。
 *
 * **听感**：频率是等比关系，1 音分 = 0.58% 的频率差。
 * 同样 100 Hz 的偏差，在 F1=270（/i/）上听着差得多，
 * 在 F2=2290 上几乎听不出来。用 Hz 定容差，对不同的元音
 * 难度完全不一样。
 *
 * **视觉**：元音图上 F2 轴跨 2400 Hz、F1 轴只跨 800 Hz。
 * 同样 150 Hz 的容差，画出来 F2 方向只有 F1 方向的 1/3 宽 ——
 * 目标区变成一条窄缝，用户根本瞄不准。
 * 用音分的话，两个方向占的比例一样，是个正圆。
 *
 * ## 容差取多少
 *
 * ±120 音分 ≈ ±7%。这是「听起来就是这个元音」和
 * 「听起来是相邻元音」的分界附近。实测 LPC 在干净信号上的
 * 误差是 28 Hz（6 个合成元音平均），远小于这个容差。
 */
object VowelZone {

    /** 容差（音分）。 */
    const val TOLERANCE_CENTS = 120f

    /**
     * 实际频率相对目标频率偏了多少音分。
     *
     * 正数 = 偏高，负数 = 偏低。
     */
    fun deviationCents(actual: Float, target: Float): Float {
        if (actual <= 0f || target <= 0f) return 0f
        return (1200f * log2(actual.toDouble() / target.toDouble())).toFloat()
    }

    /**
     * 是否在目标区内（两个轴都要满足）。
     */
    fun isInZone(f1: Float, f2: Float, target: VowelTarget): Boolean {
        if (f1 <= 0f || f2 <= 0f) return false
        return abs(deviationCents(f1, target.f1)) <= TOLERANCE_CENTS &&
            abs(deviationCents(f2, target.f2)) <= TOLERANCE_CENTS
    }

    /**
     * 容差在某个频率上对应的 Hz 半径。
     *
     * 元音图上画目标区（椭圆）时用这个换算。
     */
    fun radiusHz(hz: Float): Float {
        if (hz <= 0f) return 0f
        val up = hz * 2f.pow(TOLERANCE_CENTS / 1200f)
        return up - hz
    }

    /**
     * 判断可信度时允许的偏差（音分）。
     *
     * 比容差宽得多 —— 这里问的不是"准不准"，
     * 而是"这个数字是不是 F2"。
     */
    const val CREDIBLE_CENTS = 300f

    /**
     * F2 的读数**可信吗**。
     *
     * ## 要解决什么问题
     *
     * 真信号里 F2 偶尔会整个消失（共振峰被噪声淹没，
     * 或者带宽超限被过滤掉）。这时按频率取第二个根，
     * 拿到的是 **F3** —— 界面上显示「F2 2407 Hz」，
     * 看着挺像回事，其实完全不对。
     *
     * 真机实测 /a/：期望 F2=1090，报出来的是 2407（那是 /a/ 的 F3）。
     *
     * ## 怎么判断
     *
     * 拿它跟**两个**参照比：
     * - 目标元音的 F2 —— 用户想发什么
     * - 识别出的元音的 F2 —— 用户实际上在发什么
     *
     * 两个都不沾边，就说明这个数字不是 F2。
     * （元音空间里 F1=724 附近的元音，F2 都在 1000–1900 之间，
     * 不会有 2400。）
     *
     * ## 为什么不能靠频带约束
     *
     * 试过「F1 决定了 F2 的范围」这种规则，行不通：
     * 元音空间里 F1 和 F2 的相关性很弱 ——
     * /a/ 是 (730, 1090)，/æ/ 是 (660, 1720)，
     * F1 差不多，F2 差了一倍多。
     */
    fun isF2Credible(
        measuredF2: Float,
        target: VowelTarget,
        recognized: VowelTarget?,
    ): Boolean {
        if (measuredF2 <= 0f) return false
        if (abs(deviationCents(measuredF2, target.f2)) <= CREDIBLE_CENTS) return true
        if (recognized != null &&
            abs(deviationCents(measuredF2, recognized.f2)) <= CREDIBLE_CENTS
        ) {
            return true
        }
        return false
    }
}

/**
 * 元音训练进度：连续保持在目标区里多久了。
 *
 * ## 测的是"连续保持"不是"累计"
 *
 * 累计的话，用户可以断断续续地凑够时间，对训练没意义 ——
 * 稳态元音训练要的恰恰是**连续稳定地保持**。
 *
 * ## 宽限期
 *
 * 允许掉出目标区 2 帧（32 ms）。
 * LPC 偶尔一帧跳偏是常事，不该因为这一帧就前功尽弃。
 */
class TargetTracker(
    /** 每帧多少毫秒（跳帧间隔）。 */
    private val hopMs: Int = 16,
    /** 允许连续掉出多少帧而不中断保持。 */
    private val graceFrames: Int = 2,
) {
    private var holdFrames = 0
    private var missFrames = 0

    /**
     * 更新一帧。
     *
     * @param inZone 这一帧是否在目标区内
     * @return 连续保持的秒数
     */
    fun update(inZone: Boolean): Float {
        if (inZone) {
            holdFrames++
            missFrames = 0
        } else {
            missFrames++
            if (missFrames > graceFrames) {
                holdFrames = 0
                missFrames = 0
            }
        }
        return holdFrames * hopMs / 1000f
    }

    /** 清零（换目标时）。 */
    fun reset() {
        holdFrames = 0
        missFrames = 0
    }
}
