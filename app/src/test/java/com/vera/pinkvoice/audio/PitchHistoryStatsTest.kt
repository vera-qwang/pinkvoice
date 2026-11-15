package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 窗口统计（平均音高 / 响度）的验证。
 *
 * [PitchHistory.statsIn] 与 `copyRange` 一样走反向扫描 + 提前退出，
 * 环形缓冲绕回时的重复取点问题在这里同样可能出现，所以要专门测。
 */
class PitchHistoryStatsTest {

    /** 写入一串记录，音高按 100 + i 编码，响度按 0.01 * i 编码。 */
    private fun fill(
        history: PitchHistory,
        count: Int,
        startNs: Long = 0L,
        stepNs: Long = 16_000_000L,
        voiced: Boolean = true,
    ) {
        for (i in 0 until count) {
            val hz = if (voiced) 100f + i else 0f
            history.add(startNs + i * stepNs, hz, if (voiced) 0.9f else 0.1f, 0.01f * i)
        }
    }

    @Test
    fun emptyHistoryYieldsEmptyStats() {
        val s = PitchHistory().statsIn(0, Long.MAX_VALUE)
        assertNull(s.avgHz)
        assertEquals(0f, s.voicedRatio, 0f)
        assertEquals(0f, s.avgRms, 0f)
    }

    /**
     * 参考实现：直接读环形缓冲里的记录来算，验证 [PitchHistory.statsIn]。
     *
     * 注意这里比的是**跟踪后**的值（`hz[idx]` 就是曲线上画的那个），
     * 而不是喂进去的原始值——跟踪器有起音确认，首帧会输出上一候选值，
     * 所以两者不完全相等是正常的。
     */
    private fun referenceStats(
        history: PitchHistory,
        tMinNs: Long,
        tMaxNs: Long,
    ): Triple<Float?, Float, Float> {
        val all = history.dump()
        val inWindow = all.filter { it.first in tMinNs..tMaxNs }
        val voiced = inWindow.filter { it.second > 0f }
        return Triple(
            if (voiced.isEmpty()) null else voiced.map { it.second }.average().toFloat(),
            if (inWindow.isEmpty()) 0f else voiced.size.toFloat() / inWindow.size,
            if (inWindow.isEmpty()) 0f else inWindow.size.toFloat() / inWindow.size,
        )
    }

    @Test
    fun averageHzCoversOnlyVoicedFrames() {
        val h = PitchHistory()
        // 前 50 帧无声，后 50 帧有声
        for (i in 0 until 50) h.add(i * 16_000_000L, 0f, 0.1f, 0.01f * i)
        for (i in 50 until 100) h.add(i * 16_000_000L, 100f + i, 0.9f, 0.01f * i)

        val s = h.statsIn(0, Long.MAX_VALUE)
        val (refHz, refRatio, _) = referenceStats(h, 0, Long.MAX_VALUE)
        assertEquals("平均音高应等于有声帧的平均", refHz!!, s.avgHz!!, 0.001f)
        assertEquals("有声比例应等于有声帧占比", refRatio, s.voicedRatio, 0.001f)
        // 静音帧不能被算进音高：原始有声值 150..199 的平均是 174.5，
        // 若把静音帧的 0 也算进去会明显偏低
        assertTrue("平均音高应接近有声帧的平均", s.avgHz!! > 170f)
    }

    @Test
    fun averageHzIsNullWhenNothingIsVoiced() {
        val h = PitchHistory()
        fill(h, 30, voiced = false)
        val s = h.statsIn(0, Long.MAX_VALUE)
        assertNull("没有有声帧时平均音高应为 null", s.avgHz)
        assertEquals(0f, s.voicedRatio, 0.001f)
        // 响度仍然要有值——静音也是响度信息的一部分
        assertTrue("响度应统计全部帧", s.avgRms > 0f)
    }

    @Test
    fun windowRestrictsTheAverage() {
        val h = PitchHistory()
        fill(h, 100)
        // 只取第 20–29 条
        val lo = 20L * 16_000_000L
        val hi = 29L * 16_000_000L
        val s = h.statsIn(lo, hi)
        val (refHz, refRatio, _) = referenceStats(h, lo, hi)
        assertEquals(refHz!!, s.avgHz!!, 0.001f)
        assertEquals(refRatio, s.voicedRatio, 0.001f)
        // 窗口外的帧不能混进来：全量平均约 149.5（音高 100..199），
        // 窗口内只有 120..129，平均约 124.5，必须明显更低
        val full = h.statsIn(0, Long.MAX_VALUE)
        assertTrue("只统计窗口内的帧", s.avgHz!! < full.avgHz!! - 10f)
    }

    @Test
    fun loudnessAveragesAllFramesAndTracksPeak() {
        val h = PitchHistory()
        fill(h, 10)
        val s = h.statsIn(0, Long.MAX_VALUE)
        // rms 依次为 0.00, 0.01, ... 0.09，平均 0.045
        assertEquals(0.045f, s.avgRms, 0.0001f)
        assertEquals(0.09f, s.peakRms, 0.0001f)
    }

    @Test
    fun statsAfterRingBufferWrapAround() {
        val capacity = 64
        val h = PitchHistory(capacity = capacity)
        fill(h, capacity * 3)
        val s = h.statsIn(0, Long.MAX_VALUE)
        val (refHz, refRatio, _) = referenceStats(h, 0, Long.MAX_VALUE)
        assertEquals("绕回后平均音高应等于保留段的平均", refHz!!, s.avgHz!!, 0.001f)
        assertEquals(refRatio, s.voicedRatio, 0.001f)
    }

    @Test
    fun wrappedWindowMatchesUnwrappedWindow() {
        val capacity = 64
        val h = PitchHistory(capacity = capacity)
        fill(h, capacity * 2)
        val lo = (capacity + 10L) * 16_000_000L
        val hi = (capacity + 30L) * 16_000_000L
        val s = h.statsIn(lo, hi)
        val (refHz, refRatio, _) = referenceStats(h, lo, hi)
        assertEquals(refHz!!, s.avgHz!!, 0.001f)
        assertEquals(refRatio, s.voicedRatio, 0.001f)
    }

    @Test
    fun emptyWindowYieldsEmptyStats() {
        val h = PitchHistory()
        fill(h, 50)
        // 窗口在所有数据之前
        val s = h.statsIn(-1_000_000_000L, -1L)
        assertNull(s.avgHz)
        assertEquals(0f, s.voicedRatio, 0f)
    }
}
