package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp

/**
 * 共振峰分析的门控行为。
 *
 * 元音识别和目标区那套已经删掉了（App 现在只显示分析结果），
 * 剩下的核心是「不是语音就不该产出读数」。
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
    }

}