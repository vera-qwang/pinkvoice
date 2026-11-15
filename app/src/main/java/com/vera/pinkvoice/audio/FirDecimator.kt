package com.vera.pinkvoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 48 kHz -> 16 kHz 的低通抽取器。
 *
 * 录音仍然是 48 kHz（保证返听音质），只有分析支路降到 16 kHz（YIN 足够，
 * 且运算量只有 1/9）。降采样前必须先低通，否则 > 8 kHz 的谐波会混叠折回
 * 基带，典型后果是 YIN 出现倍频/半频误判。
 *
 * 滤波器：Blackman 窗 sinc 低通 FIR，抽头数 127，截止约 6.8 kHz，
 * 阻带从约 7.8 kHz 开始，压在 8 kHz 以下。
 */
class FirDecimator(
    private val decimation: Int = 3,
    private val cutOffRatio: Double = 6800.0 / 48000.0,
    private val taps: Int = 127,
) {
    private val coeffs: FloatArray = designLowPass(cutOffRatio, taps)
    private val history = FloatArray(taps)
    private var histPos = 0
    private var phase = 0

    /** 输出直流隔离（一阶高通），避免 YIN 被偏置影响。 */
    private var dcPrevIn = 0f
    private var dcPrevOut = 0f

    /** FIR 群延迟（输入采样点），用于对齐时间戳。 */
    val groupDelayInputSamples: Int = (taps - 1) / 2

    /**
     * 处理 [count] 个 48 kHz 样本，把抽取后的 16 kHz 样本写入 [out]。
     * @return 写入 out 的样本数
     */
    fun process(input: FloatArray, count: Int, out: FloatArray): Int {
        var outCount = 0
        for (i in 0 until count) {
            history[histPos] = input[i]
            histPos = if (histPos + 1 == taps) 0 else histPos + 1
            if (++phase < decimation) continue
            phase = 0

            var sum = 0f
            var idx = histPos
            for (k in 0 until taps) {
                sum += coeffs[k] * history[idx]
                idx = if (idx + 1 == taps) 0 else idx + 1
            }

            // 一阶直流隔离: y[n] = x[n] - x[n-1] + R * y[n-1]
            val hp = sum - dcPrevIn + 0.995f * dcPrevOut
            dcPrevIn = sum
            dcPrevOut = hp

            if (outCount < out.size) out[outCount++] = hp
        }
        return outCount
    }

    fun reset() {
        history.fill(0f)
        histPos = 0
        phase = 0
        dcPrevIn = 0f
        dcPrevOut = 0f
    }

    private companion object {
        fun designLowPass(fcNorm: Double, n: Int): FloatArray {
            val h = FloatArray(n)
            val m = (n - 1) / 2
            var sum = 0.0
            for (k in 0 until n) {
                val x = k - m
                val sinc = if (x == 0) 2.0 * fcNorm else sin(2.0 * PI * fcNorm * x) / (PI * x)
                val w = 0.42 -
                    0.5 * cos(2.0 * PI * k / (n - 1)) +
                    0.08 * cos(4.0 * PI * k / (n - 1))
                val v = sinc * w
                h[k] = v.toFloat()
                sum += v
            }
            if (sum != 0.0) {
                for (k in 0 until n) h[k] = (h[k] / sum).toFloat()
            }
            return h
        }
    }
}
