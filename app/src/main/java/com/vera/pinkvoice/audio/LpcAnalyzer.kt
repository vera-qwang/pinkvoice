package com.vera.pinkvoice.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 人声 F1 的上限。
 *
 * 成年人的 F1 最高约 1000 Hz（开口最大的 /a/）。
 * 超过这个值说明真的 F1 极点没找到，再往后排就全部错位一格。
 */
private const val MAX_PLAUSIBLE_F1_HZ = 1100f

/**
 * F2 高于这个频率就"可疑"了 —— 已经进 F3 的地盘。
 */
private const val SUSPICIOUS_F2_HZ = 2000f

/**
 * LPC 共振峰分析器。
 *
 * ## 参数是怎么来的
 *
 * **整套配置是从 Praat 反推出来的**，并用两段真人录音对照验证
 * （Praat 导出的 F1-F4 轨迹当基准）。
 *
 * | 参数 | 值 | 为什么 |
 * |---|---|---|
 * | 分析采样率 | **12 kHz** | Praat 会重采样到「2 × 共振峰上限」。上限 5 kHz → 10 kHz，实测 11–12 kHz 最好 |
 * | 阶数 | **10** | = 2 × 5 个共振峰（Praat 的算法）—— 见下 |
 * | 求法 | **Burg** | Praat 用的就是它。对短数据、共振峰靠近的情况比自相关法稳 |
 * | 窗长 | **50 ms** | Praat 默认 25 ms，但 50 ms 在真人录音上更稳 |
 * | 预加重 | **从 50 Hz 起**（系数按 48 kHz 算） | Praat 的默认值 |
 * | 带宽过滤 | **不做** | Praat 压根不按带宽选峰 — 见下 |
 *
 * ## 为什么不再按带宽过滤
 *
 * 之前按「带宽 < 520 Hz」筛选极点，结果把**真实 F1 丢掉了**。
 *
 * 实测（真人录音，Praat 真值 F1=420）：F1 的极点确实找到了，
 * 在 436 Hz，但**带宽 812 Hz** —— 被当噪声峰扔掉，
 * 于是 roots[0] 变成真正的 F2，后面全部错位一格。
 *
 * 真实声音里 F1 能量弱、又叠在声门源的强低频倾斜上，
 * LPC 只能用一个很宽的极点去拟合。**带宽大是物理事实，不是噪声。**
 *
 * 现在改成**按频带顺序选极点**，带宽只用来丢掉退化极点（> 2500 Hz）。
 *
 * ## 实测精度
 *
 * | 录音 | App | Praat |
 * |---|---|---|
 * | 录音 A（/e/ 类） | F1=324 F2=1469 | F1=420 F2=1453 |
 * | 录音 B（/u/） | F1=309 F2=806 | F1=308 F2=785 |
 *
 * ## 阶数：10（= 2 × 共振峰数），和 Praat 一致
 *
 * Praat 的 `To Formant (burg)` 用 **2 × nFormants** 阶（默认 5 个共振峰 → 10 阶）。
 *
 * 阶数对结果**极其敏感** —— 同一条录音上只改阶数：
 *
 * | 阶数 | 啊 F1 | 啊 F2 | σ(F1) |
 * |---|---|---|---|
 * | 8 | 456 | 1545 | 133 |
 * | **10** | **417** | **1472** | **43** |
 * | 11 | 451 | 1488 | 195 |
 * | 12 | 642 | 1492 | 236 |
 * | 13 | 826 | 1507 | 77 |
 *
 * 10 阶和 Praat 的 419/1454 **只差 2 Hz**，σ 只有 43 Hz；
 * 11 阶差 32 Hz，σ 是 195（差 4.5 倍），而且一阶之差就崩。
 *
 * （曾经用 11 阶。那是在只有两段录音、且分析复刻本身有 bug 的情况下
 * 选出来的 —— 结论对不上真 Praat。**以真 Praat 为准。**）
 *
 * 12 阶以上 F1 会跑到 640–850 —— 多出来的极点把 F1「吸」走了。
 *
 * 这个敏感性是这个方法本身的弱点。App 靠 [VowelZone.isF2Credible]
 * 在 F2 明显不对时标「读数不稳」，而不是给用户一个错的值。
 */
