package com.vera.pinkvoice.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * YIN 基频检测（de Cheveigné & Kawahara, 2002），实时单声道。
 *
 * 工作在 16 kHz 分析域：窗口 1024 (64 ms)、跳帧 256 (16 ms)，
 * 因此可以稳定覆盖到 75 Hz（女性/儿童音域下限附近），
 * 而 64 ms 窗口足以容纳 75 Hz 的 4.5 个周期。
 *
 * 输出：基频 Hz（0 表示未检出）、清晰度 clarity（0..1，1 - cmnd 最小值）、
 * 窗口 RMS。
 */
class YinPitchDetector(
    private val sampleRate: Int = 16000,
    private val windowSize: Int = 1024,
    private val hopSize: Int = 256,
    private val minFreq: Double = 75.0,
    private val maxFreq: Double = 500.0,
    private val threshold: Double = 0.12,
    /**
     * 没找到低于 [threshold] 的 τ 时是否仍接受结果，以及接受门槛。
     *
     * 真机实测的教训：这一档管得太紧（0.35）会让真实但信噪比不高的声音
     * （比如手机扬声器放出的自检音被麦克风远距离拾取）大量帧被判成无声，
     * 曲线断成一段段；管得太松（0.6）又会把底噪画成尖刺。
     * 取 0.5 —— 单帧离群点交给 [PitchTracker] 处理，这里只保证不丢帧。
     */
    private val fallbackThreshold: Double = 0.5,
    /**
     * 八度修正的判定比值：cmnd[2τ] 必须小于 cmnd[τ] 的这个倍数才认为 τ 是半个周期。
     * 纯音的比值接近 1，0.5 留了足够余量。
     */
    private val octaveRatio: Double = 0.5,
) {
    companion object {
        /**
         * 失配能量占比下限：低于此值说明候选 τ 处已经几乎完美重复
         * （真正的基频，或纯音的浮点噪声），不允许再做八度修正。
         */
        private const val MISMATCH_FLOOR = 0.002f
    }
    private val maxTau = min((sampleRate / minFreq).toInt(), windowSize / 2)
    private val minTau = max((sampleRate / maxFreq).toInt(), 2)

    private val buf = FloatArray(windowSize + maxTau)
    private val diff = FloatArray(maxTau + 1)
    private val cmnd = FloatArray(maxTau + 1)
    private var fill = 0

    /** 分析帧中心相对"最新样本"的滞后（16 kHz 采样点）。 */
    val analysisLagSamples: Int = maxTau + windowSize / 2

    /** 每秒输出帧数。 */
    val framesPerSecond: Int = sampleRate / hopSize

    fun reset() {
        fill = 0
    }

    /**
     * 追加分析域样本。每累积 hop 个样本做一次检测。
     * @param onFrame 回调 (hz, clarity, rms)
     */
    fun process(
        input: FloatArray,
        count: Int,
        onFrame: (hz: Float, clarity: Float, rms: Float) -> Unit,
    ) {
        var consumed = 0
        while (consumed < count) {
            val chunk = min(count - consumed, buf.size - fill)
            System.arraycopy(input, consumed, buf, fill, chunk)
            fill += chunk
            consumed += chunk
            while (fill >= windowSize + maxTau) {
                analyze(onFrame)
                System.arraycopy(buf, hopSize, buf, 0, fill - hopSize)
                fill -= hopSize
            }
        }
    }

    private fun analyze(onFrame: (Float, Float, Float) -> Unit) {
        // 1. 差分函数 d(tau)
        for (tau in 1..maxTau) {
            var sum = 0f
            var j = 0
            while (j < windowSize) {
                val delta = buf[j] - buf[j + tau]
                sum += delta * delta
                j++
            }
            diff[tau] = sum
        }

        // 2. 累积均值归一化差分
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..maxTau) {
            running += diff[tau]
            cmnd[tau] = if (running <= 0f) 1f else diff[tau] * tau / running
        }

        // 3. 绝对阈值 + 下降沿局部极小
        var tauEstimate = -1
        var tau = minTau
        while (tau <= maxTau) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= maxTau && cmnd[tau + 1] < cmnd[tau]) tau++
                tauEstimate = tau
                break
            }
            tau++
        }

        var hz = 0f
        var clarity = 0f
        if (tauEstimate > 0) {
            val fixed = fixOctave(tauEstimate)
            val betterTau = parabolic(fixed)
            hz = (sampleRate / betterTau).toFloat()
            clarity = (1f - cmnd[fixed]).coerceIn(0f, 1f)
        } else {
            // 没过严格阈值：退而求其次，但仍然取"第一个"满足松弛阈值的 τ，
            // 而不是全局最小。
            //
            // 真机实测的原因：手机扬声器 + 房间反射会形成梳状滤波，
            // 梳状周期（≈7ms）常常比真实基频周期有更深的谷。
            // 取全局最小就会锁到梳状周期上，曲线出现整段的下探毛刺；
            // 取"第一个满足松弛阈值的 τ"则优先落在真实基频上。
            var relaxed = -1
            for (t in minTau..maxTau) {
                if (cmnd[t] < fallbackThreshold.toFloat()) {
                    relaxed = t
                    break
                }
            }
            if (relaxed > 0) {
                var t = relaxed
                while (t + 1 <= maxTau && cmnd[t + 1] < cmnd[t]) t++
                val fixed = fixOctave(t)
                val betterTau = parabolic(fixed)
                hz = (sampleRate / betterTau).toFloat()
                clarity = (1f - cmnd[fixed]).coerceIn(0f, 1f)
            }
        }
        if (hz < minFreq || hz > maxFreq) {
            hz = 0f
            clarity = 0f
        }

        // 4. 窗口 RMS
        var sq = 0f
        var j = 0
        while (j < windowSize) {
            sq += buf[j] * buf[j]
            j++
        }
        val rms = sqrt(sq / windowSize)

        onFrame(hz, clarity, rms)
    }

    /**
     * 八度修正：把"检到高八度"的候选拉回真正的基频。
     *
     * YIN 的"取第一个低于阈值的 τ"有个经典副作用：当二次谐波比基频还强时
     * （手机扬声器削顶失真就是这种情况），信号在**半个周期**处同样呈现周期性，
     * 扫描会先撞上 τ/2，于是把 f0 报成高八度——真机实测 220 Hz 被报成 433 Hz。
     *
     * 判据是比较候选 τ 与 2τ 的 cmnd：
     * · τ 确实是基频时，周期信号在 2τ 处同样重复，cmnd[2τ] ≈ cmnd[τ]（比值≈1）；
     * · τ 只是半个周期时，真正的周期 2τ 处 cmnd 会显著更低（比值远小于 1）。
     *
     * 但纯音会让这个比值失去意义：400 Hz 纯音在 16 kHz 下正好 40 采样点一个周期，
     * d[τ] 与 d[2τ] 都是浮点噪声级别的 1e-30，比值纯看运气。因此先加一道
     * **能量守恒**护栏：如果候选 τ 处的失配能量已经小到只是浮点噪声
     * （说明 τ 就是货真价实的周期），一律不动。
     */
    private fun fixOctave(tauEstimate: Int): Int {
        var t = tauEstimate

        // 窗口能量，用来判断"周期性"是真有还是浮点噪声
        var energy = 0f
        var j = 0
        while (j < windowSize) {
            energy += buf[j] * buf[j]
            j++
        }
        if (energy <= 0f) return t

        // 失配占比：候选 τ 处有多少能量没能重复。
        //   τ 真是基频  -> 只剩浮点噪声，比值 < 1e-3
        //   τ 只是半周期 -> 差的是基频那部分能量，比值可达 0.1 以上
        if (diff[t] / energy <= MISMATCH_FLOOR) return t

        // 向上修正：τ 其实是半个周期
        var hops = 0
        while (2 * t <= maxTau && hops < 2) {
            val doubled = 2 * t
            if (cmnd[doubled] < threshold && cmnd[doubled] < cmnd[t] * octaveRatio) {
                t = doubled
                hops++
            } else {
                break
            }
        }
        // 反向：基频被整个漏掉时（罕见），τ/2 本身就是可信的谷
        while (t / 2 >= minTau &&
            cmnd[t / 2] < threshold &&
            cmnd[t / 2] < cmnd[t]
        ) {
            t = t / 2
        }
        return t
    }

    /** 在 cmnd 上做抛物线插值细化 tau。 */
    private fun parabolic(tau: Int): Double {
        if (tau <= 0 || tau >= cmnd.size - 1) return tau.toDouble()
        val x0 = cmnd[tau - 1].toDouble()
        val x1 = cmnd[tau].toDouble()
        val x2 = cmnd[tau + 1].toDouble()
        val denom = 2.0 * (2.0 * x1 - x2 - x0)
        if (denom == 0.0) return tau.toDouble()
        return tau + (x2 - x0) / denom
    }
}
