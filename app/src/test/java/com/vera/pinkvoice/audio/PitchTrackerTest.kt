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
        var out = 0f
        repeat(4) {
            val v = accept(hz)
            if (v > 0f) out = v
        }
        return out
    }

    @Test
    fun rejectsSingleFrameOutlier() {
        val t = PitchTracker()
        // 起音要连续 3 帧一致才认（见 onsetFrames 的说明）
        assertEquals(0f, t.accept(200f), 0.1f)
        assertEquals(0f, t.accept(200f), 0.1f)
        assertEquals(200f, t.accept(200f), 0.1f)   // 确认起音
        // 单帧离群点：必须被丢弃，输出仍保持 200
        assertEquals(200f, t.accept(800f), 0.1f)
        // 回到正常值，曲线平滑
        assertEquals(200f, t.accept(200f), 0.1f)
    }

    @Test
    fun onsetTransientIsNotShownAsLowPitch() {
        // 真机自检：220Hz 音起音瞬间 YIN 会给一个 ~85Hz 的瞬态估计，
        // 起音确认要求连续三帧一致，瞬态被丢弃
        val t = PitchTracker()
        assertEquals(0f, t.accept(85f), 0.1f)     // 瞬态：未确认，不上曲线
        assertEquals(0f, t.accept(220f), 0.1f)    // 换成了 220Hz，但仍未确认
        assertEquals(0f, t.accept(220f), 0.1f)    // 还差一帧
        assertEquals(220f, t.accept(220f), 0.1f)  // 确认起音
    }

    @Test
    fun sustainedJumpTakesOverAfterThreeFrames() {
        val t = PitchTracker()
        t.onset(200f)
        // 第一次偏离：挂起
        assertEquals(200f, t.accept(250f), 0.1f)
        // 第二次偏离：仍挂起
        assertEquals(200f, t.accept(250f), 0.1f)
        // 第三次一致：真正切换
        assertEquals(250f, t.accept(250f), 0.1f)
    }

    /**
     * 八度误判：YIN 把 f0 报成 f0/2（或 2×f0），而且常常连续两三帧。
     *
     * 真机录下来的数据（帧 1548–1553）就是这样：
     * 稳定值 156.4 Hz，检测值连着三帧给出 77.9 / 78.0 / 77.7 Hz，
     * 清晰度还不低（0.76~0.80）。按普通的 3 帧规则，稳定值整个跳到
     * 低八度，曲线随之出现一个又深又窄的倒钩。
     *
     * 八度候选要连续 6 帧一致才允许接管。
     */
    @Test
    fun octaveErrorDoesNotTakeOver() {
        val t = PitchTracker()
        t.onset(156.4f)
        repeat(4) { t.accept(156.4f) }
        // 八度误判：低八度，连续八帧（真机实测最长能到 8 帧）
        repeat(8) { assertEquals(156.4f, t.accept(78.0f), 0.1f) }
        // 恢复正常值，曲线平滑回到原位
        assertEquals(156.4f, t.accept(169.8f), 2f)
    }

    /** 高八度的误判同样要挡住。 */
    @Test
    fun octaveUpErrorDoesNotTakeOver() {
        val t = PitchTracker()
        t.onset(189.6f)
        repeat(4) { t.accept(189.6f) }
        repeat(8) { assertEquals(189.6f, t.accept(378.7f), 0.1f) }
        assertEquals(189.6f, t.accept(190.0f), 2f)
    }

    /**
     * 真正的八度跳变（唱上去）最终还是要能跟上——只是慢一点。
     *
     * 门槛是 6 帧（96 ms）。真唱八度上去会持续更久，所以不会被挡住。
     */
    @Test
    fun genuineOctaveJumpEventuallyTakesOver() {
        val t = PitchTracker()
        t.onset(200f)
        repeat(4) { t.accept(200f) }
        var out = 200f
        repeat(9) { out = t.accept(400f) }
        assertEquals("9 帧还没到门槛", 200f, out, 0.1f)
        out = t.accept(400f)
        assertEquals("第 10 帧应该接管", 400f, out, 0.1f)
    }

    /**
     * 起音锁错八度：真机数据里栽过。
     *
     * 唱到 157 Hz，起音那两帧 YIN 给的是 75.1 / 75.7 Hz（低八度），
     * 两帧一致就把稳定值定在了低八度。现在起音要 3 帧才认。
     */
    @Test
    fun onsetDoesNotLockOntoOctaveError() {
        val t = PitchTracker()
        // 起音误判：两帧低八度——不够三帧，锁不上去
        assertEquals(0f, t.accept(75.1f), 0.1f)
        assertEquals(0f, t.accept(75.7f), 0.1f)
        // 真实音高出现，连续三帧才确认起音
        assertEquals(0f, t.accept(157.4f), 0.1f)
        assertEquals(0f, t.accept(157.7f), 0.1f)
        assertEquals(157.4f, t.accept(159.6f), 0.1f)
    }

    /** 非八度的跳变仍然只要 3 帧——滑音和正常的换音不能被拖慢。 */
    @Test
    fun nonOctaveJumpStillTakesOverFast() {
        val t = PitchTracker()
        t.onset(200f)
        assertEquals(200f, t.accept(250f), 0.1f)
        assertEquals(200f, t.accept(250f), 0.1f)
        assertEquals(250f, t.accept(250f), 0.1f)
    }

    /**
     * 中等跳变（400~700 cent）要 6 帧。
     *
     * 分级的意义：小跳变照旧 3 帧，大的才慢慢来。
     */
    @Test
    fun midJumpTakesOverAfterSixFrames() {
        val t = PitchTracker()
        t.onset(200f)
        repeat(4) { t.accept(200f) }
        var out = 200f
        repeat(5) { out = t.accept(150f) }   // -498 cent
        assertEquals("5 帧还没到门槛", 200f, out, 0.1f)
        out = t.accept(150f)
        assertEquals("第 6 帧应该接管", 150f, out, 0.1f)
    }

    /** 滑音（每步几 cent）不受确认帧数影响，逐帧跟随。 */
    @Test
    fun glideIsNotDelayed() {
        val t = PitchTracker()
        t.onset(200f)
        // 从 200 一路滑到 260，每步 10 cent
        var hz = 200f
        while (hz < 260f) {
            hz *= Math.pow(2.0, 10.0 / 1200.0).toFloat()
            assertEquals("滑音必须逐帧跟随", hz, t.accept(hz), 0.5f)
        }
    }

    /**
     * 八度候选挂起期间，稳定的音高不能被带偏。
     *
     * 真机数据里就有一处：稳定值 189.4，检测值先给 170.3（正常），
     * 再给 77.9（八度误判）。误判被挡住之后，原来的音高原样保留。
     */
    @Test
    fun stablePitchSurvivesOctaveErrorBurst() {
        val t = PitchTracker()
        t.onset(189.4f)
        repeat(4) { t.accept(189.4f) }
        val outs = listOf(170.3f, 166.6f, 164.0f, 160.8f, 159.2f, 157.2f)
        var last = 189.4f
        for (v in outs) last = t.accept(v)
        // 157.2 和 189.4 相差 320 cent，不是八度关系 → 3 帧规则就该跟上
        assertEquals("应该跟上真实音高", 157.2f, last, 0.1f)
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
        assertEquals(0f, t.accept(150f), 0.1f)   // 起音待确认
        assertEquals(0f, t.accept(150f), 0.1f)   // 还差一帧
        assertEquals(150f, t.accept(150f), 0.1f)  // 确认起音
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