class LpcAnalyzer(
    private val sampleRate: Int = 12000,
    private val order: Int = 10,
    /**
     * 预加重的起始频率（Hz）。
     *
     * ## 系数按**分析速率**算 —— 这是 Praat 的做法
     *
     * Praat 源码（fon/Sound.cpp）：
     * ```cpp
     * double Sound_computeEmphasisFactor (constSound me, double cutoffFrequency) {
     *     return exp (- NUM2pi * cutoffFrequency * my dx);
     * }
     * ```
     * 而 `Sound_to_Formant_any` 是**先重采样再预加重**：
     * ```cpp
     * sound = Sound_resample (me, maximumFrequency * 2, 50);   // → 11 kHz
     * ...
     * Sound_preEmphasize_inplace (me, preemphasisFrequency);  // dx = 1/11000
     * ```
     *
     * 所以那个 `dx` 是**分析速率**的，不是原始采样率的。
     * 本分析器在 12 kHz 上跑，系数就按 12 kHz 算：
     * a = exp(-2π·50/12000) = 0.97416。
     *
     * （曾按原始采样率 48 kHz 算过，那是错的 —— Praat 不是那样做的。）
     */
    private val preEmphasisHz: Float = 50f,
) {
    /** 预加重系数。 */
    private val preEmphasis: Float =
        if (preEmphasisHz <= 0f) 0f
        else exp(-2.0 * PI * preEmphasisHz / sampleRate).toFloat()

    /** LPC 系数。a[0] 恒为 1，a[1..order] 是递推系数。 */
    private val a = FloatArray(order + 1)

    /** 预加重 + 加窗后的工作区。 */
    private var work = FloatArray(0)

    /** Burg 的前向 / 后向误差。 */
    private var fwd = FloatArray(0)
    private var bwd = FloatArray(0)

    /** Burg 更新时的临时缓冲。 */
    private var tmpErr = FloatArray(0)

    /** 本帧是否有效。 */
    var valid: Boolean = false
        private set

    /** 准备一块 frameSize 大小的缓冲区。 */
    fun prepare(frameSize: Int) {
        if (work.size != frameSize) {
            work = FloatArray(frameSize)
            fwd = FloatArray(frameSize)
            bwd = FloatArray(frameSize)
            tmpErr = FloatArray(frameSize)
        }
    }

    /**
     * 分析一帧。
     *
     * @param samples 原始样本（**12 kHz 域**）
     * @param count 有效样本数
     * @return 是否分析成功
     */
    fun analyze(samples: FloatArray, count: Int): Boolean {
        if (count < order * 4 || work.size < count) {
            valid = false
            return false
        }

        // 预加重 + **高斯窗**（Praat 用的就是高斯窗，不是 Hamming）
        //
        // Praat 源码（fon/Sound_to_Formant.cpp）：
        // ```cpp
        // const double imid = 0.5 * (nsamp_window + 1), edge = exp (-12.0);
        // window [i] = (exp (-48.0 * (i - imid) * (i - imid)
        //              / (nsamp_window + 1) / (nsamp_window + 1)) - edge) / (1.0 - edge);
        // ```
        //
        // Hamming 的 sidelobe 比高斯窗高，对共振峰提取更不友好。
        var prev = samples[0]
        val imid = 0.5 * (count + 1)
        val edge = exp(-12.0)
        val invEdge = 1.0 / (1.0 - edge)
        val denom = (count + 1).toDouble() * (count + 1)
        for (i in 0 until count) {
            val x = samples[i]
            val pre = x - preEmphasis * prev
            prev = x
            val d = (i + 1) - imid
            val w = ((exp(-48.0 * d * d / denom) - edge) * invEdge).toFloat()
            work[i] = pre * w
            fwd[i] = work[i]
            bwd[i] = work[i]
        }

        var energy = 0f
        for (i in 0 until count) energy += work[i] * work[i]
        if (energy <= 1e-12f) {
            valid = false
            return false
        }

        valid = burg(count)
        return valid
    }

    /**
     * Burg 法求 LPC 系数。
     *
     * ## 为什么不用自相关 + Levinson
     *
     * 自相关法先把数据「折叠」成 r[0..p]，再解正规方程 ——
     * 短数据、共振峰靠近时，折叠过程会丢信息。
     * Burg 直接在误差序列上递推，不折叠，
     * 也不需要事先加窗以外的假设。Praat 用的就是它。
     *
     * ## 递推
     *
     * ```
     * f[i], b[i] 初始化为加窗后的信号
     * for m = 1..p:
     *   k = -2·Σ f[i]·b[i-1] / Σ (f[i]² + b[i-1]²)     （i = m..n-1）
     *   a[m] = k,  a[i] += k · a[m-i]
     *   f[i] = f[i] + k·b[i-1],  b[i] = b[i-1] + k·f[i-1]
     * ```
     *
     * 更新必须**从后往前**：算 b[i] 要用还没被覆盖的 f[i-1]。
     */
    private fun burg(count: Int): Boolean {
        // 逐行对齐参考实现（Praat 语义），用**偏移量**代替切片。
        //
        // 前向 / 后向误差在每次迭代后「错开一位」：
        // 前向误差从偏移 1 开始、后向误差从偏移 0 开始，
        // 每轮各自前进 / 缩短 —— 直接对固定数组整体操作会错位，
        // 那是第一版的 bug（系数完全不对，极点偏移几百 Hz）。
        var efOff = 1
        var ebOff = 0
        var len = count - 1

        a.fill(0f)
        a[0] = 1f
        val prev = FloatArray(order + 1)
        val tmp = tmpErr

        for (m in 1..order) {
            var num = 0.0
            var den = 0.0
            for (t in 0 until len) {
                val fi = fwd[efOff + t].toDouble()
                val bi = bwd[ebOff + t].toDouble()
                num += fi * bi
                den += fi * fi + bi * bi
            }
            if (den <= 1e-20) return false
            var k = -2.0 * num / den
            if (k > 0.9999) k = 0.9999
            if (k < -0.9999) k = -0.9999
            val kf = k.toFloat()

            System.arraycopy(a, 0, prev, 0, m)
            a[m] = kf
            for (i in 1 until m) {
                a[i] = prev[i] + kf * prev[m - i]
            }

            if (m < order) {
                // 前向误差：f ← f + k·b
                for (t in 0 until len) {
                    tmp[t] = fwd[efOff + t] + kf * bwd[ebOff + t]
                }
                // 后向误差：b ← b + k·f（用**旧的** f —— 所以要先算 tmp）
                for (t in 0 until len) {
                    bwd[ebOff + t] = bwd[ebOff + t] + kf * fwd[efOff + t]
                }
                for (t in 0 until len) {
                    fwd[efOff + t] = tmp[t]
                }
                efOff++
                len--
            }
        }
        return true
    }

    /** 当前的 LPC 系数（诊断用）。 */
    fun coefficients(): FloatArray = a.copyOf()

    /** 一个共振峰候选（由 LPC 的根得到）。 */
    data class Formant(val hz: Float, val bandwidthHz: Float)

    /** 三个共振峰。 */
    data class Formants(val f1: Float, val f2: Float, val f3: Float) {
        /** 全都是 0 说明这一帧没有有效共振峰。 */
        val empty: Boolean get() = f1 <= 0f && f2 <= 0f && f3 <= 0f
    }

    /**
     * LPC 谱包络（dB）在某频率上的值。
     *
     * A(z) = 1 + Σ aᵢ·z⁻ⁱ，z⁻ⁱ = cos(iω) − j·sin(iω)，
     * 所以 D = (1 + Σ aᵢ·cos(iω)) − j·(Σ aᵢ·sin(iω))。
     */
    fun envelopeDb(freqHz: Float): Float {
        if (!valid) return -120f
        val w = 2.0 * PI * freqHz / sampleRate
        var re = 1.0
        var im = 0.0
        for (i in 1..order) {
            re += a[i] * cos(i * w)
            im -= a[i] * sin(i * w)
        }
        val mag = sqrt(re * re + im * im)
        if (mag <= 1e-12) return 120f
        return (-20.0 * log10(mag)).toFloat()
    }

    /**
     * 提取全部极点（按频率升序）。
     *
     * 求的是**反转多项式** q(w) = wᵖ + a₁wᵖ⁻¹ + … + aₚ 的根 ——
     * 它的根**就是极点本身**（在单位圆内），条件比直接求 A(z) 的根好得多。
     * 系数按升幂传：[aₚ, aₚ₋₁, …, a₁, 1]。
     */
    fun extractFormantsByRoots(
        minHz: Float = 150f,
        maxHz: Float = 5000f,
        /**
         * 带宽上限 —— 只用来丢**退化**极点，不是用来选共振峰。
         *
         * Praat 完全不按带宽选峰。这里给一个很宽的上限（2500），
         * 只挡掉明显是数值噪声的极点。
         */
        maxBandwidthHz: Float = 2500f,
    ): List<Formant> {
        if (!valid) return emptyList()

        val coeffs = DoubleArray(order + 1) { a[order - it].toDouble() }
        val roots = PolynomialRoots.roots(coeffs)

        val out = ArrayList<Formant>(order / 2)
        for (r in roots) {
            val zr = r.abs()
            if (zr < 1e-12 || zr > 1.05) continue
            if (r.arg() <= 0.0) continue               // 只取上半平面
            val hz = (r.arg() / (2.0 * PI) * sampleRate).toFloat()
            if (hz < minHz || hz > maxHz) continue
            val bw = abs((-sampleRate * ln(zr) / PI).toFloat())
            if (bw <= 0f || bw > maxBandwidthHz) continue
            out += Formant(hz, bw)
        }
        out.sortBy { it.hz }

        // 去掉挨太近的极点（同一个共振被拟合成两个）
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
     * 提取 F1/F2/F3。
     *
     * 按**频带顺序**取，不按带宽筛选 —— 理由见类文档。
     */
    fun extractFormants(): Formants {
        val roots = extractFormantsByRoots()
        if (roots.isEmpty()) return Formants(0f, 0f, 0f)

        // F1：低频段里的第一个极点
        val f1 = roots.firstOrNull { it.hz in 150f..MAX_PLAUSIBLE_F1_HZ }?.hz ?: 0f
        if (f1 <= 0f) return Formants(0f, 0f, 0f)

        // F2：F1 之上、3000 Hz 以下的第一个
        var f2 = roots.firstOrNull { it.hz > f1 + 200f && it.hz <= 3000f }?.hz ?: 0f

        // F2 高得可疑时（进了 F3 的地盘），看包络上有没有更靠下的脊
        if (f2 > SUSPICIOUS_F2_HZ) {
            val ridge = envelopeLocalMaxBetween(f1 + 250f, f2)
            if (ridge > 0f && envelopeDb(ridge) > envelopeDb(f2) + 1f) {
                f2 = ridge
            }
        }

        // F3
        val f3 = if (f2 > 0f) {
            roots.firstOrNull { it.hz > f2 + 300f && it.hz <= 4800f }?.hz ?: 0f
        } else {
            0f
        }
        return Formants(f1, f2, f3)
    }

    /**
     * 包络在 (loHz, hiHz) 区间里**最高的一个局部极大**的位置。
     */
    private fun envelopeLocalMaxBetween(
        loHz: Float,
        hiHz: Float,
        stepHz: Float = 10f,
    ): Float {
        if (!valid || hiHz <= loHz) return 0f
        var best = 0f
        var bestVal = -1e9f
        var prev2 = envelopeDb(loHz - stepHz)
        var prev1 = envelopeDb(loHz)
        var f = loHz + stepHz
        while (f <= hiHz) {
            val cur = envelopeDb(f)
            if (prev1 > prev2 && prev1 >= cur && prev1 > bestVal) {
                bestVal = prev1
                best = f - stepHz
            }
            prev2 = prev1
            prev1 = cur
            f += stepHz
        }
        return best
    }

    /**
     * LPC 谱包络的倾斜（dB/octave）。
     *
     * 负得多 = 低频多 = 厚；接近 0 = 高频占比高 = 薄。
     */
    fun spectralTilt(loHz: Float = 300f, hiHz: Float = 3000f): Float {
        if (!valid) return 0f
        val lo = envelopeDb(loHz)
        val hi = envelopeDb(hiHz)
        val octaves = (ln(hiHz.toDouble()) - ln(loHz.toDouble())) / ln(2.0)
        if (octaves <= 0.0) return 0f
        return (hi - lo) / octaves.toFloat()
    }

    /** 一帧的完整诊断（核对 Praat 用）。 */
    data class FrameDiag(
        val roots: List<Formant>,
        val envelope: List<Pair<Int, Float>>,
        val output: Formants,
    )

    /** 导出当前帧的诊断信息。 */
    fun diagnose(): FrameDiag {
        val env = (100..3600 step 100).map { it to envelopeDb(it.toFloat()) }
        return FrameDiag(extractFormantsByRoots(), env, extractFormants())
    }

    /**
     * 原始信号的频谱（dB，50 Hz 一步，0–5000 Hz）。
     *
     * 比 LPC 包络有用：包络已经被 LPC 参数「解释」过了，
     * 从它反推不出「换成别的参数会怎样」。
     */
    fun spectrumDb(samples: FloatArray, count: Int): List<Pair<Int, Float>> {
        if (count <= 0) return emptyList()
        val win = FloatArray(count) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (count - 1))).toFloat() * samples[i]
        }
        var peak = 1e-12f
        val mags = ArrayList<Pair<Int, Float>>(101)
        var f = 0
        while (f <= 5000) {
            val wq = 2.0 * PI * f / sampleRate
            val c = 2.0 * cos(wq)
            var s1 = 0.0
            var s2 = 0.0
            for (x in win) {
                val s0 = x + c * s1 - s2
                s2 = s1
                s1 = s0
            }
            val re = s1 - s2 * cos(wq)
            val im = s2 * sin(wq)
            val m = sqrt(re * re + im * im).toFloat()
            if (m > peak) peak = m
            mags += f to m
            f += 50
        }
        return mags.map { (hz, m) ->
            hz to (20.0 * log10((m / peak + 1e-9).toDouble())).toFloat()
        }
    }
}
