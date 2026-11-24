package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * LPC 分析器的正确性验证。
 *
 * **这个测试是整个「元音图 / 稳态训练」功能的地基**——
 * LPC 算错了不会崩，只会安静地给出错误的共振峰，
 * 然后元音图全是错的。所以这里用**共振峰已知的合成元音**来验。
 *
 * 合成方法：声门脉冲串（周期 f0）→ 级联三个二阶共振器（F1/F2/F3），
 * 这是语音合成里的标准「源-滤波器」模型。
 */
class LpcAnalyzerTest {

    private val sampleRate = 16000

    /**
     * 合成一个元音。
     *
     * @param f0 基频
     * @param formants 共振峰 [F1, F2, F3]
     * @param bandwidths 对应带宽
     */
    private fun synthVowel(
        f0: Double,
        formants: DoubleArray,
        bandwidths: DoubleArray = doubleArrayOf(60.0, 90.0, 120.0),
        seconds: Double = 0.6,
    ): FloatArray {
        val n = (sampleRate * seconds).toInt()
        val src = glottalFlow(f0, n)
        // 真正辐射出去的是声门流的**导数**（唇端辐射 +6 dB/oct），
        // 这一步不做的话频谱形状不对。
        val radiated = DoubleArray(n)
        for (i in 1 until n) radiated[i] = src[i] - src[i - 1]
        // 级联共振器
        //
        // 注意用的是 **radiated**（声门流的导数），不是 src。
        //
        // 差一个字后果很严重：声门流的低频能量极强（频谱 -12 dB/oct），
        // 拿它去级联的话 r[1]/r[0] 会接近 1，Levinson 的反射系数直接爆掉，
        // 递推发散、系数变成回文结构 —— 看起来像 LPC 写错了，其实是信号不对。
        var y = radiated
        for (k in formants.indices) {
            y = resonator(y, formants[k], bandwidths[k])
        }
        // 归一化
        var peak = 1e-9
        for (v in y) peak = maxOf(peak, kotlin.math.abs(v))
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = (y[i] / peak * 0.5).toFloat()
        return out
    }

