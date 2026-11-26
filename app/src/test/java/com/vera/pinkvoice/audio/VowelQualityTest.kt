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
     * 合成元音能被认出来。
     *
     * 用的是**原始频率** —— 不做声道归一化。
     * 归一换算是跨人对比的科研工具，对训练没帮助。
     */
    @Test
    fun recognizesSyntheticVowels() {
        val cases = listOf(
            Triple("a", 120.0, doubleArrayOf(730.0, 1090.0, 2440.0)),
            Triple("i", 200.0, doubleArrayOf(270.0, 2290.0, 3010.0)),
            Triple("u", 120.0, doubleArrayOf(300.0, 870.0, 2240.0)),
            Triple("ae", 150.0, doubleArrayOf(660.0, 1720.0, 2410.0)),
        )
        for ((name, f0, fm) in cases) {
            // 每个用例用**独立的**分析器。
            //
            // 共用一个的话，分析器的滑动缓冲里会混着上一个元音的样本，
            // 而 F1/F2 的中位数平滑窗口也会跨过信号边界 ——
            // 那是在测"转音时的行为"，不是"这个元音认不认得出来"。
            val analyzer = VoiceQualityAnalyzer(sampleRate)
            val sig = synth(f0, fm)
            val frame = sig.copyOfRange((sig.size - 1024) / 2, (sig.size - 1024) / 2 + 1024)
            analyzer.process(frame, 1024)
            val got = analyzer.recognized
            assertNotNull("$name 应该被识别出来", got)
            // 用枚举精确相等。
            //
            // 之前是 `name.lowercase().startsWith(name)` ——
            // "ae".startsWith("a") 为真，所以「啊」被认成「哎」也算通过，
            // 这条断言有一半是恒真的。
            val expected = VowelTarget.entries.first { it.name.lowercase() == name }
            assertEquals("$name 被认成了 $got", expected, got)
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
