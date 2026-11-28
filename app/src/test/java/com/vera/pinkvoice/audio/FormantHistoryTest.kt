package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 共振峰历史缓冲。 */
class FormantHistoryTest {

    @Test
    fun keepsOnlyTheRequestedTimeRange() {
        val h = FormantHistory()
        // 每秒一条，1 秒到 10 秒
        for (i in 1..10) h.add(i * 1_000_000_000L, 400f + i, 1200f + i)

        val t = LongArray(100)
        val f1 = FloatArray(100)
        val f2 = FloatArray(100)
        val n = h.copyRange(5_000_000_000L, 8_000_000_000L, t, f1, f2)

        assertEquals("应取到 5s–8s 共 4 条", 4, n)
        // 按时间升序
        assertTrue("应按时间升序", (1 until n).all { t[it] > t[it - 1] })
        assertEquals(5_000_000_000L, t[0])
        assertEquals(8_000_000_000L, t[n - 1])
    }

    @Test
    fun valuesMatchTheirTimestamps() {
        val h = FormantHistory()
        h.add(1_000_000_000L, 111f, 222f)
        h.add(2_000_000_000L, 333f, 444f)

        val t = LongArray(10)
        val f1 = FloatArray(10)
        val f2 = FloatArray(10)
        val n = h.copyRange(0L, 5_000_000_000L, t, f1, f2)

        assertEquals(2, n)
        assertEquals(111f, f1[0], 0.01f)
        assertEquals(222f, f2[0], 0.01f)
        assertEquals(333f, f1[1], 0.01f)
        assertEquals(444f, f2[1], 0.01f)
    }

    @Test
    fun newestValueIsReported() {
        val h = FormantHistory()
        assertEquals(0L, h.latestTimeNs)
        h.add(100L, 1f, 2f)
        h.add(500L, 3f, 4f)
        h.add(300L, 5f, 6f)   // 乱序写入，latest 仍应是最大的
        assertEquals(500L, h.latestTimeNs)
    }

    @Test
    fun ringWrapsAroundWithoutLosingOrder() {
        val h = FormantHistory(capacity = 8)
        // 写 20 条，早期的会被覆盖
        for (i in 1..20) h.add(i * 100_000_000L, i.toFloat(), i.toFloat())

        val t = LongArray(100)
        val f1 = FloatArray(100)
        val f2 = FloatArray(100)
        val n = h.copyRange(0L, 100_000_000_000L, t, f1, f2)

        assertEquals("环里只剩 8 条", 8, n)
        assertTrue("应按时间升序", (1 until n).all { t[it] > t[it - 1] })
        // 最早的应该是第 13 条（20 - 8 + 1）
        assertEquals(13, f1[0].toInt())
        assertEquals(20, f1[n - 1].toInt())
    }

    @Test
    fun clearEmptiesTheBuffer() {
        val h = FormantHistory()
        h.add(1L, 1f, 2f)
        h.clear()
        val t = LongArray(10)
        val f1 = FloatArray(10)
        val f2 = FloatArray(10)
        assertEquals(0, h.copyRange(0L, Long.MAX_VALUE, t, f1, f2))
        assertEquals(0L, h.latestTimeNs)
    }
}
