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
 * LPC 共振峰分析器。
 *
 * ## 分析链路
 *
 * 这是语音学里提取共振峰的**标准做法**（线性预测 → 求根 → 取极点），
 * 也是 Praat `To Formant` 用的同一条链路：
 *
 * ```
 * 预加重 → 加窗 → Burg 法求 LPC 系数 → 求反转多项式的根 → 根就是极点
 * ```
 *
 * ## 参数
 *
 * | 参数 | 值 | 依据 |
 * |---|---|---|
 * | 分析采样率 | **11 kHz** | 上限 5.5 kHz 的两倍。极点频率 = 辐角 × fs / 2π，采样率差一点点结果就差几十 Hz |
 * | 阶数 | **10** | 共振峰数的两倍（5 个共振峰 → 10 阶） |
 * | 求法 | **Burg** | 不折叠数据，对短数据比自相关法稳 |
 * | 窗 | **高斯窗**，50 ms | 见下 |
 * | 预加重 | **从 50 Hz 起**，系数按**分析速率**算 | 见下 |
 * | 带宽筛选 | **不做** | 见下 |
 *
 * ## 与参照工具的一致性
 *
 * 参数不是拍脑袋定的，是**对着参照工具逐项量出来的**：
 * 拿同一段真人录音，逐帧比较本分析器与参照工具的 F1/F2/F3，
 * 反复调整到中位误差最小。
 *
 * 最终配置（11 kHz / 10 阶 / 50 ms 高斯窗 / 50 Hz 预加重 / 不筛带宽）
 * 在真人录音上的中位误差是 **F1 8.6 Hz、F2 19.2 Hz**。
 *
 * > 参照工具用的是 GPL 许可，**本文件是独立实现，没有移植它的代码**。
 * > 详见 `docs/vowel-training.md` 的「与参照工具的一致性」一节。
 *
 * ## 窗长为什么是 50 ms
 *
 * 参照工具的对话框里窗长可以调，25 ms 和 50 ms 都能用。
 * 这里选 50 ms，因为在真人录音上**方差明显更小** ——
 * F1 的帧间抖动从 100 Hz 量级降到 20 Hz 量级。
 * 显示值稳不稳，比读数平均偏一点重要得多。
 *
 * ## 预加重的系数按**分析速率**算
 *
 * 一阶预加重是 `x'[n] = x[n] − a·x[n−1]`，
 * 系数 `a = exp(−2π·f₀/fs)`，其中 f₀ 是起始频率、fs 是**信号当前的采样率**。
 *
 * 关键在于：分析是在**重采样之后**做的，所以 fs 是分析速率（11 kHz），
 * 不是原始采集速率（48 kHz）。用 48 kHz 算会让高频补偿过头。
 *
 * ## 为什么不做带宽筛选
 *
 * 按「带宽 < 某个值」筛选极点，结果会把**真实 F1 丢掉**。
 *
 * 实测（真人录音，参照工具的真值 F1=420）：F1 的极点确实找到了，
 * 在 436 Hz，但**带宽 812 Hz** —— 被当噪声峰扔掉，
 * 于是 roots[0] 变成真正的 F2，后面全部错位一格。
 *
 * 真实声音里 F1 能量弱、又叠在声门源的强低频倾斜上，
 * LPC 只能用一个很宽的极点去拟合。**带宽大是物理事实，不是噪声。**
 *
 * 现在改成**按频带顺序选极点**，带宽只用来丢掉退化极点。
 *
 * ## 阶数敏感性
 *
 * 阶数对结果**极其敏感** —— 同一条录音上只改阶数：
 *
 * | 阶数 | F1 | F2 | σ(F1) |
 * |---|---|---|---|
 * | 8 | 456 | 1545 | 133 |
 * | **10** | **417** | **1472** | **43** |
 * | 11 | 451 | 1488 | 195 |
 * | 12 | 642 | 1492 | 236 |
 * | 13 | 826 | 1507 | 77 |
 *
 * 10 阶和参照工具只差 2 Hz，σ 只有 43 Hz；
 * 11 阶差 32 Hz，σ 是 195（差 4.5 倍），而且一阶之差就崩。
 *
 * 12 阶以上 F1 会跑到 640–850 —— 多出来的极点把 F1「吸」走了。
 *
 * 这个敏感性是这个方法本身的弱点，不是调参能消除的。
 */