    /**
     * Rosenberg 声门流脉冲。
     *
     * 为什么不用简单的脉冲串：脉冲串的频谱是**梳状**的，
     * 零点在 1/脉宽 的整数倍上（比如 5.33 ms 宽的脉冲 → 零点落在
     * 187.5 Hz 的倍数）。如果某个共振峰正好落在两个零点之间，
     * 就会被源频谱淹掉 —— 那时候测出来的不是 LPC 的错，
     * 是测试信号本身造得不对。
     *
     * Rosenberg 脉冲的频谱平滑下降（约 -12 dB/oct），
     * 零点和共振峰不打架 —— 这也是语音合成里的标准做法。
     */
    private fun glottalFlow(f0: Double, n: Int): DoubleArray {
        val out = DoubleArray(n)
        val period = sampleRate / f0
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

    /** 二阶共振器：H(z) = 1 / (1 - 2r·cosθ·z⁻¹ + r²·z⁻²) */
    private fun resonator(input: DoubleArray, freq: Double, bw: Double): DoubleArray {
        val r = exp(-PI * bw / sampleRate)
        val theta = 2.0 * PI * freq / sampleRate
        val c = 2.0 * r * kotlin.math.cos(theta)
        val r2 = r * r
        val out = DoubleArray(input.size)
        for (n in 0 until input.size) {
            val x = input[n]
            val y1 = if (n >= 1) out[n - 1] else 0.0
            val y2 = if (n >= 2) out[n - 2] else 0.0
            out[n] = x + c * y1 - r2 * y2
        }
        return out
    }

    /** 取 F1/F2。 */
    private fun formantsOf(lpc: LpcAnalyzer, sig: FloatArray): Pair<Float, Float> {
        val f = lpc.extractFormants()
        return f.f1 to f.f2
    }

    private fun analyze(sig: FloatArray): LpcAnalyzer {
        val lpc = LpcAnalyzer(sampleRate = sampleRate)
        lpc.prepare(sig.size)
        // 取中间一帧（避开起音和收音的过渡）
        val start = (sig.size - 1024) / 2
        val frame = sig.copyOfRange(start, start + 1024)
        assertTrue("分析应该成功", lpc.analyze(frame, 1024))
        return lpc
    }

    /**
     * /a/（啊）：F1 ≈ 730，F2 ≈ 1090。
     */
    @Test
    fun extractsFormantsForA() {
        val sig = synthVowel(
            f0 = 120.0,
            formants = doubleArrayOf(730.0, 1090.0, 2440.0),
        )
        val lpc = analyze(sig)
        val (f1, f2) = formantsOf(lpc, sig)
        assertEquals("F1 应接近 730 Hz", 730.0, f1.toDouble(), 60.0)
        assertEquals("F2 应接近 1090 Hz", 1090.0, f2.toDouble(), 90.0)
    }

    /**
     * /i/（衣）：F1 ≈ 270，F2 ≈ 2290。
     *
     * 这是最难的一对 —— F1 和 F2 差得很远，
     * 正好能检验 LPC 有没有把两个峰都分开。
     */
    @Test
    fun extractsFormantsForI() {
        val sig = synthVowel(
            f0 = 200.0,
            formants = doubleArrayOf(270.0, 2290.0, 3010.0),
        )
        val lpc = analyze(sig)
        val (f1, f2) = formantsOf(lpc, sig)
        assertEquals("F1 应接近 270 Hz", 270.0, f1.toDouble(), 60.0)
        assertEquals("F2 应接近 2290 Hz", 2290.0, f2.toDouble(), 120.0)
    }

    /**
     * /u/（乌）：F1 ≈ 300，F2 ≈ 870。
     */
    @Test
    fun extractsFormantsForU() {
        val sig = synthVowel(
            f0 = 120.0,
            formants = doubleArrayOf(300.0, 870.0, 2240.0),
        )
        val lpc = analyze(sig)
        val (f1, f2) = formantsOf(lpc, sig)
        assertEquals("F1 应接近 300 Hz", 300.0, f1.toDouble(), 60.0)
        assertEquals("F2 应接近 870 Hz", 870.0, f2.toDouble(), 80.0)
    }

    /** 三个元音的 F1/F2 应该彼此分得开（这是元音图能用的前提）。 */
    @Test
    fun distinguishesThreeVowels() {
        val a = formantsOf(
            analyze(synthVowel(120.0, doubleArrayOf(730.0, 1090.0, 2440.0))),
            FloatArray(0),
        )
        val i = formantsOf(
            analyze(synthVowel(200.0, doubleArrayOf(270.0, 2290.0, 3010.0))),
            FloatArray(0),
        )
        val u = formantsOf(
            analyze(synthVowel(120.0, doubleArrayOf(300.0, 870.0, 2240.0))),
            FloatArray(0),
        )
        // F1：a 明显高于 i/u
        assertTrue("a 的 F1 应高于 i", a.first > i.first + 200)
        assertTrue("a 的 F1 应高于 u", a.first > u.first + 200)
        // F2：i 明显高于 a/u
        assertTrue("i 的 F2 应高于 a", i.second > a.second + 400)
        assertTrue("i 的 F2 应高于 u", i.second > u.second + 400)
    }

    /** 全零输入不该崩，也不该给出有效结果。 */
    @Test
    fun rejectsSilence() {
        val lpc = LpcAnalyzer(sampleRate = sampleRate, order = 16)
        lpc.prepare(1024)
        assertTrue("静音应该分析失败", !lpc.analyze(FloatArray(1024), 1024))
        assertTrue("无效时包络应返回极小值", lpc.envelopeDb(1000f) <= -100f)
    }

    /** 太短的帧不该崩。 */
    @Test
    fun rejectsTooShortFrame() {
        val lpc = LpcAnalyzer(sampleRate = sampleRate, order = 16)
        lpc.prepare(1024)
        assertTrue(!lpc.analyze(FloatArray(16), 16))
    }

    /**
     * 谱倾斜：低频共振峰强的元音，倾斜应该更陡（更负）。
     */
    @Test
    fun spectralTiltDiffersBetweenVowels() {
        // /u/ 有很强的 F1（300 Hz），/i/ 的能量集中在高得多的 F2
        val u = analyze(synthVowel(120.0, doubleArrayOf(300.0, 870.0, 2240.0)))
        val i = analyze(synthVowel(200.0, doubleArrayOf(270.0, 2290.0, 3010.0)))
        val tiltU = u.spectralTilt()
        val tiltI = i.spectralTilt()
        // 倾斜是"高频相对低频"的量：/i/ 的能量更靠高频，倾斜应该更大（没那么负）
        assertTrue(
            "/i/ 的倾斜应大于 /u/（能量更靠高频）：i=$tiltI u=$tiltU",
            tiltI > tiltU,
        )
    }

    /** 同一个信号分析两次，结果应该一样（确定性）。 */
    @Test
    fun isDeterministic() {
        val sig = synthVowel(150.0, doubleArrayOf(600.0, 1200.0, 2500.0))
        val l1 = analyze(sig)
        val l2 = analyze(sig)
        assertEquals(l1.envelopeDb(700f), l2.envelopeDb(700f), 1e-6f)
    }


}
