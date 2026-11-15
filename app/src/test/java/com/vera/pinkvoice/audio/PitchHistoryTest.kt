package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音高历史环形缓冲的离线验证。
 *
 * 重点是 [PitchHistory.copyRange]：它被改成"从最新一条反向扫描 + 提前退出"，
 * 因为正向扫全环（最多 4096 条）在 60 fps 下每帧都是白费。
 * 这里的用例把"反向扫描"的结果与"朴素正向扫描"的参考实现逐条对比，
 * 确保两者完全等价——曲线画反了是那种肉眼看几条数据也发现不了的 bug。
 */
class PitchHistoryTest {

    /** 参考实现：朴素地正向扫全环，取区间内的记录。 */
    private fun referenceRange(
        history: PitchHistory,
        tMinNs: Long,
        tMaxNs: Long,
    ): List<Pair<Long, Float>> {
        val all = history.dump()
        return all.filter { it.first in tMinNs..tMaxNs }
    }

    private fun copy(history: PitchHistory, tMinNs: Long, tMaxNs: Long): List<Pair<Long, Float>> {
        val t = LongArray(4096)
        val hz = FloatArray(4096)
        val cl = FloatArray(4096)
        val rms = FloatArray(4096)
        val n = history.copyRange(tMinNs, tMaxNs, t, hz, cl, rms)
        return (0 until n).map { t[it] to hz[it] }
    }

    private fun fill(history: PitchHistory, count: Int, startNs: Long = 0L, stepNs: Long = 16_000_000L) {
        for (i in 0 until count) {
            // 频率用 i 编码，方便校验顺序与取值
            history.add(startNs + i * stepNs, 100f + i, 0.9f, 0.01f * i)
        }
    }

    @Test
    fun emptyHistoryReturnsNothing() {
        val h = PitchHistory()
        assertEquals(0, copy(h, 0, Long.MAX_VALUE).size)
    }

    @Test
    fun returnsPointsInAscendingTimeOrder() {
        val h = PitchHistory()
        fill(h, 200)
        val got = copy(h, 0, Long.MAX_VALUE)
        assertEquals(200, got.size)
        for (i in 1 until got.size) {
            assertTrue("时间必须递增：${got[i - 1].first} -> ${got[i].first}", got[i - 1].first < got[i].first)
        }
    }

    @Test
    fun matchesNaiveForwardScan() {
        val h = PitchHistory()
        fill(h, 500)
        // 与朴素正向扫描逐条一致（含时间与频率）。
        // 频率不直接对输入值断言：跟踪器有"起音确认"，头几帧会被压住。
        assertEquals(h.dump(), copy(h, 0, Long.MAX_VALUE))
    }

    @Test
    fun windowSelectsOnlyPointsInsideRange() {
        val h = PitchHistory()
        fill(h, 300)
        // 第 100 条到第 200 条
        val tMin = 100L * 16_000_000L
        val tMax = 200L * 16_000_000L
        val got = copy(h, tMin, tMax)
        assertEquals(101, got.size)   // 闭区间，含两端
        assertEquals(100f + 100, got.first().second, 0.001f)
        assertEquals(100f + 200, got.last().second, 0.001f)
    }

    @Test
    fun windowMatchesReferenceImplementation() {
        val h = PitchHistory()
        fill(h, 400)
        val windows = listOf(
            0L to 100L * 16_000_000L,
            50L * 16_000_000L to 350L * 16_000_000L,
            399L * 16_000_000L to Long.MAX_VALUE,
            200L * 16_000_000L to 200L * 16_000_000L,
        )
        for ((lo, hi) in windows) {
            assertEquals(
                "窗口 [$lo, $hi] 的结果应与朴素实现一致",
                referenceRange(h, lo, hi),
                copy(h, lo, hi),
            )
        }
    }

    /** 环形缓冲绕回后（写满再覆盖），反向扫描不能漏点也不能重复。 */
    @Test
    fun worksAfterRingBufferWrapsAround() {
        val capacity = 64
        val h = PitchHistory(capacity = capacity)
        // 写 3 倍容量，最后一轮覆盖掉前两轮
        fill(h, capacity * 3)
        val got = copy(h, 0, Long.MAX_VALUE)
        assertEquals("应恰好保留 capacity 条", capacity, got.size)
        for (i in 1 until got.size) {
            assertTrue("绕回后时间仍须递增", got[i - 1].first < got[i].first)
        }
        // 最新一条是第 3*capacity-1 条
        assertEquals(100f + capacity * 3 - 1, got.last().second, 0.001f)
        assertEquals(100f + capacity * 2, got.first().second, 0.001f)
    }

    @Test
    fun wrappedWindowMatchesReferenceImplementation() {
        val capacity = 64
        val h = PitchHistory(capacity = capacity)
        fill(h, capacity * 3)
        val tMin = (capacity * 2 + 10L) * 16_000_000L
        val tMax = (capacity * 3 - 5L) * 16_000_000L
        assertEquals(referenceRange(h, tMin, tMax), copy(h, tMin, tMax))
    }

    @Test
    fun outputCapacityIsRespected() {
        val h = PitchHistory()
        fill(h, 100)
        val t = LongArray(10)
        val hz = FloatArray(10)
        val cl = FloatArray(10)
        val rms = FloatArray(10)
        val n = h.copyRange(0, Long.MAX_VALUE, t, hz, cl, rms)
        assertEquals("不能写出数组边界", 10, n)
    }
}
