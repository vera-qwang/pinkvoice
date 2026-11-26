package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示限速器。
 *
 * 用户反复反馈「F1/F2 乱飘」。上游（跟踪器 + 中位数平滑）已经压住了
 * 大部分抖动，但压不住**极点塌陷** —— 现场实测 F1 会在 250 ms 内
 * 从 571 掉到 239（−58%），界面上就是一个大跳。
 *
 * 限速从数学上保证：相邻两次显示之间数值最多移动 3%。
 */
class SlewLimiterTest {

    @Test
    fun firstValueIsAcceptedImmediately() {
        val s = SlewLimiter()
        val out = s.apply(500f, 1500f)
        assertEquals("第一帧应该直接接受，不该从 0 爬", 500f, out.f1, 0.01f)
        assertEquals(1500f, out.f2, 0.01f)
    }

    /** 单帧大跳变会被限速切开。 */
    @Test
    fun suddenJumpIsRamped() {
        val s = SlewLimiter()
        s.apply(571f, 1500f)
        val out = s.apply(239f, 1500f)
        val change = kotlin.math.abs(out.f1 - 571f) / 571f
        assertTrue("一帧内不该跳 58%，实际变了 ${"%.1f".format(change * 100)}%", change <= 0.031f)
    }

    /** 连续给坏值，也只会缓慢下移（不会瞬间塌）。 */
    @Test
    fun sustainedBadValuesAreSmoothedIn() {
        val s = SlewLimiter()
        s.apply(571f, 1500f)
        var out = s.apply(571f, 1500f)
        repeat(3) { out = s.apply(239f, 1500f) }
        assertTrue("3 帧后不该已经到位（实际 ${out.f1}）", out.f1 > 500f)
    }

    /** 但最终一定能跟到目标 —— 不能永远追不上。 */
    @Test
    fun eventuallyReachesTarget() {
        val s = SlewLimiter()
        s.apply(500f, 1500f)
        var out = s.apply(500f, 1500f)
        repeat(200) { out = s.apply(700f, 2000f) }
        assertEquals("最终应该收敛到目标 F1", 700f, out.f1, 2f)
        assertEquals("最终应该收敛到目标 F2", 2000f, out.f2, 5f)
    }

    /** 无效输入清零。 */
    @Test
    fun invalidClears() {
        val s = SlewLimiter()
        s.apply(500f, 1500f)
        val out = s.apply(0f, 0f)
        assertEquals(0f, out.f1, 0.01f)
    }
}
