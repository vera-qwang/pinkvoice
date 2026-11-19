package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * LPC 核心的正确性验证。
 *
 * 信号 = 白噪声 → 全极点滤波器。真值就是那个滤波器的极点。
 * 如果 LPC 能把它们找回来，说明核心没问题，问题在别处。
 */
class LpcCoreTest {
    private val sampleRate = 16000

    private fun allPole(poles: DoubleArray): FloatArray {
        val n = 1024 * 4
        // 白噪声
        var seed = 12345L
        fun rnd(): Double {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return ((seed shr 33).toDouble() / 2147483648.0)
        }
        val noise = DoubleArray(n) { rnd() }
        // 真·级联：每个谐振器的输入是**上一个的输出**。
        var y = noise
        for (k in poles.indices) {
            val f = poles[k]
            val bw = 80.0
            val r = exp(-PI * bw / sampleRate)
            val theta = 2.0 * PI * f / sampleRate
            val c = 2.0 * r * cos(theta)
            val r2 = r * r
            val out = DoubleArray(n)
            for (i in 0 until n) {
                val y1 = if (i >= 1) out[i - 1] else 0.0
                val y2 = if (i >= 2) out[i - 2] else 0.0
                out[i] = y[i] + c * y1 - r2 * y2
            }
            y = out
        }
        var peak = 1e-9
        for (v in y) peak = maxOf(peak, kotlin.math.abs(v))
        return FloatArray(n) { (y[it] / peak * 0.5).toFloat() }
    }

    @Test
    fun recoversKnownPoles() {
        val poles = doubleArrayOf(300.0, 870.0, 2240.0)
        val sig = allPole(poles)
        val lpc = LpcAnalyzer(sampleRate = sampleRate, order = 16)
        lpc.prepare(1024)
        val frame = sig.copyOfRange(1024, 2048)
        println("analyze=" + lpc.analyze(frame, 1024))
        println("--- 包络每 50 Hz ---")
        var f = 200f
        val sb = StringBuilder()
        while (f <= 2800f) { sb.append("${f.toInt()}:${"%.1f".format(lpc.envelopeDb(f))} "); f += 50f }
        println(sb)
        val peaks = lpc.findPeaks(150f, 4000f)
        println("peaks=" + peaks.joinToString { "%.0f".format(it) })
        val roots = lpc.extractFormantsByRoots()
        println("roots=" + roots.joinToString { "%.0f(%.0fHz)".format(it.hz, it.bandwidthHz) })

        // 真值：300 / 870 / 2240 Hz
        // 白噪声激励下，三个共振峰都应该在包络上明显隆起。
        assertTrue("应能找到 3 个共振峰，实际 ${roots.size}", roots.size >= 3)
        assertEquals("F1 应接近 300 Hz", 300.0, roots[0].hz.toDouble(), 60.0)
        assertEquals("F2 应接近 870 Hz", 870.0, roots[1].hz.toDouble(), 60.0)
        assertEquals("F3 应接近 2240 Hz", 2240.0, roots[2].hz.toDouble(), 80.0)
    }
}
