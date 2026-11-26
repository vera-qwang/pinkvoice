package com.vera.pinkvoice.audio

/**
 * F1/F2 的中位数平滑。
 *
 * ## 为什么需要
 *
 * LPC 每 16 ms 出一帧结果，帧与帧之间天然有几十 Hz 的抖动。
 * 直接显示最后一帧，数字会疯狂跳动 —— 看着像坏了。
 *
 * 更糟的是**识别结果**也跟着闪：「像『啊』」变成「像『衣』」
 * 再变回来，用户根本没法用。
 *
 * ## 为什么用中位数而不是平均值
 *
 * - 中位数对**离群值免疫**：偶发一帧离谱（清音、换气、爆破音）
 *   不会拉走结果，平均值会。
 * - 中位数比平均值更稳：噪声是双向的，平均值只是把噪声缩小，
 *   中位数直接砍掉了一半的波动。
 *
 * ## 延迟
 *
 * 窗口 N 帧 → 延迟约 N/2 帧。N=5（80 ms）→ 约 40 ms。
 * 比人耳的感知阈值稍大，但换来的是读数不再跳动。
 * 对"看清自己在哪个位置"来说，**稳比快重要**。
 */
class FormantSmoother(
    /**
     * 窗口帧数。
     *
     * **17 帧 ≈ 272 ms。** 这个值是从真人录音上量出来的，不是拍脑袋。
     *
     * ## 为什么要这么长
     *
     * 有些元音的 F1 极点位置**本身就是模糊的** —— 比如录音 A，
     * F1 并进了低频滚降，逐帧值在 234–495 Hz 之间游走
     * （F2/F3 却很稳，1419–1505）。中位数是对的（386，Praat 420），
     * 但逐帧值必然乱跳。
     *
     * 实测五段真人录音，不同窗口下「显示值跳动 >10% 的帧占比」：
     *
     * | 窗口 | 延迟 | A | E | I | O | U |
     * |---|---|---|---|---|---|---|
     * | 5 | 80 ms | 7% | 0 | 0 | 0 | 0 |
     * | 9 | 144 ms | 3% | 0 | 0 | 0 | 0 |
     * | 13 | 208 ms | 2% | 0 | 0 | 0 | 0 |
     * | **17** | **272 ms** | **0%** | 0 | 0 | 0 | 0 |
     *
     * 而中位数偏差几乎不变（−28 ~ +5 Hz），**精度没有为了稳定而牺牲**。
     *
     * ## 272 ms 的延迟可以接受吗
     *
     * 训练场景是「保持一个元音 1–3 秒看是否到位」，
     * 272 ms 的延迟远小于这个时间尺度。
     * 换元音时数字会慢慢滑过去，但那正是用户想看到的过渡。
     */
    private val window: Int = 17,

    /**
     * 信号中断多少帧后才清空。
     *
     * 以前**任何一帧无效就整个清空** —— 一个坏帧就把 272 ms 的历史
     * 全扔掉，中位数从头再来，画面立刻开始抖。
     * 现在短暂无效直接**保持原值**（和 [PitchTracker] 的补洞逻辑一致），
     * 连续断这么久才真的清空。
     */
    private val gapFrames: Int = 6,
) {
    private var unvoicedRun = 0
    private val f1Buf = ArrayDeque<Float>(window)
    private val f2Buf = ArrayDeque<Float>(window)

    /**
     * 加一帧，返回平滑后的共振峰。
     *
     * @param f1Hz 实测 F1（Hz），<= 0 视为无效
     * @param f2Hz 实测 F2（Hz），<= 0 视为无效
     */
    fun add(f1Hz: Float, f2Hz: Float): LpcAnalyzer.Formants {
        if (f1Hz <= 0f || f2Hz <= 0f) {
            unvoicedRun++
            if (unvoicedRun > gapFrames) {
                reset()
                return LpcAnalyzer.Formants(0f, 0f, 0f)
            }
            // 短暂中断 → 保持上一个平滑值
            return if (f1Buf.isEmpty()) {
                LpcAnalyzer.Formants(0f, 0f, 0f)
            } else {
                LpcAnalyzer.Formants(median(f1Buf), median(f2Buf), 0f)
            }
        }
        unvoicedRun = 0
        f1Buf.addLast(f1Hz)
        f2Buf.addLast(f2Hz)
        while (f1Buf.size > window) {
            f1Buf.removeFirst()
            f2Buf.removeFirst()
        }
        return LpcAnalyzer.Formants(
            median(f1Buf),
            median(f2Buf),
            0f,
        )
    }

    /** 清空（信号中断时调用，避免留着过期数据）。 */
    fun reset() {
        f1Buf.clear()
        f2Buf.clear()
        unvoicedRun = 0
    }

    private fun median(buf: ArrayDeque<Float>): Float {
        val arr = buf.toFloatArray()
        arr.sort()
        return arr[arr.size / 2]
    }
}
