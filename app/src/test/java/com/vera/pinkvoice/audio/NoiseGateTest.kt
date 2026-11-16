package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 返听降噪器（噪声门 + 高通）。
 *
 * 这些用例对应真机上的真实问题：麦克风把整个房间都收进来了，
 * 返听里环境底噪特别吵。
 */
class NoiseGateTest {

    private val rate = 48000

    /** 造一段正弦（带直流偏置和低频隆隆声，模拟真实环境）。 */
    private fun tone(
        freq: Float,
        seconds: Float,
        amplitude: Float = 0.1f,
        rumble: Float = 0f,
        dc: Float = 0f,
    ): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toFloat() / rate
            var v = amplitude * sin(2f * Math.PI.toFloat() * freq * t)
            if (rumble > 0f) v += rumble * sin(2f * Math.PI.toFloat() * 40f * t)
            if (dc > 0f) v += dc
            out[i] = v
        }
        return out
    }

    private fun rms(a: FloatArray, from: Int = 0, to: Int = a.size): Float {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / (to - from)).toFloat()
    }

    /** 强度 0 时必须完全透明——不能有任何处理。 */
    @Test
    fun levelZeroIsTransparent() {
        val g = NoiseGate(rate)
        val input = tone(200f, 0.1f, amplitude = 0.05f, rumble = 0.03f, dc = 0.01f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        for (i in input.indices) {
            assertEquals("第 $i 个样本应该原样通过", input[i], out[i], 1e-6f)
        }
    }

    /** 强噪声（远超门限）应该原样通过。 */
    @Test
    fun loudSignalPassesUnchanged() {
        val g = NoiseGate(rate)
        g.level = 1f
        val input = tone(200f, 0.2f, amplitude = 0.5f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        // 忽略开头开门斜坡的那几毫秒
        val skip = rate / 100
        val expected = rms(input, skip)
        val actual = rms(out, skip)
        assertEquals("大信号应该基本原样通过", expected, actual, expected * 0.15f)
    }

    /** 安静信号（低于门限）应该被压掉。 */
    @Test
    fun quietSignalIsSuppressed() {
        val g = NoiseGate(rate)
        g.level = 1f
        // -50 dBFS 的底噪，门限在 -25 dB → 应该被压掉
        val input = tone(200f, 0.3f, amplitude = 0.003f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        val skip = rate / 20
        assertTrue(
            "安静的底噪应该被压掉：rms ${rms(out, skip)} vs 输入 ${rms(input, skip)}",
            rms(out, skip) < rms(input, skip) * 0.2f,
        )
    }

    /** 门开之后，人声要能出来——不能把真声音也切掉。 */
    @Test
    fun loudVoiceOpensTheGate() {
        val g = NoiseGate(rate)
        g.level = 0.5f
        // -20 dBFS 的声音，门限在 -47.5 dB → 应该放行
        val input = tone(200f, 0.3f, amplitude = 0.1f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        val skip = rate / 20
        assertTrue(
            "人声应该放行：rms ${rms(out, skip)} vs 输入 ${rms(input, skip)}",
            rms(out, skip) > rms(input, skip) * 0.5f,
        )
    }

    /** 高通要砍掉低频隆隆声。 */
    @Test
    fun highPassRemovesRumble() {
        val g = NoiseGate(rate)
        g.level = 1f
        // 只有 40 Hz 的隆隆声
        val input = tone(40f, 0.3f, amplitude = 0.2f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        val skip = rate / 20
        assertTrue(
            "40 Hz 隆隆声应该被高通砍掉：${rms(out, skip)} vs ${rms(input, skip)}",
            rms(out, skip) < rms(input, skip) * 0.5f,
        )
    }

    /** 高通不能误伤人声频段。 */
    @Test
    fun highPassKeepsVoiceRange() {
        val g = NoiseGate(rate)
        g.level = 1f
        // 200 Hz 的人声，最高档的高通（80 Hz）应该几乎不动
        val input = tone(200f, 0.3f, amplitude = 0.3f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        val skip = rate / 20
        assertTrue(
            "200 Hz 应该基本保留：${rms(out, skip)} vs ${rms(input, skip)}",
            rms(out, skip) > rms(input, skip) * 0.8f,
        )
    }

    /** 直流偏置必须被去掉——否则耳机里会有"咔"的一声。 */
    @Test
    fun removesDcOffset() {
        val g = NoiseGate(rate)
        g.level = 1f
        val input = tone(200f, 0.3f, amplitude = 0.05f, dc = 0.1f)
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        val skip = rate / 20
        var mean = 0f
        for (i in skip until out.size) mean += out[i]
        mean /= (out.size - skip)
        assertTrue("直流分量应该被去掉，实际均值 $mean", abs(mean) < 0.01f)
    }

    /** 输出不能超出 -1..1。 */
    @Test
    fun outputStaysInRange() {
        val g = NoiseGate(rate)
        g.level = 1f
        val input = FloatArray(rate) { if (it % 2 == 0) 1f else -1f }
        val out = FloatArray(input.size)
        g.process(input, input.size, out)
        for (v in out) {
            assertTrue("输出越界：$v", v >= -1.0001f && v <= 1.0001f)
        }
    }

    /** 门限换算要和 UI 显示的一致。 */
    @Test
    fun thresholdMapping() {
        assertEquals(-70f, NoiseGate.MIN_THRESHOLD_DB, 0.01f)
        assertEquals(-25f, NoiseGate.MAX_THRESHOLD_DB, 0.01f)
        // 强度 0.5 → -47.5 dB
        val mid = NoiseGate.MIN_THRESHOLD_DB +
            (NoiseGate.MAX_THRESHOLD_DB - NoiseGate.MIN_THRESHOLD_DB) * 0.5f
        assertEquals(-47.5f, mid, 0.01f)
    }
}
