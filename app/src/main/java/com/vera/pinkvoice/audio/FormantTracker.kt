package com.vera.pinkvoice.audio

import kotlin.math.abs

/**
 * 共振峰跟踪器：压住离群帧，但不阻碍真正的换音。
 *
 * ## 要解决什么
 *
 * 中位数滤波只能挡住**孤立**的坏帧。
 * 真机实测：/啊/ 的 F1/F2 大部分时候很准（F1 ±22 Hz、F2 ±30 Hz），
 * 但会**连续几帧**跳到完全错的位置 ——
 * 实测见过 F1 708→196、F2 1062→2405（2405 是 /啊/ 的 F3）。
 *
 * 连续 3 帧以上都错的话，中位数窗口（5 帧）就被带偏了。
 *
 * ## 做法
 *
 * 两级：
 *
 * 1. **离群帧忽略** —— 新值跟当前值差超过 20% 就先不认，
 *    继续用当前值显示。
 * 2. **真换音要认** —— 连续 3 帧都指向同一个新位置，
 *    才切换过去（≈ 48 ms 延迟）。
 *
 * 这样两种情况都对：
 * - 突发坏帧 → 被忽略，读数纹丝不动
 * - 用户真的换了个元音 → 48 ms 后跟上，不会有「卡住不动」的感觉
 *
 * ## 为什么不做成无限期忽略
 *
 * 无限期忽略的话，用户从 /啊/ 换成 /衣/ 时界面就不动了 ——
 * 那是更糟的体验。
 */
class FormantTracker(
    /**
     * 与当前值差多少算「接近」（相对差）。
     *
     * 20% 覆盖了实测的跳变幅度（F1 708→196 是 −72%），
     * 又比真正的元音切换小得多（/啊/→/衣/ 的 F1 是 −63%，
     * 但那种切换是持续 several 帧的，靠第 2 级跟上）。
     */
    private val acceptRatio: Float = 0.20f,

    /**
     * 连续几帧指向同一个新位置才切换。
     *
     * 3 帧 = 48 ms。够滤掉突发噪声，又不至于让换音显得迟钝。
     */
    private val switchFrames: Int = 3,

    /**
     * 连续多少帧无效才清空状态。
     *
     * 以前任何一帧无效就 reset()，一个坏帧把跟踪状态全扔掉。
     */
    private val gapFrames: Int = 6,
) {
    private var currentF1 = 0f
    private var currentF2 = 0f

    // 候选新位置
    private var candidateF1 = 0f
    private var candidateF2 = 0f
    private var candidateCount = 0

    /** 连续无效帧数。短暂无声不清状态，连续断了才清。 */
    private var unvoicedRun = 0

    /**
     * 加一帧，返回应该显示的值。
     *
     * @param f1Hz 实测 F1，<= 0 视为无效
     * @param f2Hz 实测 F2，<= 0 视为无效
     */
    fun add(f1Hz: Float, f2Hz: Float): LpcAnalyzer.Formants {
        if (f1Hz <= 0f || f2Hz <= 0f) {
            // 以前这里直接 reset()：**一个坏帧就丢掉整个跟踪状态**，
            // 下一帧变成「第一个值」被直接接受 —— 曲线立刻开始抖。
            // 现在短暂无效保持原值，连续断 [gapFrames] 帧才清空。
            unvoicedRun++
            if (unvoicedRun > gapFrames) {
                reset()
                return LpcAnalyzer.Formants(0f, 0f, 0f)
            }
            return LpcAnalyzer.Formants(currentF1, currentF2, 0f)
        }
        unvoicedRun = 0

        // 第一个值：直接接受
        if (currentF1 <= 0f) {
            currentF1 = f1Hz
            currentF2 = f2Hz
            return LpcAnalyzer.Formants(currentF1, currentF2, 0f)
        }

        // 接近当前值 → 直接接受
        if (relativeDiff(f1Hz, currentF1) <= acceptRatio &&
            relativeDiff(f2Hz, currentF2) <= acceptRatio
        ) {
            currentF1 = f1Hz
            currentF2 = f2Hz
            candidateCount = 0
            return LpcAnalyzer.Formants(currentF1, currentF2, 0f)
        }

        // 远 —— 可能是离群帧，也可能是真的换音
        if (candidateCount > 0 &&
            relativeDiff(f1Hz, candidateF1) <= acceptRatio &&
            relativeDiff(f2Hz, candidateF2) <= acceptRatio
        ) {
            candidateCount++
            if (candidateCount >= switchFrames) {
                // 连续几帧都指向这里 → 认了
                currentF1 = candidateF1
                currentF2 = candidateF2
                candidateCount = 0
            }
        } else {
            // 新的候选
            candidateF1 = f1Hz
            candidateF2 = f2Hz
            candidateCount = 1
        }
        // 还没切换就继续用当前值
        return LpcAnalyzer.Formants(currentF1, currentF2, 0f)
    }

    /** 清空（信号中断）。 */
    fun reset() {
        currentF1 = 0f
        currentF2 = 0f
        unvoicedRun = 0
        candidateF1 = 0f
        candidateF2 = 0f
        candidateCount = 0
    }

    private fun relativeDiff(a: Float, b: Float): Float {
        if (b <= 0f) return Float.MAX_VALUE
        return abs(a - b) / b
    }
}
