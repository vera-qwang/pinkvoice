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
    /** 窗口帧数。5 × 16 ms = 80 ms。 */
    private val window: Int = 5,
) {
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
            reset()
            return LpcAnalyzer.Formants(0f, 0f, 0f)
        }
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
    }

    private fun median(buf: ArrayDeque<Float>): Float {
        val arr = buf.toFloatArray()
        arr.sort()
        return arr[arr.size / 2]
    }
}
