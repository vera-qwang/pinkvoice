package com.vera.pinkvoice.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LPC（线性预测编码）分析器。
 *
 * 用一组很短的递推系数近似**声道的频率响应**：
 *
 * ```
 * x[n] ≈ a₁·x[n-1] + a₂·x[n-2] + ... + aₚ·x[n-p]
 * ```
 *
 * 让预测误差最小，就得到这个滤波器的系数。它的极点就是**共振峰**。
 *
 * 声道在 5 kHz 以下只有 3–4 个共振峰，所以 14–16 阶就够用了
 * （经验公式 p ≈ 2 + fs/1000）。
 *
 * ## 为什么不直接求多项式的根
 *
 * 求根（伴随矩阵 + QR 迭代）是整个流程里最容易出错的地方 ——
 * 数值不稳，而且对混响敏感。
 *
 * 这里改成：**在频率轴上扫描包络值，找峰值 + 抛物线插值**。
 * 同样是 LPC 模型给出的包络，峰值位置就是共振峰，
 * 但没有求根的坑。
 *
 * ## 处理顺序（Rabiner & Schafer 的标准做法）
 *
 * 1. **预加重** `x'[n] = x[n] - 0.97·x[n-1]`
 *    —— 不做的话高频被低估，F1 会明显偏低（几十 Hz）。
 *    这一步最容易漏，但影响很大。
 * 2. **加窗**（Hamming）—— 让帧边界连续
 * 3. **自相关**
 * 4. **Levinson-Durbin 递推** → LPC 系数
 */
