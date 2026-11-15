package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * YIN 检测器的离线验证：用合成信号检查基频估计精度。
 * 这些用例不依赖 Android 运行时，普通 JVM 单元测试即可跑。
 */
class YinPitchDetectorTest {

    private fun tone(hz: Double, seconds: Double, sampleRate: Int, amp: Float = 0.5f): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * hz * i / sampleRate)).toFloat() }
    }

    private fun detect(detector: YinPitchDetector, input: FloatArray): List<Pair<Float, Float>> {
        val out = mutableListOf<Pair<Float, Float>>()
        detector.process(input, input.size) { hz, clarity, _ -> out += hz to clarity }
        return out
    }

    private fun assertDetects(input: FloatArray, expectedHz: Double, toleranceCents: Double = 30.0) {
        val detector = YinPitchDetector(sampleRate = 16000)
        val voiced = detect(detector, input).filter { it.first > 0f && it.second > 0.5f }
        assertTrue("应当检出基频", voiced.isNotEmpty())
        val avg = voiced.map { it.first.toDouble() }.average()
        val cents = 1200.0 * kotlin.math.ln(avg / expectedHz) / kotlin.math.ln(2.0)
        assertEquals("检测频率 $avg Hz 应接近 $expectedHz Hz", 0.0, cents, toleranceCents)
    }

    @Test
    fun detectsA3() = assertDetects(tone(220.0, 1.0, 16000), 220.0)

    @Test
    fun detectsLowMaleVoice() = assertDetects(tone(110.0, 1.0, 16000), 110.0)

    @Test
    fun detectsLowEdge() = assertDetects(tone(80.0, 1.5, 16000), 80.0, toleranceCents = 40.0)

    @Test
    fun detectsHighVoice() = assertDetects(tone(400.0, 1.0, 16000), 400.0)

    @Test
    fun silenceProducesNoPitch() {
        val detector = YinPitchDetector(sampleRate = 16000)
        val silence = FloatArray(16000)
        val results = detect(detector, silence)
        assertTrue("静音不应检出音高", results.all { it.first == 0f })
    }

    @Test
    fun noiseProducesNoReliablePitch() {
        val detector = YinPitchDetector(sampleRate = 16000)
        val rnd = java.util.Random(7)
        val noise = FloatArray(16000) { rnd.nextFloat() * 0.2f - 0.1f }
        val results = detect(detector, noise)
        assertTrue("白噪声不应产生高置信度音高", results.none { it.first > 0f && it.second > 0.8f })
    }

    /**
     * 真机 bug 复现：手机扬声器失真后，二次谐波（2×f0）比基频还强，
     * YIN 于是锁到半个周期上，把 220 Hz 报成 433 Hz。
     *
     * 这是"检测频率偏高一个八度"，对跨性别声乐训练是致命的：
     * 目标音域 165–255 Hz 一旦翻八度，训练方向整个反过来。
     */
    @Test
    fun strongSecondHarmonicDoesNotTriggerOctaveUpError() {
        val sr = 16000
        val f0 = 220.0
        val input = FloatArray(sr) { i ->
            val t = i.toDouble() / sr
            // 基频弱、二次谐波强（扬声器削顶失真的典型比例：谐波是基频的 5 倍）
            // 这个比例下，未做八度修正的 YIN 会报 440 Hz
            val fundamental = 0.10 * sin(2.0 * PI * f0 * t)
            val harmonic = 0.50 * sin(2.0 * PI * 2 * f0 * t)
            (fundamental + harmonic).toFloat()
        }
        assertDetects(input, f0, toleranceCents = 40.0)
    }

    /** 谐波占优时也不能反向误判成低八度。 */
    @Test
    fun dominantHarmonicStillReportsTheRealFundamental() {
        val sr = 16000
        val f0 = 180.0
        val input = FloatArray(sr) { i ->
            val t = i.toDouble() / sr
            val fundamental = 0.09 * sin(2.0 * PI * f0 * t)
            val h2 = 0.50 * sin(2.0 * PI * 2 * f0 * t)
            val h3 = 0.25 * sin(2.0 * PI * 3 * f0 * t)
            (fundamental + h2 + h3).toFloat()
        }
        assertDetects(input, f0, toleranceCents = 50.0)
    }

    /** 纯音（没有谐波结构）绝不能被八度修正改坏。 */
    @Test
    fun pureToneIsNotShiftedByOctaveCorrection() {
        assertDetects(tone(330.0, 1.0, 16000), 330.0, toleranceCents = 30.0)
        assertDetects(tone(262.0, 1.0, 16000), 262.0, toleranceCents = 30.0)
    }

    /**
     * 真实人声回归：多谐波 + 颤音 + 噪声。
     *
     * 八度修正最大的风险是把正常人声误判成低八度——一旦触发，
     * 跨性别声乐训练会得到完全相反的反馈。这条用例守住这条线。
     */
    @Test
    fun realisticVoiceIsNotShiftedDownByOctaveCorrection() {
        val sr = 16000
        val rnd = java.util.Random(11)
        val input = FloatArray(sr * 2) { i ->
            val t = i.toDouble() / sr
            // 基频带 5 Hz 颤音，在 C3 (~130.8) 附近
            val f0 = 130.8 * Math.pow(2.0, (30.0 / 1200.0) * sin(2.0 * PI * 5.0 * t) / (2.0 * PI))
            var v = 0.0
            v += 0.30 * sin(2.0 * PI * f0 * t)          // 基频
            v += 0.55 * sin(2.0 * PI * 2 * f0 * t)      // 二次谐波（人声里常很强）
            v += 0.30 * sin(2.0 * PI * 3 * f0 * t)      // 三次谐波
            v += 0.15 * sin(2.0 * PI * 4 * f0 * t)
            v += 0.02 * (rnd.nextFloat() * 2 - 1)       // 噪声
            v.toFloat()
        }
        val det = YinPitchDetector(sampleRate = sr)
        val voiced = detect(det, input).filter { it.first > 0f && it.second > 0.5f }
        assertTrue("应当检出基频", voiced.isNotEmpty())
        val avg = voiced.map { it.first.toDouble() }.average()
        // 必须落在基频附近，绝不能掉到 65 Hz（低八度）
        assertTrue("检测频率 $avg Hz 不应被误判成低八度", avg > 110.0 && avg < 150.0)
    }

    @Test
    fun vibratoIsTracked() {
        // 5.5 Hz / ±40 cent 的颤音
        val sr = 16000
        val det = YinPitchDetector(sampleRate = sr)
        val input = FloatArray(sr) { i ->
            val t = i.toDouble() / sr
            val hz = 200.0 * Math.pow(2.0, (40.0 / 1200.0) * sin(2.0 * PI * 5.5 * t) / (2.0 * PI))
            (0.5 * sin(2.0 * PI * hz * t)).toFloat()
        }
        val voiced = detect(det, input).filter { it.first > 0f && it.second > 0.6f }
        assertTrue("颤音应被持续跟踪", voiced.size > 40)
        val min = voiced.minOf { it.first }
        val max = voiced.maxOf { it.first }
        assertTrue("颤音幅度应可见（$min ~ $max）", max / min > 1.05f)
    }
}