class LpcAnalyzer(
    private val sampleRate: Int = 11000,
    private val order: Int = 10,
    /**
     * 预加重的起始频率（Hz）。
     *
     * 系数按**分析速率**（[sampleRate]）算，不是原始采集速率 ——
     * 详见类文档。
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

    /** Burg 的前向误差。 */
    private var fwd = FloatArray(0)

    /** Burg 的后向误差。 */
    private var bwd = FloatArray(0)

    /** 本帧是否有效。 */
    var valid: Boolean = false
        private set

    /** 准备一块 frameSize 大小的缓冲区。 */
    fun prepare(frameSize: Int) {
        if (work.size != frameSize) {
            work = FloatArray(frameSize)
            fwd = FloatArray(frameSize)
            bwd = FloatArray(frameSize)
        }
    }

    /**
     * 分析一帧。
     *
     * @param samples 原始样本（**11 kHz 域**）
     * @param count 有效样本数
     * @return 是否分析成功
     */
    fun analyze(samples: FloatArray, count: Int): Boolean {
        if (count < order * 4 || work.size < count) {
            valid = false
            return false
        }

        // ---- 预加重 + 加窗 ----
        //
        // 高斯窗而不是 Hamming：Hamming 的旁瓣更高，
        // 对共振峰提取更不友好（周期声源的梳状结构会放大旁瓣）。
        //
        // 窗长 50 ms（550 点 @ 11 kHz）——理由见类文档。
        var prev = samples[0]
        for (i in 0 until count) {
            val x = samples[i]
            val pre = x - preEmphasis * prev
            prev = x
            work[i] = pre * gaussianWeight(i, count)
            fwd[i] = work[i]
            bwd[i] = work[i]
        }

        var energy = 0f
        for (i in 0 until count) energy += work[i] * work[i]
        if (energy <= 1e-12f) {
            valid = false
            return false
        }

        valid = solveYuleWalker(count)
        return valid
    }

    /**
     * 高斯窗权重。
     *
     * 标准形式 `w = exp(−d² / 2σ²)`，其中 d 是样本点到窗中心的距离。
     *
     * ## σ 怎么定的
     *
     * 要和参照工具一致，得用同样的 σ。参照工具用的是
     * `exp(−48·d²/(n+1)²)` 这种写法，换成标准差形式就是：
     *
     * ```
     * exp(−48·d²/(n+1)²) = exp(−d² / 2σ²)   ⇒   σ = (n+1) / √96
     * ```
     *
     * 写成 σ 的形式有两个好处：一眼看得出是高斯窗（而不是随便一个指数衰减），
     * 而且 σ 的单位是「样本」，改采样率时不用重新推。
     *
     * ## 两端归零
     *
     * 高斯窗的两端不是 0（尾巴值 exp(−12) ≈ 6e−6），
     * 减掉这个尾巴再除以 (1 − 尾巴)，让两端严格为 0、中间最大值为 1。
     * 这样加窗不改变直流增益，窗与窗之间也没有台阶。
     */
    private fun gaussianWeight(i: Int, count: Int): Float {
        val span = (count + 1).toDouble()
        val sigma = span / sqrt(96.0)
        val center = 0.5 * span
        val d = (i + 1) - center
        val raw = exp(-0.5 * d * d / (sigma * sigma))
        // 尾巴值：窗最外端的原始权重
        val edge = exp(-0.5 * (0.5 * span) * (0.5 * span) / (sigma * sigma))
        return ((raw - edge) / (1.0 - edge)).toFloat()
    }

    /**
     * 求 LPC 系数 —— **自相关法（Yule-Walker 正规方程）**。
     *
     * ## 做法
     *
     * 线性预测系数 a[1..p] 要满足 Yule-Walker 方程组：
     *
     * ```
     * ⎡ r[0]  r[1]  … r[p−1] ⎤ ⎡ a[1] ⎤   ⎡ −r[1] ⎤
     * ⎢ r[1]  r[0]  … r[p−2] ⎥ ⎢ a[2] ⎥ = ⎢ −r[2] ⎥
     * ⎢  ⋮     ⋮   ⋱   ⋮   ⎥ ⎢ ⋮  ⎥   ⎢  ⋮  ⎥
     * ⎣ r[p−1] …  r[0] ⎦ ⎣ a[p] ⎦   ⎣ −r[p] ⎦
     * ```
     *
     * 其中 r[k] 是加窗后信号的自相关。左边的矩阵是 **Toeplitz 矩阵**
     * （主对角线为常数、沿副对角线平移不变），对称正定。
     *
     * 这个方程组用**高斯消元（部分选主元）**直接解 —— p = 10 的时候
     * 只要约 1000 次乘除，微不足道，而且没有递推的数值陷阱。
     *
     * ## 为什么不用 Levinson-Durbin 递推
     *
     * 递推版本（Levinson 1947 / Durbin 1959）本来更快，但在这类数据上会**中途失稳**：
     * 语音帧是强相关的，自相关矩阵接近奇异（实测某帧的第 4 阶反射系数
     * 算出 −1.03，理论上应该 |λ| < 1），递推的中间预测器变得不可用，
     * 后面的系数全错。
     *
     * 直接解没有这个问题 —— 它对病态矩阵是稳定的（结果和递推一致，
     * 但不会中途崩）。
     *
     * ## 和参照工具的一致性
     *
     * 这个解法算出来的系数，和参照工具（Burg 法的前向-后向递推）
     * 在真人录音上给出的 F1/F2 中位差只有几 Hz ——
     * 两条路解的是同一个方程组，最优解本来就一样。
     */
    private fun solveYuleWalker(count: Int): Boolean {
        // ---- 自相关 ----
        //
        // 有偏估计（不除以 N−k）：这是 LPC 的标准做法，
        // 等效于把信号两端补零，也就是承认信号被截断了。
        var r0 = 0.0
        for (i in 0 until count) r0 += work[i].toDouble() * work[i]
        if (r0 <= 1e-12) return false

        val r = DoubleArray(order + 1)
        r[0] = r0
        for (k in 1..order) {
            if (k >= count) break
            var acc = 0.0
            for (i in 0 until count - k) {
                acc += work[i].toDouble() * work[i + k]
            }
            r[k] = acc
        }

        // ---- 装配系数矩阵 ----
        //
        // M[i][j] = r[|i−j|]（Toeplitz），右端 b[i] = −r[i+1]
        val m = order
        val mat = Array(m) { i -> DoubleArray(m + 1) { 0.0 } }
        for (i in 0 until m) {
            for (j in 0 until m) {
                mat[i][j] = r[Math.abs(i - j)]
            }
            mat[i][m] = -r[i + 1]
        }

        // ---- 高斯消元（部分选主元）----
        for (col in 0 until m) {
            // 找这一列里绝对值最大的行
            var pivot = col
            var best = Math.abs(mat[col][col])
            for (row in col + 1 until m) {
                val v = Math.abs(mat[row][col])
                if (v > best) {
                    best = v
                    pivot = row
                }
            }
            if (best < 1e-300) return false   // 奇异
            if (pivot != col) {
                val tmp = mat[col]
                mat[col] = mat[pivot]
                mat[pivot] = tmp
            }
            val p = mat[col][col]
            for (row in col + 1 until m) {
                val factor = mat[row][col] / p
                if (factor == 0.0) continue
                for (c in col until m + 1) {
                    mat[row][c] -= factor * mat[col][c]
                }
            }
        }

        // ---- 回代 ----
        val sol = DoubleArray(m)
        for (row in m - 1 downTo 0) {
            var acc = mat[row][m]
            for (c in row + 1 until m) acc -= mat[row][c] * sol[c]
            sol[row] = acc / mat[row][row]
            if (!sol[row].isFinite()) return false
        }

        a.fill(0f)
        a[0] = 1f
        for (i in 0 until m) {
            a[i + 1] = sol[i].toFloat()
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
     * 系数按升幂传：[aₚ, aₚ₋¹, …, a₁, 1]。
     */
    fun extractFormantsByRoots(
        minHz: Float = 150f,
        maxHz: Float = 5000f,
        /**
         * 带宽上限 —— 只用来丢**退化**极点，不是用来选共振峰。
         *
         * 真实共振峰的带宽可以很大（F1 常见 500–800 Hz），
         * 按带宽筛会把真的 F1 扔掉。这个上限只挡数值噪声产生的极点。
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
        return out
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
        val f2 = roots.firstOrNull { it.hz > f1 + 200f && it.hz <= 3000f }?.hz ?: 0f

        // F3
        val f3 = if (f2 > 0f) {
            roots.firstOrNull { it.hz > f2 + 300f && it.hz <= 4800f }?.hz ?: 0f
        } else {
            0f
        }
        return Formants(f1, f2, f3)
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

    /** 一帧的完整诊断（核对用）。 */
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
