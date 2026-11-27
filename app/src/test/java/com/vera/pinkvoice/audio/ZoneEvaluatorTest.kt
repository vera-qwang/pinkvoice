package com.vera.pinkvoice.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标区判定（分布式）。
 *
 * ## 要守住的两条
 *
 * 1. **偶发坏读数不能断保持。** 共振峰偶尔会整个消失，
 *    这时取到的是 F3（看起来是个合理的数字，其实完全不对）。
 *    单帧点比较会立刻判「不在区」，保持计时就断了。
 *
 * 2. **起音过渡不能算「已在区内」。** 从静音滑进元音时，
 *    F1/F2 连续变化，中间有若干帧恰好落在目标区里。
 *    单帧点比较会说「已经到位」，但用户其实还没稳定下来。
 */
class ZoneEvaluatorTest {

    private val target = VowelTarget.A
    private val centerF1 = FeminineTarget.centerF1(target)
    private val centerF2 = FeminineTarget.centerF2(target)

    /** 在目标区中心的读数。 */
    private fun centered() = centerF1 to centerF2

    /** 明显在区外的读数（比如还是男性参考位置）。 */
    private fun outside() = target.f1 to target.f2

    @Test
    fun steadyVowelCountsAsInZone() {
        val e = ZoneEvaluator(windowFrames = 12)
        // 窗口没满的几帧按设计返回 false（样本太少，说明不了问题）
        for (i in 0 until 11) {
            val (f1, f2) = centered()
            assertFalse("第 ${i + 1} 帧：窗口没满", e.update(f1, f2, target, true, true))
        }
        val (f1, f2) = centered()
        assertTrue("窗口满了就该判在区", e.update(f1, f2, target, true, true))
        repeat(20) {
            val (a, b) = centered()
            assertTrue(e.update(a, b, target, true, true))
        }
    }

    @Test
    fun windowMustFillBeforeJudging() {
        val e = ZoneEvaluator(windowFrames = 12)
        // 窗口没满不该判定 —— 几帧说明不了什么
        for (i in 0 until 11) {
            val (f1, f2) = centered()
            assertFalse("第 ${i + 1} 帧：窗口没满就不该判定", e.update(f1, f2, target, true, true))
        }
        val (f1, f2) = centered()
        assertTrue(e.update(f1, f2, target, true, true))
    }

    @Test
    fun oneBadFrameDoesNotBreakTheHold() {
        val e = ZoneEvaluator(windowFrames = 12, requiredDwell = 0.6f)
        // 先稳定在区里
        repeat(20) {
            val (f1, f2) = centered()
            e.update(f1, f2, target, true, true)
        }
        // 单帧坏读数（F3 冒充 F2）
        val (f1, f2) = centered()
        assertTrue(
            "单帧坏读数不该断保持",
            e.update(f1, 2400f, target, true, true),
        )
        // 恢复正常后仍然在区
        val (a, b) = centered()
        assertTrue(e.update(a, b, target, true, true))
    }

    @Test
    fun onsetTransitionIsNotCountedAsInZone() {
        val e = ZoneEvaluator(windowFrames = 12, requiredDwell = 0.6f)

        // 用实测的起音过渡：/衣/ 的 F2 从 1522 一路滑到 2260
        // （目标区中心在 2679，所以整段过渡都在区外）。
        val startF1 = 474f
        val startF2 = 1522f
        val endF1 = 283f
        val endF2 = 2260f

        var wronglyJudged = false
        for (i in 0 until 40) {
            val t = i / 39f
            val f1 = startF1 + (endF1 - startF1) * t
            val f2 = startF2 + (endF2 - startF2) * t
            val hit = e.update(f1, f2, VowelTarget.I, true, true)
            // 整段过渡都离目标区还很远，不该判「已在区内」
            if (hit) wronglyJudged = true
        }
        assertFalse("起音过渡期间不该判成「已在目标区」", wronglyJudged)
    }

    @Test
    fun dwellProportionMustBeReached() {
        // 窗口 10 帧，要求 60% 在区 → 至少 6 帧
        val e = ZoneEvaluator(windowFrames = 10, requiredDwell = 0.6f)
        // 6 帧在区、4 帧不在
        for (i in 0 until 6) {
            val (f1, f2) = centered()
            e.update(f1, f2, target, true, true)
        }
        for (i in 0 until 4) {
            val (f1, f2) = outside()
            e.update(f1, f2, target, true, true)
        }
        // 6/10 = 60% → 刚好达标
        val (f1, f2) = centered()
        assertTrue("6/10 应算达标", e.update(f1, f2, target, true, true))

        // 只有 4/10 → 不达标
        val e2 = ZoneEvaluator(windowFrames = 10, requiredDwell = 0.6f)
        for (i in 0 until 4) {
            val (a, b) = centered()
            e2.update(a, b, target, true, true)
        }
        for (i in 0 until 6) {
            val (a, b) = outside()
            e2.update(a, b, target, true, true)
        }
        val (a, b) = centered()
        assertFalse("4/10 不该算达标", e2.update(a, b, target, true, true))
    }

    @Test
    fun switchingTargetClearsTheWindow() {
        val e = ZoneEvaluator(windowFrames = 12, requiredDwell = 0.6f)
        repeat(20) {
            val (f1, f2) = centered()
            e.update(f1, f2, target, true, true)
        }
        // 换目标：旧目标的分布对新目标没意义，窗口要清空
        e.setTarget(VowelTarget.I)
        val (f1, f2) = centered()
        assertFalse(
            "换目标后不该沿用旧窗口的判定",
            e.update(f1, f2, VowelTarget.I, true, true),
        )
    }
}
