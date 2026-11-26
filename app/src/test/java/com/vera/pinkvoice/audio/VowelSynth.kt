package com.vera.pinkvoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 合成元音（测试用）。
 *
 * ## 频谱比例必须是真实的
 *
 * 朴素的「声门源 → 级联三个共振器」合出来的 F2 会比 F1 低 **26 dB**。
 * 真实元音不是这样 —— 三个共振峰的高度是接近的
 * （F1 最强，F2 低 5–15 dB，F3 再低一点）。
 *
 * 用不真实的信号做验证，结论会跑偏：
 * F2 只比 F1 低 26 dB 时，经过笔记本喇叭和房间之后它直接掉进噪声底，
 * LPC 自然测不到 —— 但那是**信号的问题**，不是算法的问题。
 *
 * 真实声道的共振峰峰值增益取决于该处的反射和损耗，
 * 不是级联里"前一级把后一级喂弱了"那么简单。
 * 所以这里在级联之后做一次峰值均衡，把比例调回真实的样子。
 */
internal object VowelSynth {

    const val SAMPLE_RATE = 16000

    /** 共振峰的目标相对电平（dB，相对 F1）。 */
    val FORMANT_LEVELS_DB = doubleArrayOf(0.0, -6.0, -10.0)

    /**
     * 合成一个元音。
     *
     * @param f0 基频
     * @param formants 共振峰 [F1, F2, F3]
     * @param bandwidths 对应带宽
     */
    fun synth(
        f0: Double,
        formants: DoubleArray,
        bandwidths: DoubleArray = doubleArrayOf(60.0, 90.0, 120.0),
        seconds: Double = 0.6,
    ): FloatArray {
        val n = (SAMPLE_RATE * seconds).toInt()
        val flow = glottalFlow(f0, n)
        // 辐射出去的是声门流的**导数**（唇端辐射 +6 dB/oct）。
        // 这一步不做的话频谱形状不对。
        val radiated = DoubleArray(n)
        for (i in 1 until n) radiated[i] = flow[i] - flow[i - 1]

        var y = radiated
        for (k in formants.indices) {
            y = resonator(y, formants[k], bandwidths[k])
        }
        // 把共振峰的比例调成真实人声的样子
        y = equalizeFormants(y, formants)

        var peak = 1e-9
        for (v in y) peak = maxOf(peak, kotlin.math.abs(v))
        return FloatArray(n) { (y[it] / peak * 0.5).toFloat() }
    }

    /** Rosenberg 声门流脉冲（频谱约 −12 dB/oct，零点不与共振峰打架）。 */
    private fun glottalFlow(f0: Double, n: Int): DoubleArray {
        val out = DoubleArray(n)
        val period = SAMPLE_RATE / f0
        for (i in 0 until n) {
            val p = (i % period) / period
            out[i] = when {
                p < 0.4 -> {
                    val t = p / 0.4
                    3.0 * t * t - 2.0 * t * t * t
                }
                p < 0.56 -> {
                    val t = (p - 0.4) / 0.16
                    1.0 - (1.0 - t) * (1.0 - t)
                }
                else -> 0.0
            }
        }
        return out
    }

    /** 二阶共振器。 */
    private fun resonator(input: DoubleArray, freq: Double, bw: Double): DoubleArray {
        val r = exp(-PI * bw / SAMPLE_RATE)
        val theta = 2.0 * PI * freq / SAMPLE_RATE
        val c = 2.0 * r * cos(theta)
        val r2 = r * r
        val out = DoubleArray(input.size)
        for (n in 0 until input.size) {
            val y1 = if (n >= 1) out[n - 1] else 0.0
            val y2 = if (n >= 2) out[n - 2] else 0.0
            out[n] = input[n] + c * y1 - r2 * y2
        }
        return out
    }

    /** 把三个共振峰拉到真实比例。 */
    private fun equalizeFormants(y: DoubleArray, formants: DoubleArray): DoubleArray {
        val start = (y.size - 4096) / 2
        val seg = y.copyOfRange(start, start + 4096)
        val cur = formants.map { levelAt(seg, it) }
        var out = y
        for (k in formants.indices) {
            val want = cur[0] * 10.0.pow(FORMANT_LEVELS_DB[k] / 20.0)
            val gain = 20.0 * log10((want + 1e-12) / (cur[k] + 1e-12))
            out = peakingEq(out, formants[k], gain, q = 6.0)
        }
        return out
    }

    /** 单频点幅度（Goertzel）。 */
    private fun levelAt(seg: DoubleArray, freq: Double, winHz: Double = 60.0): Double {
        var best = 0.0
        var f = maxOf(100.0, freq - winHz)
        while (f <= freq + winHz) {
            val wq = 2.0 * PI * f / SAMPLE_RATE
            val c = 2.0 * cos(wq)
            var s1 = 0.0
            var s2 = 0.0
            for (x in seg) {
                val s0 = x + c * s1 - s2
                s2 = s1
                s1 = s0
            }
            val re = s1 - s2 * cos(wq)
            val im = s2 * sin(wq)
            val v = sqrt(re * re + im * im)
            if (v > best) best = v
            f += 5.0
        }
        return best
    }

    /** 峰值均衡滤波器（RBJ cookbook）。 */
    private fun peakingEq(
        x: DoubleArray,
        freq: Double,
        gainDb: Double,
        q: Double,
    ): DoubleArray {
        if (kotlin.math.abs(gainDb) < 0.01) return x
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / SAMPLE_RATE
        val alpha = sin(w0) / (2.0 * q)
        val b0 = 1 + alpha * a
        val b1 = -2.0 * cos(w0)
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2.0 * cos(w0)
        val a2 = 1 - alpha / a
        val b0n = b0 / a0
        val b1n = b1 / a0
        val b2n = b2 / a0
        val a1n = a1 / a0
        val a2n = a2 / a0
        val y = DoubleArray(x.size)
        for (n in x.indices) {
            val x1 = if (n >= 1) x[n - 1] else 0.0
            val x2 = if (n >= 2) x[n - 2] else 0.0
            val y1 = if (n >= 1) y[n - 1] else 0.0
            val y2 = if (n >= 2) y[n - 2] else 0.0
            y[n] = b0n * x[n] + b1n * x1 + b2n * x2 - a1n * y1 - a2n * y2
        }
        return y
    }
}
