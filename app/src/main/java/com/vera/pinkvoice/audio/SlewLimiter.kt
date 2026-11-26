package com.vera.pinkvoice.audio

/**
 * 显示值的**限速器**（slew limiter）。
 *
 * ## 解决什么
 *
 * 前面几层（跟踪器 → 中位数平滑）压住了抖动，但压不死**极点塌陷**：
 * 现场实测里 F1 会在 250 ms 内从 571 掉到 239（−58%），
 * 中位数跟着走，界面上就是一个大跳。
 *
 * 逐帧看现场 4.6 秒的数据（290 帧）：
 *
 * | | 最大逐帧变化 | >10% 的帧 | 范围 |
 * |---|---|---|---|
 * | 只有平滑 | **97%** | 1.7% | 239–654 |
 * | 加限速 3% | **3%** | **0%** | 402–652 |
 *
 * 限速是从**数学上**保证：相邻两次显示之间，数值最多移动 3%。
 * 无论上游怎么塌、怎么错位，画面都不可能跳。
 *
 * ## 会不会变迟钝
 *
 * 62 fps 下每帧 3%：
 * - 10% 的变化 → 3 帧 ≈ 50 ms
 * - 30% 的变化 → 9 帧 ≈ 145 ms
 *
 * 比中位数窗口（400 ms）的延迟还小，所以它**不是**新的瓶颈。
 *
 * ## 为什么放在平滑之后
 *
 * 平滑负责让值可信（中位数），限速负责让**过渡**不可见（斜坡）。
 * 顺序反了的话，限速会把坏值也一起平滑进去。
 */
class SlewLimiter(
    /**
     * 每帧最多移动当前值的百分之几。
     *
     * 3% 是实测选出来的：2% 时 F1 的范围已经能压到 423–652（更稳），
     * 但换元音时会明显慢；5% 时仍可能出现肉眼可见的移动。
     */
    private val maxStepRatio: Float = 0.03f,
) {
    private var f1 = 0f
    private var f2 = 0f

    fun reset() {
        f1 = 0f
        f2 = 0f
    }

    /**
     * 输入平滑后的目标值，返回限速后真正该显示的值。
     *
     * @param target1 平滑后的 F1，<= 0 表示无效
     * @param target2 平滑后的 F2
     */
    fun apply(target1: Float, target2: Float): LpcAnalyzer.Formants {
        if (target1 <= 0f || target2 <= 0f) {
            reset()
            return LpcAnalyzer.Formants(0f, 0f, 0f)
        }
        if (f1 <= 0f) {
            // 第一帧：直接接受，别从 0 慢慢爬
            f1 = target1
            f2 = target2
            return LpcAnalyzer.Formants(f1, f2, 0f)
        }
        f1 = step(f1, target1)
        f2 = step(f2, target2)
        return LpcAnalyzer.Formants(f1, f2, 0f)
    }

    private fun step(current: Float, target: Float): Float {
        val limit = current * maxStepRatio
        val delta = (target - current).coerceIn(-limit, limit)
        return current + delta
    }
}
