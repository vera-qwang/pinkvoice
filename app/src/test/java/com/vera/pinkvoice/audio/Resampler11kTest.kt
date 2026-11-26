package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 16 kHz → 11 kHz 重采样器。 */
class Resampler11kTest {

    private val inRate = 16000
    private val outRate = 11000

    /** 输出样本数应该是 11/16。 */
    @Test
    fun outputLengthIsElevenSixteenths() {
        val r = Resampler16kTo11k()
        val input = FloatArray(inRate) { 0.1f }
        val out = FloatArray(12000)
        val got = r.process(input, input.size, out)
        assertEquals("16000 个 16k 样本应产出 11000 个 11k 样本", 11000.0, got.toDouble(), 30.0)
    }

    /** 正弦波过重采样后频率不变、幅度不变。 */
    @Test
    fun sineKeepsFrequencyAndAmplitude() {
        for (freq in doubleArrayOf(200.0, 500.0, 1000.0, 2000.0, 3000.0, 4500.0)) {
            val input = FloatArray(inRate) {
                (0.5 * sin(2 * PI * freq * it / inRate)).toFloat()
            }
            val r = Resampler16kTo11k()
            val out = FloatArray(outRate + 64)
            val got = r.process(input, input.size, out)
            assertTrue("频率 $freq：输出太短（$got）", got > outRate / 2)

            // 丢掉开头的暂态（滤波器的群延迟）
            val skip = 300
            val usable = got - skip
            assertTrue("频率 $freq：可用样本太少（$usable）", usable > outRate / 2)
            val seg = FloatArray(usable) { out[skip + it] }

            // 用零率计数测频 —— 对非整数周期也准，且不依赖 FFT 实现
            var crossings = 0
            for (i in 1 until seg.size) {
                if (seg[i - 1] < 0f && seg[i] >= 0f) crossings++
            }
            val duration = seg.size.toDouble() / outRate
            // 每个周期有一次上穿 —— 不用再除 2
            val peak = crossings / duration
            assertEquals("频率 $freq 过重采样后应不变", freq, peak, freq * 0.02)

            // 幅度：去掉直流后算 RMS
            val mean = seg.sumOf { it.toDouble() } / seg.size
            var sum = 0.0
            for (v in seg) { val d = v - mean; sum += d * d }
            val rms = sqrt(sum / seg.size)
            // 接近截止频率（5.5 kHz）时通带会滚降，所以容差随频率放宽
            val tol = if (freq >= 4000) 0.16 else 0.06
            assertEquals(
                "频率 $freq 的幅度不该被明显改变",
                0.5 / sqrt(2.0),
                rms,
                0.5 / sqrt(2.0) * tol,
            )
        }
    }

    /** 高频不该混叠下来（11 kHz 的奈奎斯特是 5.5 kHz）。 */
    @Test
    fun doesNotAliasHighFrequencies() {
        // 7 kHz 在 16 kHz 域是合法的，但到了 11 kHz 就会混叠到 4 kHz
        val input = FloatArray(inRate) {
            (0.5 * sin(2 * PI * 7000.0 * it / inRate)).toFloat()
        }
        val r = Resampler16kTo11k()
        val out = FloatArray(12000)
        val got = r.process(input, input.size, out)
        var sum = 0.0
        var count = 0
        for (i in 300 until got - 300) { sum += out[i].toDouble() * out[i]; count++ }
        val rms = sqrt(sum / count)
        assertTrue("7 kHz 在 11 kHz 下应该被滤掉（实测 RMS $rms）", rms < 0.05)
    }

    /** 直流失真不能大（滤波器的直流增益应该是 1）。 */
    @Test
    fun dcIsPreserved() {
        val input = FloatArray(inRate) { 0.25f }
        val r = Resampler16kTo11k()
        val out = FloatArray(12000)
        val got = r.process(input, input.size, out)
        var sum = 0.0
        var count = 0
        for (i in 300 until got - 300) { sum += out[i].toDouble(); count++ }
        val mean = sum / count
        assertEquals("直流增益应该接近 1", 0.25, mean, 0.02)
    }

}
