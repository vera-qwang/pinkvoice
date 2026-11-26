package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 16 kHz → 12 kHz 重采样器。
 *
 * 这个重采样是「LPC 在 12 kHz 上分析」的前提，
 * 错了后面全错，所以单独验。
 */
class ResamplerTest {

    /**
     * 正弦波过重采样后，频率不变、幅度基本不变。
     *
     * 这是最基本也最要紧的性质：如果通带被压了或者频率走了，
     * 共振峰位置就会整体偏。
     */
    @Test
    fun sineKeepsFrequencyAndAmplitude() {
        val inRate = 16000
        val outRate = 12000
        for (freq in doubleArrayOf(200.0, 500.0, 1000.0, 2000.0, 3000.0)) {
            val n = inRate  // 1 秒
            val input = FloatArray(n) {
                (0.5 * sin(2 * PI * freq * it / inRate)).toFloat()
            }
            val r = Resampler16kTo12k()
            val out = FloatArray(outRate + 64)
            val got = r.process(input, n, out)

            // 跳过开头（滤波器建立）和结尾
            val skip = 600
            val usable = got - skip * 2
            assertTrue("频率 $freq：输出太短（$got）", usable > outRate / 2)

            // 用自相关测周期
            val seg = FloatArray(usable) { out[skip + it] }
            var bestLag = 0
            var best = -1e9f
            // 搜索范围要覆盖测的频率：周期 = outRate / freq
            // 上限 4000 Hz → 最短 lag 3；下限 100 Hz → 最长 lag 120
            val minLag = (outRate / 4000).toInt().coerceAtLeast(2)
            val maxLag = (outRate / 100).toInt()
            for (lag in minLag..maxLag) {
                var s = 0f
                for (i in 0 until seg.size - lag) s += seg[i] * seg[i + lag]
                if (s > best) {
                    best = s
                    bestLag = lag
                }
            }
            val measured = outRate.toDouble() / bestLag
            assertEquals("频率 $freq 过重采样后应不变", freq, measured, freq * 0.03)

            // 幅度（RMS）：输入 0.5 幅度 → RMS ≈ 0.354
            var sum = 0.0
            for (v in seg) sum += v.toDouble() * v
            val rms = kotlin.math.sqrt(sum / seg.size)
            assertEquals(
                "频率 $freq 的幅度不该被明显改变",
                0.5 / kotlin.math.sqrt(2.0),
                rms,
                0.5 / kotlin.math.sqrt(2.0) * 0.08,
            )
        }
    }

    /** 输出样本数应该约等于输入的 3/4。 */
    @Test
    fun outputLengthIsThreeQuarters() {
        val r = Resampler16kTo12k()
        val input = FloatArray(16000) { 0.1f }
        val out = FloatArray(13000)
        val got = r.process(input, input.size, out)
        assertEquals("16000 个 16k 样本应产出约 12000 个 12k 样本", 12000.0, got.toDouble(), 40.0)
    }

    /** 直流不该被放大（滤波器的系数和应为 1）。 */
    @Test
    fun dcGainIsUnity() {
        val r = Resampler16kTo12k()
        val input = FloatArray(4000) { 0.25f }
        val out = FloatArray(3200)
        val got = r.process(input, input.size, out)
        // 取后段（滤波器已稳定）
        var sum = 0.0
        for (i in got - 500 until got) sum += out[i]
        assertEquals("直流增益应为 1", 0.25, sum / 500, 0.01)
    }

    /** 分块喂和一次喂，结果应该一致。 */
    @Test
    fun chunkedEqualsBulk() {
        val n = 8000
        val input = FloatArray(n) {
            (0.4 * sin(2 * PI * 440.0 * it / 16000) + 0.2 * sin(2 * PI * 1700.0 * it / 16000)).toFloat()
        }
        val bulk = FloatArray(7000)
        val bulkN = Resampler16kTo12k().process(input, n, bulk)

        val chunked = FloatArray(7000)
        val r = Resampler16kTo12k()
        var written = 0
        val chunk = 300
        var off = 0
        while (off < n) {
            val c = minOf(chunk, n - off)
            val part = FloatArray(c)
            System.arraycopy(input, off, part, 0, c)
            val tmp = FloatArray(c + 8)
            val k = r.process(part, c, tmp)
            System.arraycopy(tmp, 0, chunked, written, k)
            written += k
            off += c
        }
        assertTrue("分块输出不该比整块少太多", abs(written - bulkN) <= 8)
        var maxDiff = 0f
        for (i in 0 until minOf(written, bulkN)) {
            maxDiff = maxOf(maxDiff, abs(chunked[i] - bulk[i]))
        }
        assertTrue("分块和整块的结果应该一致（最大差 $maxDiff）", maxDiff < 1e-4f)
    }
}
