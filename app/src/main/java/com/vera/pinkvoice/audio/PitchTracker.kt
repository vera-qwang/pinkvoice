package com.vera.pinkvoice.audio

import kotlin.math.abs

/**
 * 带迟滞的音高跟踪器：把 YIN 的逐帧结果整理成稳定的音高轨迹。
 *
 * 真机实测的问题：底噪会让 YIN 冒出**单帧伪周期**（曲线上的竖直尖刺）。
 * 单纯用中值滤波会把离群点"抹匀"到相邻两帧，反而污染了真实值。
 *
 * ## 规则
 *
 * 1. 新值与稳定值相差在 [toleranceCents] 以内 → 直接跟随。
 *    颤音、滑音都能逐帧跟上（真实颤音每帧只走几 cent）。
 * 2. 相差过大 → 挂起该值，需要连续多帧一致才真正切换。
 *    单帧离群点因此被完全丢弃，不会画到曲线上。
 * 3. 短暂无声（≤ [gapBridgeFrames] 帧）→ 保持上一个音高，避免曲线被切断。
 *
 * ## 跳变越大，要求的确认帧数越多
 *
 * 这是从真机录下来的 11 万帧数据里总结出来的。YIN 会时不时把 f0 报成
 * f0/2 或 2×f0（**八度误判**），而且这种误判常常连续好几帧、清晰度还不低
 * （0.57~0.86），看着像是真的。按固定的 3 帧规则，第三帧一到稳定值就整个
 * 跳到低八度，曲线随之出现一个又深又窄的"倒钩"——那才是真正刺眼的波动。
 *
 * 实测八度误判的持续长度分布是 3 / 4 / 4 / 6 / 7 / 8 帧（最长 128 ms），
 * 所以大跳变要 10 帧（160 ms）才认。
 *
 * 分级而不是一刀切，是因为**唱歌时的音高变化是滑过去的**：
 * 滑音每步只走几十 cent，走第 1 步时就被规则 1 直接跟上了，
 * 完全不受确认帧数的影响。只有"跳变"（一步就跳出去）才会被延迟，
 * 而那本来就不该是真实演唱里常见的事。
 *
 * @param takeoverFrames 小跳变（≤ 400 cent）要连续多少帧一致才接管
 * @param midTakeoverFrames 中等跳变（400~700 cent）要连续多少帧一致才接管
 * @param largeTakeoverFrames 大跳变（> 700 cent，含八度）要连续多少帧一致才接管
 * @param onsetFrames 起音确认：连续多少帧一致才认定"开始发声"
 */
class PitchTracker(
    private val toleranceCents: Double = 60.0,
    private val takeoverFrames: Int = 3,
    private val midTakeoverFrames: Int = 6,
    private val largeTakeoverFrames: Int = 10,
    private val gapBridgeFrames: Int = 2,
    /**
     * 起音确认：连续多少帧一致才认定"开始发声"。
     *
     * 早先只要 2 帧——真机数据里就栽过：唱到 157 Hz 时，起音那两帧
     * YIN 给的是 75.1 / 75.7 Hz（低八度），两帧一致就把稳定值定在了
     * 低八度，曲线跟着往下一扎。要 3 帧才认。
     */
    private val onsetFrames: Int = 3,
) {
    private var stableHz = 0f
    private var pendingHz = 0f
    private var pendingCount = 0
    private var unvoicedRun = 0

    /** 挂起候选的跳变档位——档位变了就得重新计数。 */
    private var pendingTier = 0

    fun reset() {
        stableHz = 0f
        pendingHz = 0f
        pendingCount = 0
        unvoicedRun = 0
        pendingTier = 0
    }

    /** 输入一帧检测结果，返回稳定后的音高（0 表示无声）。 */
    fun accept(hz: Float, clarity: Float = 0f): Float {
        if (hz <= 0f) {
            unvoicedRun++
            if (unvoicedRun > gapBridgeFrames) {
                stableHz = 0f
                pendingHz = 0f
                pendingCount = 0
                pendingTier = 0
            }
            return stableHz
        }
        unvoicedRun = 0

        if (stableHz <= 0f) {
            // 起音确认：第一个有声帧往往是冲击瞬态（真机自检里表现为一下低频毛刺），
            // 需要连续几帧一致才认定起音，否则会画出一个假的低频尖峰。
            if (pendingHz > 0f &&
                abs(PitchMath.centsBetween(hz.toDouble(), pendingHz.toDouble())) <= toleranceCents
            ) {
                pendingCount++
            } else {
                pendingHz = hz
                pendingCount = 1
            }
            if (pendingCount >= onsetFrames) {
                stableHz = pendingHz
                pendingHz = 0f
                pendingCount = 0
                pendingTier = 0
                return stableHz
            }
            return 0f
        }

        val delta = PitchMath.centsBetween(hz.toDouble(), stableHz.toDouble())
        if (abs(delta) <= toleranceCents) {
            stableHz = hz
            pendingHz = 0f
            pendingCount = 0
            pendingTier = 0
            return stableHz
        }

        // 偏离较大：挂起，连续多帧一致才切换。
        // 跳得越大要的帧数越多——八度误判就是这样被挡住的。
        val tier = tierOf(delta)
        if (pendingHz > 0f &&
            pendingTier == tier &&
            abs(PitchMath.centsBetween(hz.toDouble(), pendingHz.toDouble())) <= toleranceCents
        ) {
            pendingCount++
        } else {
            pendingHz = hz
            pendingCount = 1
            pendingTier = tier
        }
        if (pendingCount >= framesNeeded(tier)) {
            stableHz = hz
            pendingHz = 0f
            pendingCount = 0
            pendingTier = 0
        }
        return stableHz
    }

    /** 跳变档位：1 = 小跳变，2 = 中等，3 = 大跳变（含八度）。 */
    private fun tierOf(deltaCents: Double): Int {
        val d = abs(deltaCents)
        return when {
            d <= SMALL_JUMP_CENTS -> 1
            d <= MID_JUMP_CENTS -> 2
            else -> 3
        }
    }

    private fun framesNeeded(tier: Int): Int = when (tier) {
        1 -> takeoverFrames
        2 -> midTakeoverFrames
        else -> largeTakeoverFrames
    }

    companion object {
        /** 小跳变上限（cent）：400 ≈ 3.5 个半音。 */
        private const val SMALL_JUMP_CENTS = 400.0

        /** 中等跳变上限（cent）：700 ≈ 6 个半音。 */
        private const val MID_JUMP_CENTS = 700.0
    }
}
