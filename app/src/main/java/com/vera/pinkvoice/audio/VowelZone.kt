package com.vera.pinkvoice.audio

import kotlin.math.abs
import kotlin.math.log2

/**
 * 元音的**目标区域**。
 *
 * ## 它是什么
 *
 * 一个以「女性化目标」为中心、覆盖统计范围的**区域**，
 * 不是「标准元音在图上的那个点」。
 *
 * 真实说话人的共振峰散布很宽 —— 同样的女性化程度，
 * 不同人的 F1/F2 能差上百 Hz。
 * 所以目标是区域，落在区域里就算达标。
 *
 * ## 与「元音身份」是两回事
 *
 * [VowelRecognizer] 回答「你在发哪个元音」（舌位前后高低）。
 * 这里回答「你的共振峰空间离女性化目标还有多远」。
 *
 * 两个问题都要问，但**不能混为一谈** ——
 * 落到标准元音的点 ≠ 女性化练好了。
 */
object VowelZone {

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
     * 是否在目标区域内（两个轴都要满足）。
     */
    fun isInZone(f1: Float, f2: Float, target: VowelTarget): Boolean {
        if (f1 <= 0f || f2 <= 0f) return false
        return abs(f1 - FeminineTarget.centerF1(target)) <= FeminineTarget.radiusF1(target) &&
            abs(f2 - FeminineTarget.centerF2(target)) <= FeminineTarget.radiusF2(target)
    }

    /**
     * 判断可信度时允许的偏差（音分）。
     *
     * 比区域容差宽得多 —— 这里问的不是"准不准"，
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
     * 跟**目标元音**的 F2 比 —— 偏差超过 [CREDIBLE_CENTS] 就不是 F2。
     *
     * 曾经也拿「识别出的元音」当第二参照，但那个有漏洞：
     * 识别结果本身可能就是被坏读数带偏的，两个错误凑巧对上，
     * 反而把坏读数判成了可信。
     */
    fun isF2Credible(
        measuredF2: Float,
        target: VowelTarget,
    ): Boolean {
        if (measuredF2 <= 0f) return false
        val tF2 = FeminineTarget.centerF2(target)
        return abs(deviationCents(measuredF2, tF2)) <= CREDIBLE_CENTS
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
