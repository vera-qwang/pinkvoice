package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * 元音识别、稳态度、音色轴。
 *
 * 这几项**都有客观 ground truth**（念什么元音自己知道），
 * 所以能直接验证 —— 这是它们比「虚实检测」好的地方。
 */
class VowelQualityTest {

    private val sampleRate = 16000

    // ---------- 合成工具 ----------

    /** 合成元音。用共享的 [VowelSynth] —— 频谱比例是真实的。 */
    private fun synth(f0: Double, formants: DoubleArray): FloatArray =
        VowelSynth.synth(f0, formants)

    // ---------- 元音识别 ----------

            /**
     * **不是语音帧就不该产出共振峰。**
     *
     * 这是「F1/F2 乱跳」最隐蔽的一个原因：以前每一帧都跑 LPC，
     * 环境噪音也被拟合成「看起来很合理」的共振峰
     * （实测静音段能算出 297/1611、455/1318 这种值），
     * 于是用户没说话时界面照样在跳数。
     */
    @Test
    fun unvoicedFramesProduceNothing() {
        val analyzer = VoiceQualityAnalyzer(sampleRate)
        val sig = synth(145.0, doubleArrayOf(730.0, 1090.0, 2440.0))

        // 先确认有声时确实能出结果
        analyzer.voiced = true
        analyzer.process(sig, sig.size)
        assertTrue("有声时应该能测出共振峰", analyzer.formants.f1 > 0f)

        // 关掉门控 → 什么都不该有
        analyzer.voiced = false
        analyzer.process(sig, sig.size)
        assertEquals("不是语音帧不该有 F1", 0f, analyzer.formants.f1, 0.01f)
        assertEquals("不是语音帧不该有 F2", 0f, analyzer.formants.f2, 0.01f)
        assertNull("不是语音帧不该识别出元音", analyzer.recognized)
        assertEquals("不是语音帧不该计时", 0f, analyzer.holdSeconds, 0.01f)
    }

/**
     * 合成元音应该能被识别出来。
     *
     * **只断言「认得出是个元音」，不断言认成哪个。**
     *
     * 合成信号和真实语音差得远，F1 会系统性偏高几十到上百 Hz，
     * 足够让相邻元音混淆。真实语音上的识别准确度
     * 由 [RealRecordingFormantTest] 覆盖。
     */
    @Test
    fun recognizesSyntheticVowelsAsSomeVowel() {
        val cases = listOf(
            Triple("a", 120.0, doubleArrayOf(730.0, 1090.0, 2440.0)),
            Triple("i", 200.0, doubleArrayOf(270.0, 2290.0, 3010.0)),
            Triple("u", 120.0, doubleArrayOf(300.0, 870.0, 2240.0)),
        )
        for ((name, f0, fm) in cases) {
            val analyzer = VoiceQualityAnalyzer(sampleRate)
            val sig = synth(f0, fm)
            analyzer.process(sig, sig.size)
            assertNotNull("$name 应该被识别出一个元音", analyzer.recognized)
        }
    }

    /** 不同的元音要能区分开。 */
    @Test
    fun distinguishesVowels() {
        val a = VowelRecognizer.recognize(730f, 1090f)
        val i = VowelRecognizer.recognize(270f, 2290f)
        val u = VowelRecognizer.recognize(300f, 870f)
        assertEquals(VowelTarget.A, a.vowel)
        assertEquals(VowelTarget.I, i.vowel)
        assertEquals(VowelTarget.U, u.vowel)
    }

    /** 无效输入不该给出结果。 */
    @Test
    fun rejectsInvalidInput() {
        val r = VowelRecognizer.recognize(0f, 0f)
        assertNull(r.vowel)
        assertTrue(!r.confident)
    }


}
