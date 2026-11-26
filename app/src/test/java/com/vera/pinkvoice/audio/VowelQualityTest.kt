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

    // ---------- 合成工具 ----------

    /** 合成元音。用共享的 [VowelSynth] —— 频谱比例是真实的。 */
    private fun synth(f0: Double, formants: DoubleArray): FloatArray =
        VowelSynth.synth(f0, formants)

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
        for ((name, f0, fm) in cases) {
            // 每个用例用**独立的**分析器。
            //
            // 共用一个的话，分析器的滑动缓冲里会混着上一个元音的样本，
            // 而 F1/F2 的中位数平滑窗口也会跨过信号边界 ——
            // 那是在测"转音时的行为"，不是"这个元音认不认得出来"。
            val analyzer = VoiceQualityAnalyzer(sampleRate)
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


    /**
     * 信噪比对共振峰分析的影响。
     *
     * 拿干净的合成 /u/，加上「低频滚降 + 环境噪声」，
     * 看分析器还能不能找回 300 Hz 的 F1。
     *
     * **结论是：需要一定的信噪比。**
     * 信号被噪声淹没时，LPC 拟合的是噪声，
     * 读数会彻底崩坏（实测 F1 报到 759 Hz、F2 报到 2250 Hz）。
     *
     * 这条测试守着两件事：
     * 1. 干净信号下必须准（这才是算法该保证的）
     * 2. 降级后确实会崩（提醒：采集链路决定精度下限）
     */
    @Test
    fun lowFrequencyRolloffBreaksU() {
        val sr = 16000
        // /u/：F1=300, F2=870, F3=2240
        val clean = synth(120.0, doubleArrayOf(300.0, 870.0, 2240.0))
        val n = clean.size

        // 一阶高通：模拟喇叭低频滚降（-12 dB/oct 从 250 Hz 起）
        fun rollOff(x: FloatArray): FloatArray {
            val y = FloatArray(x.size)
            val rc = 1.0 / (2.0 * Math.PI * 250.0 / sr)
            val a = rc / (rc + 1.0)
            var prevX = 0.0
            var prevY = 0.0
            for (i in x.indices) {
                val v = a * (prevY + x[i] - prevX)
                y[i] = v.toFloat()
                prevX = x[i].toDouble()
                prevY = v
            }
            return y
        }

        // 环境噪声：-45 dBFS 的白噪声
        val rnd = java.util.Random(42)
        val noise = FloatArray(n) { (rnd.nextGaussian() * 0.0056).toFloat() }

        val rolled = rollOff(clean)
        val degraded = FloatArray(n) { rolled[it] + noise[it] }

        fun analyze(x: FloatArray): Pair<Float, Float> {
            val q = VoiceQualityAnalyzer(sr)
            var i = 0
            while (i + 1024 <= x.size) {
                q.process(x.copyOfRange(i, i + 1024), 1024)
                i += 256
            }
            return q.formants.f1 to q.formants.f2
        }

        val (cleanF1, cleanF2) = analyze(clean)
        val (degF1, degF2) = analyze(degraded)

        println("干净 /u/:   F1=$cleanF1 F2=$cleanF2")
        println("滚降+噪声:   F1=$degF1 F2=$degF2")

        // 干净信号必须准确 —— 这才是算法该保证的
        assertTrue("干净 /u/ 的 F1 应接近 300，实际 $cleanF1", kotlin.math.abs(cleanF1 - 300f) < 70f)
        assertTrue("干净 /u/ 的 F2 应接近 870，实际 $cleanF2", kotlin.math.abs(cleanF2 - 870f) < 120f)

        // 降级后会明显崩坏 —— 采集链路的信噪比决定精度下限。
        //
        // 真机实测（笔记本喇叭 → 房间 → 手机麦）：
        //   /u/（300/870）报成 F1=429 / F2=2256（F2 掉进噪声底了）。
        // 而同样的信号抬到 500/1150 就测得 F1=374 / F2=1092 —— 很准。
        val drift = kotlin.math.abs(degF1 - 300f)
        assertTrue("降级后读数应当明显偏离真值（实际 F1=$degF1）", drift > 100f)
    }

}
