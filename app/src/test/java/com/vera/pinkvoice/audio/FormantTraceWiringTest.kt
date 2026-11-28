package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分析器 → 历史缓冲的接线。
 *
 * 界面上的轨迹图靠这个历史画线。接线断了的话界面就是一条空线，
 * 而分析本身还是好的 —— 单元测试覆盖不到，得专门测。
 */
class FormantTraceWiringTest {

    @Test
    fun analyzerFillsTheHistoryWithTimestamps() {
        val analyzer = VoiceQualityAnalyzer(sampleRate = 16000)
        val sig = FloatArray(16000) { i ->
            (0.4 * kotlin.math.sin(2 * Math.PI * 200.0 * i / 16000)).toFloat()
        }
        analyzer.voiced = true

        // 分块喂，模拟真实的数据流；每块给一个递增的时间戳
        var t = 1_000_000_000L
        val block = 1024
        var i = 0
        while (i < sig.size) {
            val n = minOf(block, sig.size - i)
            t += n * 1_000_000_000L / 16000
            analyzer.process(sig.copyOfRange(i, i + n), n, t)
            i += n
        }

        val hist = analyzer.formantHistory
        assertTrue("历史里应该有帧", hist.latestTimeNs > 0L)

        val times = LongArray(1024)
        val f1 = FloatArray(1024)
        val f2 = FloatArray(1024)
        val count = hist.copyRange(0L, Long.MAX_VALUE, times, f1, f2)
        assertTrue("应取到帧（实际 $count）", count > 5)

        // 时间戳升序
        assertTrue("时间戳应升序", (1 until count).all { times[it] > times[it - 1] })
        // 时间戳在给定块结束时刻附近（允许几帧的偏差）
        assertTrue(
            "时间戳应接近块结束时刻（最后 ${times[count - 1]}，块末 $t）",
            times[count - 1] <= t && t - times[count - 1] < 500_000_000L,
        )
        // 值是有效的共振峰
        assertTrue("F1 应该是合理值", f1[count - 1] > 100f && f1[count - 1] < 1200f)
        assertTrue("F2 应该是合理值", f2[count - 1] > 400f && f2[count - 1] < 3000f)
    }

    @Test
    fun framesAreRoughlySixteenMillisecondsApart() {
        val analyzer = VoiceQualityAnalyzer(sampleRate = 16000)
        val sig = FloatArray(16000) {
            (0.4 * kotlin.math.sin(2 * Math.PI * 200.0 * it / 16000)).toFloat()
        }
        analyzer.voiced = true
        var t = 0L
        val block = 1024
        var i = 0
        while (i < sig.size) {
            val n = minOf(block, sig.size - i)
            t += n * 1_000_000_000L / 16000
            analyzer.process(sig.copyOfRange(i, i + n), n, t)
            i += n
        }
        val hist = analyzer.formantHistory
        val times = LongArray(1024)
        val f1 = FloatArray(1024)
        val f2 = FloatArray(1024)
        val count = hist.copyRange(0L, Long.MAX_VALUE, times, f1, f2)
        assertTrue(count > 5)
        // 跳帧 = 11 kHz 域的 177 点 ≈ 16 ms
        val gap = (times[count - 1] - times[0]) / (count - 1)
        assertEquals("帧间隔应约 16 ms", 16_000_000.0, gap.toDouble(), 2_000_000.0)
    }
}
