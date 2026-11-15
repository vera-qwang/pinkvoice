package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** 48k -> 16k 抽取器：验证幅度保持与高频抑制（防混叠导致倍频误判）。 */
class FirDecimatorTest {

    private fun tone(hz: Double, seconds: Double, sampleRate: Int, amp: Float = 0.5f): FloatArray {
        val n = (seconds * sampleRate).toInt()
        return FloatArray(n) { i -> (amp * sin(2.0 * PI * hz * i / sampleRate)).toFloat() }
    }

    @Test
    fun preservesLowFrequencyAmplitude() {
        val decimator = FirDecimator()
        val input = tone(200.0, 0.5, 48000)
        val out = FloatArray(16000)
        val n = decimator.process(input, input.size, out)
        assertTrue(n > 1000)
        val tail = out.copyOfRange(n - 2000, n)
        val peak = tail.maxOf { kotlin.math.abs(it) }
        assertEquals("300Hz 附近幅度应基本保持", 0.5f, peak, 0.05f)
    }

    @Test
    fun attenuatesAboveNyquist() {
        val decimator = FirDecimator()
        // 11 kHz：超过 8 kHz 奈奎斯特，必须被滤掉，否则会混叠折回基带
        val input = tone(11000.0, 0.5, 48000)
        val out = FloatArray(16000)
        val n = decimator.process(input, input.size, out)
        val tail = out.copyOfRange(n - 2000, n)
        val peak = tail.maxOf { kotlin.math.abs(it) }
        assertTrue("11kHz 应被明显抑制（实测峰值 $peak）", peak < 0.05f)
    }

    @Test
    fun keepsFundamentalOfVoiceLikeSignal() {
        // 基频 120Hz + 若干谐波（模拟真实人声）
        val sr = 48000
        val input = FloatArray(sr) { i ->
            val t = i.toDouble() / sr
            var v = 0.5 * sin(2.0 * PI * 120.0 * t)
            v += 0.25 * sin(2.0 * PI * 360.0 * t)
            v += 0.15 * sin(2.0 * PI * 600.0 * t)
            v.toFloat()
        }
        val decimator = FirDecimator()
        val out = FloatArray(16000)
        val n = decimator.process(input, input.size, out)
        val det = YinPitchDetector(sampleRate = 16000)
        val results = mutableListOf<Float>()
        det.process(out, n) { hz, _, _ -> results += hz }
        val voiced = results.filter { it > 0f }
        assertTrue("应检出基频", voiced.isNotEmpty())
        val avg = voiced.average().toDouble()
        assertEquals("应检出 120Hz 基频而不是谐波", 120.0, avg, 1.5)
    }
}
