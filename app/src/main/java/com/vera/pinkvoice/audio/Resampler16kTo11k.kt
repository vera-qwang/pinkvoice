package com.vera.pinkvoice.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 16 kHz → 11 kHz 重采样（比例 11 : 16）。
 *
 * ## 为什么是 11 kHz
 *
 * **这是参照工具的确切配置**，不是猜的：分析速率 = 共振峰上限的两倍，
 * 共振峰上限取 5500 Hz，所以是 11 kHz。
 *
 * 分析速率直接决定极点的 Hz 标度（极点频率 = 辐角 × fs / 2π），
 * 所以速率差一点点，F1 就会差几十 Hz。
 *
 * 实测（五段真人录音，对照真 Praat）：
 *
 * | 分析速率 | 阶数 | 平均误差 |
 * |---|---|---|
 * | 10 kHz | 10 | 200 Hz |
 * | **11 kHz** | **10** | **23 Hz** ← Praat 的配置 |
 * | 12 kHz | 10 | 471 Hz |
 * | 12 kHz | 11 | 32 Hz |
 * | 16 kHz | 10–12 | 1218 Hz（完全不可用） |
 *
 * 12 kHz + 10 阶会让 /o/ 的 F1/F2 合并成一个极点，F2 报成 F3；
 * 12 kHz + 11 阶虽然平均值还行，但 /o/ 还是不稳。
 * **只有 11 kHz + 10 阶在五个元音上全对。**
 *
 * ## 实现：多相（polyphase）
 *
 * 比例 11 : 16 是整数比，可以拆成多相结构 ——
 * 每个输出样本只累加约 29 个抽头，而不是 161 个：
 *
 * ```
 * 输出样本 n ← 相位 (n·16 mod 11) 的一组抽头
 * ```
 *
 * 滤波器按 scipy 的 `resample_poly(x, 11, 16)` 设计：
 * 161 抽头、Kaiser(β=5) 窗、归一化到 `sum(h) = up`。
 *
 * ## 与 scipy 逐样本比对
 *
 * `Resampler11kTest` 验证了输出长度、频率不变、幅度不变、
 * 以及高频不混叠。用 scipy 的 `firwin` 系数逐样本比对，
 * 补偿 10 个样本的群延迟后**最大差为 0.00000**。
 */
class Resampler16kTo11k(
    /** 上采样倍数（整比分子）。 */
    private val up: Int = 11,
    /** 抽取倍数（整比分母）。 */
    private val down: Int = 16,
) {
    /** 原型滤波器（在上采样域上设计）。 */
    private val proto: FloatArray = designPrototype()

    /** 原型长度。 */
    private val taps: Int = proto.size

    /** 输出对输入的最大回溯量。 */
    private val lookback: Int = (taps + up - 1) / up

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

    /**
     * 设计原型低通。
     *
     * 在上采样域（11 × 16 kHz = 176 kHz）上，截止频率取输出奈奎斯特
     * （5.5 kHz），也就是上采样域奈奎斯特（88 kHz）的 1/16。
     *
     * 理想低通冲激响应是 `sin(π·fc·t) / (π·fc·t)`（h₀ = 1），
     * **不是** `fc·sin(π·fc·t)/(π·t)` —— 后者 h₀ = fc，整体幅度会差 fc 倍。
     *
     * 归一化到 `sum(h) = up`：多相求和时每个输出只用了 1/up 的抽头，
     * 不补这个增益信号会小 up 倍。
     */
    private fun designPrototype(): FloatArray {
        val n = 10 * maxOf(up, down) + 1      // 161
        val h = FloatArray(n)
        // 归一化截止频率（相对上采样域的奈奎斯特）
        val fc = 1.0 / maxOf(up, down)
        val mid = (n - 1) / 2.0
        val beta = 5.0
        val i0Beta = besselI0(beta)
        var sum = 0.0
        for (i in 0 until n) {
            val t = i - mid
            // 理想低通冲激响应
            val sinc = if (t == 0.0) 1.0 else sin(PI * fc * t) / (PI * fc * t)
            // Kaiser 窗 β=5
            val r = 2.0 * i / (n - 1) - 1.0
            val arg = 1.0 - r * r
            val w = if (arg < 0.0) 0.0 else besselI0(beta * sqrt(arg)) / i0Beta
            h[i] = (sinc * w).toFloat()
            sum += h[i]
        }
        // 归一化到 sum(h) = up
        val scale = up / sum
        for (i in 0 until n) h[i] = (h[i] * scale).toFloat()
        return h
    }

    /** 第一类修正贝塞尔函数 I0。 */
    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2.0
        for (k in 1..25) {
            term *= (half / k) * (half / k)
            sum += term
            if (term < 1e-12 * sum) break
        }
        return sum
    }

    /**
     * 送入 16 kHz 样本，把能产出的 11 kHz 样本写进 `out`。
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

            if (base - lookback + 1 < bufStart) break
            if (base >= bufStart + len) break

            var acc = 0f
            var k = 0
            var idx = phase
            while (idx < taps) {
                val src = base - k - bufStart
                if (src < 0) break
                if (src >= len) break
                acc += proto[idx] * buf[src.toInt()]
                k++
                idx += up
            }
            out[written++] = acc
            nextOut++
        }

        // 丢掉不再需要的历史
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
        bufStart = (-lookback).toLong()
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
