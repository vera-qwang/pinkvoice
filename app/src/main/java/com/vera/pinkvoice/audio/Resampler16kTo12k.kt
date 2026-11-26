package com.vera.pinkvoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 把分析支路的 16 kHz 重采样到 12 kHz（比例 3 : 4）。
 *
 * ## 为什么要降
 *
 * LPC 共振峰分析在 **12 kHz** 上最准 —— 这是从 Praat 反推出来的：
 * Praat 会重采样到「2 × 共振峰上限」（默认上限 5 kHz → 10 kHz），
 * 实测 11–12 kHz 最好。在 16 kHz 上分析时 F1 的极点会被低频倾斜
 * 撑得很宽、常和邻居合并；降到 12 kHz 后极点分得更开。
 *
 * ## 怎么实现的
 *
 * 标准**多相**（polyphase）重采样，不真的插零：
 *
 * ```
 * 输出第 n 个样本 ← 上采样域位置 m = n × 4
 *                 ← 输入位置 base = m / 3，相位 phase = m % 3
 * out[n] = 3 · Σₖ h[phase + 3k] · in[base − k]
 * ```
 *
 * 零插值那一步乘的是零，直接跳过 —— 每个输出样本只花 `tapsPerPhase` 次乘加。
 */
class Resampler16kTo12k(
    /** 每相的抽头数（总共 3 倍于此）。 */
    private val tapsPerPhase: Int = 12,
) {
    private val up = 3
    private val down = 4
    private val totalTaps = tapsPerPhase * up

    private val h = designLowPass()

    /** 输出对输入的最大回溯量（抽头数 / 上采样倍数）。 */
    private val lookback: Int = (totalTaps + up - 1) / up

    /** 输入历史。buf[i] 对应绝对索引 bufStart + i。 */
    private var buf = FloatArray(4096)

    /**
     * buf[0] 的绝对索引。
     *
     * 初始是 **−lookback**：开头补了 lookback 个零当历史，
     * 否则第一个输出样本会因为「历史不够」永远算不出来。
     */
    private var bufStart: Long = (-lookback).toLong()

    private var len = lookback

    /** 下一个要产出的输出样本序号。 */
    private var nextOut = 0L

    private fun designLowPass(): FloatArray {
        // 在「上采样后」的等效采样率 48 kHz 上设计。
        // 截止放在 12 kHz 奈奎斯特的 90%（5.4 kHz），给过渡带留余量。
        val fc = 5400.0 / 48000.0
        val out = FloatArray(totalTaps)
        val mid = (totalTaps - 1) / 2.0
        var sum = 0.0
        for (i in 0 until totalTaps) {
            val t = i - mid
            val sinc = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
            val w = 0.54 - 0.46 * cos(2 * PI * i / (totalTaps - 1))
            out[i] = (sinc * w).toFloat()
            sum += out[i]
        }
        for (i in 0 until totalTaps) out[i] = (out[i] / sum).toFloat()
        return out
    }

    /**
     * 送入 16 kHz 样本，把能产出的 12 kHz 样本写进 `out`。
     *
     * @return 写入 out 的样本数
     */
    fun process(input: FloatArray, count: Int, out: FloatArray): Int {
        if (count <= 0) return 0
        ensureCapacity(len + count)
        System.arraycopy(input, 0, buf, len, count)
        len += count

        var written = 0
        while (written < out.size) {
            val m = nextOut * down
            val base = m / up
            val phase = (m % up).toInt()

            // 需要 in[base] .. in[base - lookback + 1]
            if (base - lookback + 1 < bufStart) {
                // 只可能出现在开头（已经补了零，理论上进不来）
                break
            }
            if (base >= bufStart + len) break   // 数据还不够

            var acc = 0f
            var k = 0
            var idx = phase
            while (idx < totalTaps) {
                val src = base - k - bufStart
                if (src < 0) break
                if (src >= len) break
                acc += h[idx] * buf[src.toInt()]
                k++
                idx += up
            }
            out[written++] = acc * up
            nextOut++
        }

        // 丢掉不再需要的历史（保留 lookback 个）
        val nextBase = nextOut * down / up
        val keepFrom = nextBase - lookback - 2
        if (keepFrom > bufStart) {
            val drop = minOf((keepFrom - bufStart).toInt(), len)
            if (drop > 0) {
                System.arraycopy(buf, drop, buf, 0, len - drop)
                len -= drop
                bufStart += drop
            }
        }
        return written
    }

    /** 清空。 */
    fun reset() {
        len = lookback
        bufStart = -lookback.toLong()
        nextOut = 0
        java.util.Arrays.fill(buf, 0, minOf(len, buf.size), 0f)
    }

    private fun ensureCapacity(need: Int) {
        if (buf.size >= need) return
        var n = buf.size
        while (n < need) n *= 2
        buf = buf.copyOf(n)
    }
}
