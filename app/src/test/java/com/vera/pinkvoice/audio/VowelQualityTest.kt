package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * 元音识别、稳态度、音色轴。
 *
 * 这几项**都有客观 ground truth**（念什么元音自己知道），
 * 所以能直接验证 —— 这是它们比「虚实检测」好的地方。
 */
class VowelQualityTest {

    private val sampleRate = 16000

    // ---------- 合成工具（和 LpcAnalyzerTest 同款） ----------

    private fun resonator(input: DoubleArray, freq: Double, bw: Double): DoubleArray {
        val r = exp(-PI * bw / sampleRate)
        val theta = 2.0 * PI * freq / sampleRate
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

    private fun synth(f0: Double, formants: DoubleArray): FloatArray {
        val n = (sampleRate * 0.6).toInt()
        val flow = glottalFlow(f0, n)
        val radiated = DoubleArray(n)
        for (i in 1 until n) radiated[i] = flow[i] - flow[i - 1]
        var y = radiated
        val bws = doubleArrayOf(60.0, 90.0, 120.0)
        for (k in formants.indices) y = resonator(y, formants[k], bws[k])
        var peak = 1e-9
        for (v in y) peak = maxOf(peak, kotlin.math.abs(v))
        return FloatArray(n) { (y[it] / peak * 0.5).toFloat() }
    }

    // ---------- 元音识别 ----------

    /**
     * 合成元音能被认出来。
     *
     * 归一化后（用户声道长度 = 标准值），测到的 F1/F2 应该落回
     * 元音图的目标位置，识别自然就对了。
     */
    @Test
    fun recognizesSyntheticVowels() {
        FormantNormalizer.reset()
        val cases = listOf(
            Triple("a", 120.0, doubleArrayOf(730.0, 1090.0, 2440.0)),
            Triple("i", 200.0, doubleArrayOf(270.0, 2290.0, 3010.0)),
            Triple("u", 120.0, doubleArrayOf(300.0, 870.0, 2240.0)),
            Triple("ae", 150.0, doubleArrayOf(660.0, 1720.0, 2410.0)),
        )
        val analyzer = VoiceQualityAnalyzer(sampleRate)
        for ((name, f0, fm) in cases) {
            val sig = synth(f0, fm)
            val frame = sig.copyOfRange((sig.size - 1024) / 2, (sig.size - 1024) / 2 + 1024)
            analyzer.process(frame, 1024)
            val got = analyzer.recognized
            assertNotNull("$name 应该被识别出来", got)
            assertTrue(
                "$name 被认成了 $got",
                got!!.name.lowercase().startsWith(name),
            )
        }
    }

    /** 不同的元音要能区分开。 */
    @Test
    fun distinguishesVowels() {
        FormantNormalizer.reset()
        val a = VowelRecognizer.recognize(730f, 1090f)
        val i = VowelRecognizer.recognize(270f, 2290f)
        val u = VowelRecognizer.recognize(300f, 870f)
        assertEquals(VowelTarget.A, a.vowel)
        assertEquals(VowelTarget.I, i.vowel)
        assertEquals(VowelTarget.U, u.vowel)
    }

    /** 无效输入不该给出结果。 */
    @Test
    fun rejectsInvalidInput() {
        val r = VowelRecognizer.recognize(0f, 0f)
        assertNull(r.vowel)
        assertTrue(!r.confident)
    }

    // ---------- 声道长度归一化 ----------

    /**
     * 声道长的人共振峰低，归一化后应该和别人一样。
     *
     * 模拟：声道长 1.3 倍 → 所有共振峰 × 1/1.3 → 归一化后还原。
     */
    @Test
    fun normalizationAlignsDifferentVocalTracts() {
        FormantNormalizer.reset()
        // 长声道：/a/ 的 F1 只有 730/1.3 = 562 Hz
        val longTractF1 = 730f / 1.3f
        val length = FormantNormalizer.calibrate(longTractF1)
        assertEquals("声道长度应约为 22.75 cm", 22.75f, length, 0.2f)
        // 归一化后应该回到 730
        val normalized = FormantNormalizer.normalize(longTractF1)
        assertEquals("归一化后应回到 730 Hz", 730f, normalized, 5f)
        // 识别也应该正确
        val r = VowelRecognizer.recognize(normalized, 1090f)
        assertEquals(VowelTarget.A, r.vowel)
        FormantNormalizer.reset()
    }

    /** 校准值不合理时不该乱改。 */
    @Test
    fun calibrationRejectsAbsurdValues() {
        FormantNormalizer.reset()
        val before = FormantNormalizer.userLengthCm
        FormantNormalizer.calibrate(10f)
        assertEquals("离谱的值不该改变声道长度", before, FormantNormalizer.userLengthCm, 0.01f)
        FormantNormalizer.calibrate(5000f)
        assertEquals("离谱的值不该改变声道长度", before, FormantNormalizer.userLengthCm, 0.01f)
    }

    // ---------- 稳态度 ----------

    /**
     * 稳的信号 → 稳定度高；抖的信号 → 稳定度低。
     */
    @Test
    fun stabilityDistinguishesSteadyFromJittery() {
        // 稳：F1 恒定
        val steady = StabilityTracker()
        var s = 0f
        repeat(64) { s = steady.add(500f, 1500f) }
        assertTrue("恒定信号应该完全稳定，实际 $s", s > 0.95f)

        // 抖：F1 随机跳 ±60 Hz（约 12%，对人声来说很不稳了）
        val jittery = StabilityTracker()
        var s2 = 0f
        var seed = 1
        repeat(64) {
            seed = seed * 1103515245 + 12345
            val jitter = ((seed shr 16) and 0xFF) / 255f * 120f - 60f
            s2 = jittery.add(500f + jitter, 1500f)
        }
        // 判据是「能区分」，不是某个具体数值：
        // 抖动信号必须明显比稳的差。
        assertTrue("抖动的信号应该明显更不稳，实际 $s2", s2 < s - 0.2f)
        assertTrue("±60 Hz 的抖动不该算稳，实际 $s2", s2 < 0.75f)

        // 轻微抖动（±10 Hz）应该还算稳
        val slight = StabilityTracker()
        var s3 = 0f
        seed = 7
        repeat(64) {
            seed = seed * 1103515245 + 12345
            val jitter = ((seed shr 16) and 0xFF) / 255f * 20f - 10f
            s3 = slight.add(500f + jitter, 1500f)
        }
        assertTrue("±10 Hz 的轻微抖动应该还算稳，实际 $s3", s3 > 0.8f)
    }

    /** 数据不够时不该给出高分。 */
    @Test
    fun stabilityNeedsWarmup() {
        val t = StabilityTracker()
        assertEquals("数据不够时应该是 0", 0f, t.stability(), 0.001f)
        t.add(500f, 1500f)
        assertEquals("只有一帧时应该是 0", 0f, t.stability(), 0.001f)
    }

    /** 无效帧不该污染统计。 */
    @Test
    fun stabilityIgnoresInvalidFrames() {
        val t = StabilityTracker()
        repeat(64) { t.add(500f, 1500f) }
        val before = t.stability()
        repeat(10) { t.add(0f, 0f) }
        assertEquals("无效帧不该影响结果", before, t.stability(), 0.001f)
    }

    // ---------- 音色轴 ----------

    /**
     * 没有基准时不该给结论。
     */
    @Test
    fun timbreAxisNeedsBaseline() {
        val axis = TimbreAxis()
        val r = axis.read(-3f, 700f)
        assertTrue("没有基准时不该有结论", !r.hasBaseline)
        assertEquals("没有基准时应该在中间", 0f, r.position, 0.001f)
    }

    /**
     * 有基准后：比基准更"厚"（倾斜更负）应该偏正方向。
     */
    @Test
    fun timbreAxisRespondsToTilt() {
        val axis = TimbreAxis()
        // 造一段基准：倾斜 -4 dB/oct，F1 700
        repeat(40) { axis.baseline.add(-4f, 700f) }
        assertTrue(axis.baseline.valid)

        // 和基准一样 → 居中
        val same = axis.read(-4f, 700f)
        assertTrue("与基准相同应接近 0，实际 ${same.position}", kotlin.math.abs(same.position) < 0.15f)

        // 倾斜更平（-1）= 高频多 = 薄 → 负方向
        val thinner = axis.read(-1f, 700f)
        assertTrue("更薄应该在负方向，实际 ${thinner.position}", thinner.position < -0.3f)

        // 倾斜更陡（-8）= 低频多 = 厚 → 正方向
        val thicker = axis.read(-8f, 700f)
        assertTrue("更厚应该在正方向，实际 ${thicker.position}", thicker.position > 0.3f)
    }

    /** F1 越大（嘴越开）应该越偏"前"（负方向）。 */
    @Test
    fun timbreAxisRespondsToF1() {
        val axis = TimbreAxis()
        repeat(40) { axis.baseline.add(-4f, 700f) }
        val biggerF1 = axis.read(-4f, 900f)
        val smallerF1 = axis.read(-4f, 500f)
        assertTrue("F1 大应该偏负", biggerF1.position < smallerF1.position)
    }

    /** 基准样本不够时不该算有效。 */
    @Test
    fun timbreBaselineNeedsEnoughSamples() {
        val b = TimbreAxis.Baseline()
        assertTrue(!b.valid)
        repeat(TimbreAxis.MIN_SAMPLES) { b.add(-4f, 700f) }
        assertTrue(b.valid)
    }
}