class LpcAnalyzer(
    private val sampleRate: Int = 16000,
    /**
     * LPC 阶数。
     *
     * 12 是**标定出来的**：用共振峰已知的合成元音扫了 12–24 阶，
     * 平均误差在 12 阶最小（38 Hz），阶数一高就冒出伪根
     * （16 阶平均误差 137 Hz，18 阶 223 Hz）。
     *
     * 原因：语音在 4 kHz 以下只有 4–5 个共振峰，约 8–10 个极点就够，
     * 多出来的极点会去拟合噪声，产生的根有模有样但不是共振峰。
     */
    private val order: Int = 12,
    /**
     * 预加重系数。
     *
     * 0.3（约 +2.5 dB/oct）—— 同样是标定出来的：
     * 0.3–0.7 都不错，0.97（+6 dB/oct）会把低频压平，
     * /i/ 的 F1 直接消失（270 Hz 附近包络上根本没有峰）。
     */
    private val preEmphasis: Float = 0.3f,
) {

    /** LPC 系数。a[0] 恒为 1，a[1..order] 是递推系数。 */
    private val a = FloatArray(order + 1)

    /** 预加重 + 加窗后的帧。 */
    private var work = FloatArray(0)

    /** 最近一次分析的包络是否有效。 */
    var valid: Boolean = false
        private set

    /** 预加重 + 加窗。 */
    fun prepare(frameSize: Int) {
        if (work.size != frameSize) {
            work = FloatArray(frameSize)
        }
    }

    /**
     * 分析一帧。
     *
     * @param samples 原始样本（16 kHz 域）
     * @param count 有效样本数
     * @return 是否分析成功
     */
    fun analyze(samples: FloatArray, count: Int): Boolean {
        if (count < order * 4 || work.size < count) return false

        // 1. 预加重 + 2. 加窗
        var prev = samples[0]
        for (i in 0 until count) {
            val x = samples[i]
            val pre = x - preEmphasis * prev
            prev = x
            // Hamming 窗
            val w = 0.54f - 0.46f * cos(2.0 * PI * i / (count - 1)).toFloat()
            work[i] = pre * w
        }

        // 3. 自相关（归一化，让不同帧的尺度可比）
        var r0 = 0f
        for (i in 0 until count) r0 += work[i] * work[i]
        if (r0 <= 1e-12f) {
            valid = false
            return false
        }
        val r = FloatArray(order + 1)
        for (m in 0..order) {
            var sum = 0f
            for (i in 0 until count - m) sum += work[i] * work[i + m]
            r[m] = sum / r0
        }

        // 4. Levinson-Durbin
        if (!levinson(r)) {
            valid = false
            return false
        }
        valid = true
        return true
    }

    /**
     * Levinson-Durbin 递推：从自相关求出 LPC 系数。
     *
     * 递推式（Rabiner & Schafer）：
     * ```
     * a[0] = 1
     * E = r[0]
     * for i = 1..p:
     *     k = -Σ_{j=0}^{i-1} a[j]·r[i-j]
     *     m = k / E
     *     a_new[i] = m
     *     for j = 1..i-1: a_new[j] = a[j] + m·a[i-i-j]... 见实现
     *     E = E·(1 - m²)
     * ```
     *
     * @return false 表示输入全零之类的不可能情况。
     */
    private fun levinson(r: FloatArray): Boolean {
        a[0] = 1f
        var e = r[0]
        if (e <= 0f) return false
        // 递推需要上一轮的系数，单独开一块避免边算边覆盖
        val prev = FloatArray(order + 1)

        for (i in 1..order) {
            var k = 0f
            for (j in 0 until i) k += a[j] * r[i - j]
            k = -k

            if (e <= 1e-20f) {
                // 能量已经耗尽，剩下的系数置零
                for (j in i..order) a[j] = 0f
                return true
            }
            // 反射系数必须 |m| < 1，否则预测误差 e 会变号，
            // 后面所有系数全错（真机上遇到静音帧、突发噪声就会这样）。
            // 夹到 ±0.9999 是标准做法。
            val m = (k / e).coerceIn(-0.9999f, 0.9999f)

            // 保存旧系数（更新时要用）
            System.arraycopy(a, 0, prev, 0, i)
            a[i] = m
            for (j in 1 until i) {
                a[j] = prev[j] + m * prev[i - j]
            }
            e *= (1f - m * m)
        }
        return true
    }

    /** 最近一次分析得到的 LPC 系数（标准约定，a[0] = 1）。 */
    fun coefficients(): FloatArray = a.copyOf()

    /**
     * 求包络在某个频率上的值（dB）。
     *
     * ```
     * H(z) = 1 / (1 - a₁z⁻¹ - a₂z⁻² - ... - aₚz⁻ᵖ)
     * ```
     *
     * @param freqHz 频率
     * @return 包络幅度（dB）；没分析过或失败时返回极小值
     */
    fun envelopeDb(freqHz: Float): Float {
        if (!valid) return -120f
        val w = 2.0 * PI * freqHz / sampleRate
        var re = 1.0
        var im = 0.0
        for (i in 1..order) {
            // Levinson-Durbin 求出的系数用的是标准约定：
            //
            //     A(z) = 1 + Σ aᵢ·z⁻ⁱ
            //
            // 而 z⁻ⁱ = cos(iω) − j·sin(iω)，所以
            //
            //     D = A(e^jω) = (1 + Σ aᵢ·cos(iω)) − j·(Σ aᵢ·sin(iω))
            //
            // 符号写反的话包络不会在共振峰处隆起，而是整个糊掉。
            re += a[i] * cos(i * w)
            im -= a[i] * sin(i * w)
        }
        val mag = sqrt(re * re + im * im)
        if (mag <= 1e-12) return 120f
        return (-20.0 * log10(mag)).toFloat()
    }

    /**
     * 在 [loHz, hiHz] 范围内找包络的峰。
     *
     * 扫描步长 [stepHz]，峰顶用**抛物线插值**细化——
     * 10 Hz 的步长对共振峰（带宽几十 Hz）本来够用，
     * 但插值后能到 1–2 Hz，稳态训练的抖动统计会好看很多。
     *
     * @return 峰的频率（Hz），按幅度从大到小排；找不到返回 0
     */
    fun findPeaks(loHz: Float, hiHz: Float, stepHz: Float = 10f): FloatArray {
        if (!valid) return FloatArray(0)
        val peaks = ArrayList<Float>(4)
        var prev2 = envelopeDb(loHz - stepHz)
        var prev1 = envelopeDb(loHz)
        var f = loHz + stepHz
        while (f <= hiHz) {
            val cur = envelopeDb(f)
            // 局部极大：前一个比前两个高、当前比前一个低
            if (prev1 > prev2 && prev1 >= cur) {
                // 峰在 prev1 那个点（频率 f - stepHz），
                // 三点分别是 (f-2step, f-step, f) —— x 和 y 要对上。
                peaks += refinePeak(f - 2f * stepHz, f - stepHz, f, prev2, prev1, cur)
            }
            prev2 = prev1
            prev1 = cur
            f += stepHz
        }
        return peaks.toFloatArray()
    }

    /**
     * 用峰顶三点做抛物线插值，细化峰的频率。
     *
     * y = a·(x-x₀)² + b·(x-x₀) + c，顶点是 x₀ - b/(2a)。
     * 这里的 x₀ 取中间那个点。
     */
    private fun refinePeak(
        xm1: Float,
        x0: Float,
        xp1: Float,
        ym1: Float,
        y0: Float,
        yp1: Float,
    ): Float {
        val denom = ym1 - 2f * y0 + yp1
        if (denom == 0f) return x0
        val delta = 0.5f * (ym1 - yp1) / denom
        // delta 只在 -1..1 之间合理（否则说明不是抛物线拟合的峰）
        if (delta < -1f || delta > 1f) return x0
        val step = (xp1 - xm1) / 2f
        return x0 + delta * step
    }

    /**
     * 一组共振峰。
     *
     * 0 表示这一帧没找到对应的峰（通常是浊音段之外）。
     */
    data class Formants(val f1: Float, val f2: Float, val f3: Float) {
        /** 全都是 0 说明这一帧没有有效共振峰。 */
        val empty: Boolean get() = f1 <= 0f && f2 <= 0f && f3 <= 0f
    }

    /**
     * 一个共振峰候选（由 LPC 的根得到）。
     *
     * @param hz 频率
     * @param bandwidthHz 带宽
     */
    data class Formant(val hz: Float, val bandwidthHz: Float)

    /**
     * 提取共振峰（前 3 个）。
     *
     * 走的是**求根**那条路，不是在包络上挑峰 —— 理由见
     * [extractFormantsByRoots]。
     */
    fun extractFormants(): Formants {
        val list = extractFormantsByRoots()
        return Formants(
            list.getOrNull(0)?.hz ?: 0f,
            list.getOrNull(1)?.hz ?: 0f,
            list.getOrNull(2)?.hz ?: 0f,
        )
    }

    /**
     * 用 **LPC 求根**提取共振峰。
     *
     * 这是语音处理里的标准做法（Praat 的 "To Formants" 就是这个）。
     * 比在包络上挑峰可靠得多 —— 周期声源会让包络上出现一堆
     * 几乎等高的起伏，挑峰会挑错，而每个根就是一个独立的共振。
     *
     * 过滤条件（照搬经典做法）：
     * - 频率在 [minHz, maxHz]
     * - 带宽 < maxBandwidth（太宽的是噪声峰，不是共振峰）
     * - 极点稳定（|z| < 1，即 |y| > 1）
     *
     * @return 按频率升序排列
     */
    fun extractFormantsByRoots(
        minHz: Float = 180f,
        maxHz: Float = 4000f,
        maxBandwidthHz: Float = 400f,
    ): List<Formant> {
        if (!valid) return emptyList()
        // p(y) = 1 + a₁y + a₂y² + … + aₚy^p，y = z⁻¹
        val coeffs = DoubleArray(order + 1) { a[it].toDouble() }
        val roots = PolynomialRoots.roots(coeffs)

        val out = ArrayList<Formant>(4)
        for (r in roots) {
            // 极点 z = 1/y
            if (r.abs() < 1e-12) continue
            val zr = 1.0 / r.abs()
            if (zr >= 1.0) continue          // 不稳定
            val theta = -r.arg()              // 极点相位
            if (theta <= 0.0) continue        // 只取上半平面
            val hz = (theta / (2.0 * Math.PI) * sampleRate).toFloat()
            if (hz < minHz || hz > maxHz) continue
            // 带宽 = -fs·ln|z| / π
            val bw = (-sampleRate * kotlin.math.ln(zr) / Math.PI).toFloat()
            if (bw <= 0f || bw > maxBandwidthHz) continue
            out += Formant(hz, bw)
        }
        out.sortBy { it.hz }

        // 去掉挨得太近的根。
        //
        // 阶数比实际共振峰多时，LPC 会多出一些"看起来很像"的根
        // （同一个共振被拟合成两个相邻极点）。它们通常更宽，
        // 所以保留更窄的那个。
        val deduped = ArrayList<Formant>(out.size)
        for (f in out) {
            val prev = deduped.lastOrNull()
            if (prev != null && f.hz - prev.hz < 90f) {
                if (f.bandwidthHz < prev.bandwidthHz) {
                    deduped[deduped.size - 1] = f
                }
            } else {
                deduped += f
            }
        }
        return deduped
    }

    /**
     * LPC 谱包络的**倾斜**（dB/octave）。
     *
     * 这是判断声音「厚/薄」的一个指标：
     * - 斜率负得多（陡）→ 低频多 → 厚、实、后共振
     * - 斜率接近 0（平）→ 高频占比高 → 薄、虚、前共振
     *
     * @param loHz 起始频率
     * @param hiHz 结束频率
     * @return dB/octave（负值 = 随频率上升而下降）
     */
    fun spectralTilt(loHz: Float = 300f, hiHz: Float = 3000f): Float {
        if (!valid) return 0f
        val lo = envelopeDb(loHz)
        val hi = envelopeDb(hiHz)
        // 每 octave 的分母（log2）
        val octaves = (ln(hiHz.toDouble()) - ln(loHz.toDouble())) / ln(2.0)
        if (octaves <= 0.0) return 0f
        return (hi - lo) / octaves.toFloat()
    }
}
