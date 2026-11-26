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
/**
 * F2 高于这个频率就"可疑"了 —— 已经进 F3 的地盘。
 *
 * 女声的 F2 能到 2800，所以这个门限不能定得太低。
 * 真正区分 /i/（F2=2290，正常）和「F2 丢了、拿 F3 顶上来」
 * 的是包络上 F1 之下还有没有显著脊。
 */
private const val SUSPICIOUS_F2_HZ = 2000f

/**
 * 人声 F1 的上限。
 *
 * 成年人的 F1 最高约 1000 Hz（开口最大的 /a/）。
 * 超过这个值说明**真的 F1 根丢了**，roots[0] 其实是 F2 ——
 * 再往后排就全部错位一格。
 *
 * 这个守卫是实测驱动的：用户 Praat 测出 F1=471，
 * App 却报 1411（=真实的 F2 1540），F2 报 2677（=真实的 F3 2885）。
 * 错位的结果看着"有数字"，比报未知更危险。
 */
private const val MAX_PLAUSIBLE_F1_HZ = 1100f

class LpcAnalyzer(
    private val sampleRate: Int = 16000,
    /**
     * LPC 阶数。
     *
     * 16 是**标定出来的**，而且是**两处标定**的结果：
     *
     * - 合成元音（干净信号）：12 阶最好（平均误差 38 Hz），16 阶 63 Hz
     * - **真机**（手机麦 + 蓝牙 + 房间）：12 阶会**整个丢掉 F2** ——
     *   真信号比合成信号"脏"，低频结构更杂，要多几个极点才拟合得出
     *   三个共振峰。实测 12 阶只有 F1 和 F3，16 阶才三个都在。
     *
     * 所以取 16：宁可多一点极点（后面有带宽/频带过滤兜底），
     * 也不能丢共振峰。
     */
    private val order: Int = 16,
    /**
     * 预加重系数。
     *
     * 0.7（约 +5 dB/oct）—— 标定出来的折中：
     * - 0（不加重）：低频压不住，反射系数接近 ±1，递推容易发散
     * - 0.97（+6 dB/oct）：太狠，/i/ 的 F1 直接消失
     * - 0.3–0.7：合成信号上误差都在 37–64 Hz 之间
     */
    private val preEmphasis: Float = 0.5f,
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

    /**
     * 诊断：所有根（**不���任何过滤**），连同频率和带宽。
     */
    fun debugAllRoots(): List<Triple<Float, Float, Float>> {
        if (!valid) return emptyList()
        val coeffs = DoubleArray(order + 1) { a[order - it].toDouble() }
        return PolynomialRoots.roots(coeffs).mapNotNull { r ->
            val zr = r.abs()
            if (zr < 1e-12) return@mapNotNull null
            val theta = r.arg()
            val hz = (theta / (2.0 * Math.PI) * sampleRate).toFloat()
            val bw = (-sampleRate * kotlin.math.ln(zr) / Math.PI).toFloat()
            Triple(hz, bw, zr.toFloat())
        }.sortedBy { it.first }
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
     * 用**包络挑峰**提取共振峰（前 3 个）。
     *
     * 和求根是两条独立的路，各有各的问题：
     *
     * - **求根**：理论上更准（极点就是共振峰），但反射系数接近 ±1 时
     *   递推会发散，根求不出来；而且极点略微不稳定时会被过滤掉。
     * - **挑峰**：不受递推发散影响，但周期声源会让包络上出现
     *   一堆几乎等高的起伏，容易挑错。
     *
     * 所以两条都保留 —— 实际用哪条，看标定结果。
     */
    fun extractFormantsByEnvelope(minProminenceDb: Float = 1.5f): Formants {
        if (!valid) return Formants(0f, 0f, 0f)
        val peaks = findPeaks(150f, 4000f, stepHz = 10f)
        if (peaks.isEmpty()) return Formants(0f, 0f, 0f)

        // 每个峰的突出度：峰顶与两侧最近谷底的高差
        val prom = FloatArray(peaks.size)
        for (i in peaks.indices) {
            val y = envelopeDb(peaks[i])
            var valley = y
            var left = peaks[i] - 10f
            while (left > 150f) {
                val v = envelopeDb(left)
                if (v < valley) valley = v
                if (v > y) break
                left -= 10f
            }
            var right = peaks[i] + 10f
            while (right < 4000f) {
                val v = envelopeDb(right)
                if (v < valley) valley = v
                if (v > y) break
                right += 10f
            }
            prom[i] = y - valley
        }

        /**
         * 自下而上取第一个显著峰。
         *
         * 不能取"频带内最高峰"：F1 的频带（200–1000）和 F2 的频带
         * （700–2800）是**重叠**的，按最高选会跳过 F1 直接选 F2。
         */
        fun firstSignificant(lo: Float, hi: Float): Float {
            for (i in peaks.indices) {
                val f = peaks[i]
                if (f < lo) continue
                if (f > hi) return 0f
                if (prom[i] < minProminenceDb) continue
                return f
            }
            return 0f
        }

        val f1 = firstSignificant(200f, 1000f)
        val f2 = firstSignificant(
            maxOf(700f, if (f1 > 0f) f1 + 200f else 700f),
            2800f,
        )
        val f3 = firstSignificant(
            maxOf(2200f, if (f2 > 0f) f2 + 200f else 2200f),
            3800f,
        )
        return Formants(f1, f2, f3)
    }

    /**
     * 提取共振峰（前 3 个）。
     *
     * 走的是**求根**那条路，不是在包络上挑峰 —— 理由见
     * [extractFormantsByRoots]。
     */
    /**
     * 一帧的完整诊断（给「核对 Praat」用）。
     *
     * 为什么要这个：只有把**原始的根列表和包络**列出来，
     * 才能判断「报出来的 F1 到底是真的 F1，还是 F2 顶替的」。
     * 光看界面上那两个数字永远判断不了。
     */
    data class FrameDiag(
        val roots: List<Formant>,
        /** 包络在若干频点的 dB 值。 */
        val envelope: List<Pair<Int, Float>>,
        val output: Formants,
    )

    /** 导出当前帧的诊断信息。 */
    fun diagnose(): FrameDiag {
        val env = (100..3600 step 100).map { it to envelopeDb(it.toFloat()) }
        return FrameDiag(extractFormantsByRoots(), env, extractFormants())
    }

    /**
     * **原始信号**的频谱（dB，50 Hz 一步，0–4000 Hz）。
     *
     * 比 LPC 包络有用得多：包络已经被 LPC 参数「解释」过了，
     * 从它反推不出「换成别的参数会怎样」。有了原始频谱，
     * 就能在电脑上离线重跑整套 LPC —— 阶数、预加重、带宽全试一遍，
     * 找出能在**真实声音**上保住 F1 的设置。
     *
     * 用 Goertzel 逐点算（81 个点 × 1024 样本），
     * 只在诊断模式下、每 16 帧跑一次，开销可以忽略。
     *
     * @param samples 原始样本（16 kHz 域，未预加重）
     */
    fun spectrumDb(samples: FloatArray, count: Int): List<Pair<Int, Float>> {
        if (count <= 0) return emptyList()
        val win = FloatArray(count) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (count - 1))).toFloat() * samples[i]
        }
        var peak = 1e-12f
        val mags = ArrayList<Pair<Int, Float>>(81)
        var f = 0
        while (f <= 4000) {
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
            val m = kotlin.math.sqrt(re * re + im * im).toFloat()
            if (m > peak) peak = m
            mags += f to m
            f += 50
        }
        return mags.map { (hz, m) ->
            hz to (20.0 * log10((m / peak + 1e-9).toDouble())).toFloat()
        }
    }

    fun extractFormants(): Formants {
        val roots = extractFormantsByRoots()
        if (roots.isEmpty()) return Formants(0f, 0f, 0f)

        // ---------------- F1 必须落在人声范围内 ----------------
        //
        // **真 F1 的根丢了呢？**
        //
        // 那就全部错位一格：roots[0] 其实是 F2、roots[1] 是 F3，
        // 界面上却报成「F1=1411 Hz」——
        // 实测就是这个现象（Praat 的 F1=471、F2=1540、F3=2885，
        // App 报 1411 / 2677，正好是 F2 / F3 往后挪了一格）。
        //
        // 错位比"没有读数"糟得多：用户会照着一个错的值练。
        // 所以这里宁可报未知。
        //
        // 上限 1100 Hz：成年人的 F1 最高约 1000（开口最大的 /a/）。
        // 真 F2 最低的 /u/ 也有 800 多，而 F2 一旦被当成 F1，
        // 报出来的值明显超过 1100。
        val f1 = roots[0].hz
        if (f1 > MAX_PLAUSIBLE_F1_HZ) return Formants(0f, 0f, 0f)

        var f2 = roots.getOrNull(1)?.hz ?: 0f
        var f3 = roots.getOrNull(2)?.hz ?: 0f

        // ---------------- F2 校验 ----------------
        //
        // **不能只按频率取前三个根。**
        //
        // 真信号里 F2 偶尔会整个消失（共振峰被噪声淹没，
        // 或者带宽超限被过滤掉）。这时候按频率取第二个根，
        // 拿到的是 **F3** —— 界面上显示「F2 2250 Hz」，
        // 看着挺像回事，其实完全不对。
        //
        // 真机实测 /u/（F1=300, F2=870, F3=2240）就是这样：
        // F2 根丢了，界面报 F2=2253，还把元音认成了「衣」。
        //
        // 判据：候选 F2 高得可疑（> 2000 Hz，已经进 F3 的地盘），
        // 而包络上 F1 之上、2000 Hz 以下**还有一个显著脊** ——
        // 那才是 F2，高根是 F3。
        //
        // 注意 /i/（F1=270, F2=2290）**不能**被误判：
        // 它的 470–2000 Hz 之间本来就没有共振峰，
        // 包络上找不到显著脊，所以 F2 保持 2290。
        if (f2 > SUSPICIOUS_F2_HZ) {
            val ridge = envelopeLocalMaxBetween(f1 + 250f, f2)
            if (ridge > 0f && envelopeDb(ridge) > envelopeDb(f2) + 1f) {
                f2 = ridge
                f3 = roots.getOrNull(1)?.hz ?: 0f
            }
        }
        return Formants(f1, f2, f3)
    }

    /**
     * 包络在 (loHz, hiHz) 区间里**最高的一个局部极大**的位置。
     *
     * 跟 [firstProminentPeak] 的区别：不看"突出度"，
     * 只看是不是局部极大。
     *
     * 突出度在这里不合适 —— LPC 包络在两个共振峰之间很平滑，
     * 谷底离峰顶可能只有 1–2 dB，用 1.5 dB 的门限会把
     * 真正的 F2 判成"不显著"（实测 /a/ 和 /ao/ 全被判丢了）。
     *
     * @return 频率，找不到返回 0
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
     * 包络上 [loHz, hiHz] 区间里的**第一个显著峰**。
     *
     * 「显著」= 突出度超过阈值（峰顶比两侧谷底高出多少）。
     * 周期声源会让包络起伏，靠「是不是局部极大」不够，
     * 得看它突不突出。
     *
     * @return 峰顶频率，找不到返回 0
     */
    private fun firstProminentPeak(
        loHz: Float,
        hiHz: Float,
        minProminenceDb: Float = 1.5f,
    ): Float {
        if (!valid) return 0f
        val peaks = findPeaks(loHz.coerceAtLeast(150f), hiHz, stepHz = 10f)
        for (p in peaks) {
            val y = envelopeDb(p)
            // 两侧最近谷底
            var valley = y
            var left = p - 10f
            while (left > 150f) {
                val v = envelopeDb(left)
                if (v < valley) valley = v
                if (v > y) break
                left -= 10f
            }
            var right = p + 10f
            while (right < 4000f) {
                val v = envelopeDb(right)
                if (v < valley) valley = v
                if (v > y) break
                right += 10f
            }
            if (y - valley >= minProminenceDb) return p
        }
        return 0f
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
        /**
         * 带宽上限。
         *
         * 700 Hz —— 这是用**真实录音对照 Praat** 标定出来的，
         * 不是拍脑袋定的。
         *
         * ## 为什么必须这么宽
         *
         * F1 的极点带宽天生就大。真信号里 F1 的能量弱、
         * 又叠在声门源的强低频倾斜上，LPC 只能用一个很宽的极点去拟合。
         *
         * 实测（用户真实录音，Praat 真值 F1=413）：
         * F1 的根找到了，在 436 Hz，**带宽 812 Hz**。
         * 带宽上限卡在 520 的话它被当成"噪声峰"丢掉 ——
         * 然后 roots[0] 变成真正的 F2，后面全部错位一格：
         * 界面报 F1=1411（其实是 F2）、F2=2677（其实是 F3）。
         *
         * 这就是「F1/F2 完全错误」的根因。
         *
         * ## 为什么不再是 400
         *
         * 教科书上的 400 适用于干净录音棚素材。
         * 手机麦 + 房间混响会把共振峰抹宽，400 一律丢光。
         *
         * ## 标定数据
         *
         * 真实录音（Praat 基准）：bw 520 → 误差 2156 Hz；
         * bw 700 → 误差 43 Hz；bw 900/1100 与 700 持平。
         * 合成元音（6 个）：bw 700 和 900 结果完全一致。
         * 取 700 是因为它在真实录音上略好，且更窄、更不容易放进伪根。
         */
        maxBandwidthHz: Float = 700f,
    ): List<Formant> {
        if (!valid) return emptyList()

        // 求**反转多项式**的根，而不是 LPC 多项式本身。
        //
        // LPC 多项式 A(z) = 1 + a₁z⁻¹ + … + aₚz⁻ᵖ，令 w = z⁻¹，
        // 它的根在**单位圆外**（稳定极点的倒数的倒数……），
        // 数值条件很差：Durand-Kerner 的初值在单位圆上、根在圆外，
        // 经常收敛不了 —— 真机上表现为大部分帧一个根都求不出来。
        //
        // 反转多项式 q(w) = wᵖ + a₁wᵖ⁻¹ + … + aₚ 的根
        // **就是极点本身**（|z| < 1，在单位圆内），
        // 初值也该取在圆内，条件好得多。
        //
        // 系数按升幂传：[aₚ, aₚ₋₁, …, a₁, 1]
        val coeffs = DoubleArray(order + 1) { a[order - it].toDouble() }
        val roots = PolynomialRoots.roots(coeffs)

        val out = ArrayList<Formant>(4)
        for (r in roots) {
            // r 就是极点 z
            val zr = r.abs()
            // 允许**略微不稳定**的极点（|z| 略大于 1）。
            //
            // LPC 拟合出的中间共振峰有时会落到单位圆外一点点。
            // 一刀切丢掉的话，F2 就经常整个消失（真机上实测：
            // 12 阶时 F1 有、F2 没有；16 阶时 F2 才出现）。
            // 反正后面还有频带和带宽两道过滤。
            if (zr < 1e-12 || zr > 1.05) continue
            val theta = r.arg()
            if (theta <= 0.0) continue               // 只取上半平面
            val hz = (theta / (2.0 * Math.PI) * sampleRate).toFloat()
            if (hz < minHz || hz > maxHz) continue
            // 带宽 = -fs·ln|z| / π（略微不稳时取绝对值）
            val bw = kotlin.math.abs((-sampleRate * kotlin.math.ln(zr) / Math.PI).toFloat())
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
