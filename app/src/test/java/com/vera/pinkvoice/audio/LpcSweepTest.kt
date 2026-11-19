package com.vera.pinkvoice.audio

import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/** 参数标定：两种提取方法都扫一遍。 */
class LpcSweepTest {
    private val sampleRate = 16000

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
                p < 0.4 -> { val t = p / 0.4; 3.0 * t * t - 2.0 * t * t * t }
                p < 0.56 -> { val t = (p - 0.4) / 0.16; 1.0 - (1.0 - t) * (1.0 - t) }
                else -> 0.0
            }
        }
        return out
    }

    private fun synth(f0: Double, formants: DoubleArray): FloatArray {
        val n = (sampleRate * 0.6).toInt()
        val flow = glottalFlow(f0, n)
        val src = DoubleArray(n)
        for (i in 1 until n) src[i] = flow[i] - flow[i - 1]
        var y = src
        val bws = doubleArrayOf(60.0, 90.0, 120.0)
        for (k in formants.indices) y = resonator(y, formants[k], bws[k])
        var peak = 1e-9
        for (v in y) peak = maxOf(peak, kotlin.math.abs(v))
        return FloatArray(n) { (y[it] / peak * 0.5).toFloat() }
    }

    @Test
    fun sweep() {
        val vowels = listOf(
            Triple("a", 120.0, doubleArrayOf(730.0, 1090.0, 2440.0)),
            Triple("i", 200.0, doubleArrayOf(270.0, 2290.0, 3010.0)),
            Triple("u", 120.0, doubleArrayOf(300.0, 870.0, 2240.0)),
            Triple("ae", 150.0, doubleArrayOf(660.0, 1720.0, 2410.0)),
            Triple("eh", 150.0, doubleArrayOf(530.0, 1840.0, 2480.0)),
            Triple("ao", 130.0, doubleArrayOf(570.0, 840.0, 2410.0)),
        )
        println("method pre ord | aF1/aF2  iF1/iF2  uF1/uF2 | avg err")
        for (method in listOf("env", "root")) {
            for (pe in floatArrayOf(0f, 0.3f, 0.5f, 0.7f, 0.9f, 0.97f)) {
                for (order in intArrayOf(12, 14, 16, 18, 20)) {
                    val out = StringBuilder()
                    var err = 0.0
                    var n = 0
                    for ((_, f0, expected) in vowels) {
                        val sig = synth(f0, expected)
                        val lpc = LpcAnalyzer(sampleRate, order, pe)
                        lpc.prepare(1024)
                        val start = (sig.size - 1024) / 2
                        if (!lpc.analyze(sig.copyOfRange(start, start + 1024), 1024)) {
                            out.append("   FAIL    ")
                            continue
                        }
                        val f = if (method == "env") {
                            lpc.extractFormantsByEnvelope()
                        } else {
                            lpc.extractFormants()
                        }
                        out.append(" ${f.f1.toInt()}/${f.f2.toInt()}")
                        val got = doubleArrayOf(f.f1.toDouble(), f.f2.toDouble(), f.f3.toDouble())
                        for (i in 0..2) {
                            if (got[i] > 0) { err += kotlin.math.abs(got[i] - expected[i]); n++ }
                        }
                    }
                    println("%-5s %.2f %3d |%s | %.0f".format(method, pe, order, out, err / maxOf(n, 1)))
                }
            }
        }
    }
}
