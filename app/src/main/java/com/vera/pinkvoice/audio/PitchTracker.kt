package com.vera.pinkvoice.audio

import kotlin.math.abs

/**
 * 带迟滞的音高跟踪器：把 YIN 的逐帧结果整理成稳定的音高轨迹。
 *
 * 真机实测的问题：底噪会让 YIN 冒出**单帧伪周期**（曲线上的竖直尖刺）。
 * 单纯用中值滤波会把离群点"抹匀"到相邻两帧，反而污染了真实值。
 *
 * 规则（歌声跟踪的标准做法）：
 *  - 新值与稳定值相差在 [toleranceCents] 以内 → 直接跟随（颤音、滑音都能跟上）；
 *  - 相差过大 → 挂起该值，需要连续 [takeoverFrames] 帧一致才真正切换
 *    （单帧离群点因此被完全丢弃，不会画到曲线上）；
 *  - 短暂无声（≤ [gapBridgeFrames] 帧）→ 保持上一个音高，避免曲线被切断。
 */
class PitchTracker(
    private val toleranceCents: Double = 60.0,
    private val takeoverFrames: Int = 3,
    private val gapBridgeFrames: Int = 2,
    /** 起音确认：连续多少帧一致才认定"开始发声"。 */
    private val onsetFrames: Int = 2,
) {
    private var stableHz = 0f
    private var pendingHz = 0f
    private var pendingCount = 0
    private var unvoicedRun = 0

    fun reset() {
        stableHz = 0f
        pendingHz = 0f
        pendingCount = 0
        unvoicedRun = 0
    }

    /** 输入一帧检测结果，返回稳定后的音高（0 表示无声）。 */
    fun accept(hz: Float, clarity: Float = 0f): Float {
        if (hz <= 0f) {
            unvoicedRun++
            if (unvoicedRun > gapBridgeFrames) {
                stableHz = 0f
                pendingHz = 0f
                pendingCount = 0
            }
            return stableHz
        }
        unvoicedRun = 0

        if (stableHz <= 0f) {
            // 起音确认：第一个有声帧往往是冲击瞬态（真机自检里表现为一下低频毛刺），
            // 需要连续两帧一致才认定起音，否则会画出一个假的低频尖峰。
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
                return stableHz
            }
            return 0f
        }

        val cents = abs(PitchMath.centsBetween(hz.toDouble(), stableHz.toDouble()))
        if (cents <= toleranceCents) {
            stableHz = hz
            pendingHz = 0f
            pendingCount = 0
            return stableHz
        }

        // 偏离较大：挂起，连续多帧一致才切换
        if (pendingHz > 0f &&
            abs(PitchMath.centsBetween(hz.toDouble(), pendingHz.toDouble())) <= toleranceCents
        ) {
            pendingCount++
        } else {
            pendingHz = hz
            pendingCount = 1
        }
        if (pendingCount >= takeoverFrames) {
            stableHz = hz
            pendingHz = 0f
            pendingCount = 0
        }
        return stableHz
    }
}
