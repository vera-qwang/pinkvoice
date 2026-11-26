package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LPC 分析器的**结构性**测试。
 *
 * ## 为什么这里不断言具体频率
 *
 * 之前有一堆「合成 /a/ 应该出 730/1090」的断言，全绿，
 * 但真机上 F1 整个丢失、错位一千多 Hz ——
 * 合成信号和真实语音差得太远（声门源倾斜、房间、麦克风全都没有），
 * 拿它标定出来的参数在真机上根本不成立。
 *
 * 所以精度由 [RealRecordingFormantTest] 用**真实录音 + Praat 基准**守着
 * （五段录音，平均误差 32 Hz）。这里只验不依赖信号细节的性质。
 */
class LpcAnalyzerTest {

    /** 分析器按 App 的配置构造（12 kHz / 11 阶）。 */
    private fun analyzer() = LpcAnalyzer(sampleRate = 11000, order = 10)

    /** 一段简单的有声信号（正弦），只是为了有能量供 LPC 拟合。 */
    private fun tone(freq: Double, n: Int = 2400): FloatArray =
        FloatArray(n) { (0.3 * kotlin.math.sin(2 * Math.PI * freq * it / 12000)).toFloat() }

    @Test
    fun rejectsSilence() {
        val lpc = analyzer()
        lpc.prepare(600)
        assertFalse("全零帧不该分析成功", lpc.analyze(FloatArray(600), 600))
        assertTrue("无效帧不该给出共振峰", lpc.extractFormants().empty)
    }

    @Test
    fun rejectsTooShortFrame() {
        val lpc = analyzer()
        lpc.prepare(600)
        assertFalse("帧太短不该分析成功", lpc.analyze(FloatArray(20) { 0.1f }, 20))
    }

    @Test
    fun isDeterministic() {
        val sig = tone(440.0)
        val a = analyzer().apply { prepare(600) }
        val b = analyzer().apply { prepare(600) }
        a.analyze(sig.copyOfRange(0, 600), 600)
        b.analyze(sig.copyOfRange(0, 600), 600)
        assertEquals(a.extractFormants(), b.extractFormants())
    }

    /**
     * 共振峰按频率升序、互不重叠。
     *
     * 这是元音图能用的前提 —— 不管信号是什么，
     * 报出来的 F1/F2/F3 必须是有序的。
     */
    @Test
    fun formantsAreOrdered() {
        for (f in doubleArrayOf(200.0, 440.0, 900.0, 1800.0)) {
            val lpc = analyzer()
            lpc.prepare(600)
            lpc.analyze(tone(f).copyOfRange(600, 1200), 600)
            val r = lpc.extractFormants()
            if (r.f1 > 0f && r.f2 > 0f) {
                assertTrue("F1(${r.f1}) 应低于 F2(${r.f2})", r.f1 < r.f2)
            }
            if (r.f2 > 0f && r.f3 > 0f) {
                assertTrue("F2(${r.f2}) 应低于 F3(${r.f3})", r.f2 < r.f3)
            }
        }
    }

    /**
     * F1 必须在人声范围内。
     *
     * 真 F1 的极点丢掉时，`roots[0]` 会变成 F2 ——
     * 那样报出来的「F1」会明显超过 1100 Hz。
     * 这个守卫是为了那种情况报未知，而不是错位报数。
     */
    @Test
    fun f1StaysInHumanRange() {
        for (f in doubleArrayOf(150.0, 300.0, 800.0, 2000.0)) {
            val lpc = analyzer()
            lpc.prepare(600)
            lpc.analyze(tone(f).copyOfRange(600, 1200), 600)
            val r = lpc.extractFormants()
            assertTrue(
                "F1 报成 ${r.f1} Hz，超出人声范围",
                r.f1 == 0f || r.f1 <= 1100f,
            )
        }
    }

    /** 包络在有效帧上应该能算，无效帧返回一个很低的值。 */
    @Test
    fun envelopeIsFinite() {
        val lpc = analyzer()
        lpc.prepare(600)
        lpc.analyze(tone(440.0).copyOfRange(600, 1200), 600)
        val v = lpc.envelopeDb(800f)
        assertTrue("包络应是有限值", v.isFinite())
        assertFalse(lpc.envelopeDb(800f) > 1e6f)
    }
}
