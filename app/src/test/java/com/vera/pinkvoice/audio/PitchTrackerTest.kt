package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 音高跟踪器的迟滞行为测试。
 * 这些用例直接对应真机实测到的问题：
 *  1) 底噪造成单帧伪周期（曲线上的竖直尖刺）；
 *  2) 起音冲击瞬态造成一个假的低频毛刺（自检 220Hz 时曲线底部那个下探）。
 */
class PitchTrackerTest {

    private fun cents(a: Float, b: Float) =
        abs(PitchMath.centsBetween(a.toDouble(), b.toDouble()))

    /** 走完起音确认，返回稳定后的音高。 */
    private fun PitchTracker.onset(hz: Float): Float {
        var out = accept(hz)
        if (out == 0f) out = accept(hz)
        return out
    }

    @Test
    fun rejectsSingleFrameOutlier() {
        val t = PitchTracker()
        assertEquals(0f, t.accept(200f), 0.1f)      // 起音待确认
        assertEquals(200f, t.accept(200f), 0.1f)     // 确认起音
        // 单帧离群点：必须被丢弃，输出仍保持 200
        assertEquals(200f, t.accept(800f), 0.1f)
        // 回到正常值，曲线平滑
        assertEquals(200f, t.accept(200f), 0.1f)
    }

    @Test
    fun onsetTransientIsNotShownAsLowPitch() {
        // 真机自检：220Hz 音起音瞬间 YIN 会给一个 ~85Hz 的瞬态估计，
        // 起音确认要求连续两帧一致，瞬态被丢弃，220Hz 需再等一帧才确认
        val t = PitchTracker()
        assertEquals(0f, t.accept(85f), 0.1f)     // 瞬态：未确认，不上曲线
        assertEquals(0f, t.accept(220f), 0.1f)    // 换成了 220Hz，但仍未确认
        assertEquals(220f, t.accept(220f), 0.1f)  // 确认起音
    }

    @Test
    fun sustainedJumpTakesOverAfterThreeFrames() {
        val t = PitchTracker()
        t.onset(200f)
        // 第一次偏离：挂起
        assertEquals(200f, t.accept(400f), 0.1f)
        // 第二次偏离：仍挂起
        assertEquals(200f, t.accept(400f), 0.1f)
        // 第三次一致：真正切换
        assertEquals(400f, t.accept(400f), 0.1f)
    }

    @Test
    fun vibratoAndGlideTrackImmediately() {
        val t = PitchTracker()
        t.onset(200f)
        // 容差是相对"上一帧"的：真实颤音每帧只走几 cent，必须逐帧跟随
        val up = 200f * Math.pow(2.0, 10.0 / 1200.0).toFloat()   // +10 cent
        assertEquals(up, t.accept(up), 0.5f)
        val down = 200f * Math.pow(2.0, -10.0 / 1200.0).toFloat() // 相对上一帧 -20 cent
        assertEquals(down, t.accept(down), 0.5f)
    }

    @Test
    fun bridgesShortUnvoicedGaps() {
        val t = PitchTracker()
        t.onset(200f)
        // 短暂无声保持音高
        assertEquals(200f, t.accept(0f), 0.1f)
        assertEquals(200f, t.accept(0f), 0.1f)
        // 超过桥接长度才判定为无声
        assertEquals(0f, t.accept(0f), 0.1f)
    }

    @Test
    fun startsFromSilence() {
        val t = PitchTracker()
        assertEquals(0f, t.accept(0f), 0.1f)
        assertEquals(0f, t.accept(0f), 0.1f)
        assertEquals(0f, t.accept(150f), 0.1f)  // 起音待确认
        assertEquals(150f, t.accept(150f), 0.1f) // 确认起音
    }

    @Test
    fun noiseBurstDoesNotDisturbStablePitch() {
        // 模拟真机：稳定 200 Hz 底噪中偶发 800 Hz 伪周期
        val t = PitchTracker()
        t.onset(200f)
        repeat(4) { t.accept(200f) }
        val outs = mutableListOf<Float>()
        repeat(20) { i ->
            val v = if (i % 7 == 3) 800f else 200f
            outs += t.accept(v)
        }
        val maxDeviation = outs.map { cents(it, 200f) }.max()
        assertTrue("噪声不应让曲线偏离稳定音高（实测 ${maxDeviation.toInt()} cent）", maxDeviation < 60.0)
    }
}
