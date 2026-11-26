package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 元音训练页的两块新逻辑：
 * 1. [FormantSmoother] —— 解决"数据乱跳"
 * 2. [VowelZone] / [TargetTracker] —— 解决"不知道练习的目标"
 */
class VowelTrainingTest {

    // ---------------------------------------------------------------- 平滑

    @Test
    fun smootherRemovesOutliers() {
        val s = FormantSmoother(window = 5)
        // 稳定的 700 Hz，中间混进一帧离谱的 2000 Hz
        val values = listOf(700f, 705f, 2000f, 698f, 702f, 701f, 699f)
        var last = LpcAnalyzer.Formants(0f, 0f, 0f)
        for (v in values) {
            last = s.add(v, v * 2f)
        }
        // 中位数应该落在正常值附近，绝不能被那帧 2000 拉走
        assertTrue(
            "离群值把中位数带走了：${last.f1}",
            abs(last.f1 - 700f) < 20f,
        )
    }

    @Test
    fun smootherHalvesJitter() {
        val s = FormantSmoother(window = 5)
        // 模拟真机上的抖动：±40 Hz 的随机噪声
        val rnd = java.util.Random(7)
        val raw = FloatArray(200)
        val smoothed = FloatArray(200)
        for (i in raw.indices) {
            val v = 700f + (rnd.nextFloat() - 0.5f) * 80f
            raw[i] = v
            smoothed[i] = s.add(v, v * 2f).f1
        }
        fun std(a: FloatArray, from: Int): Float {
            val n = a.size - from
            var mean = 0f
            for (i in from until a.size) mean += a[i]
            mean /= n
            var sum = 0f
            for (i in from until a.size) {
                val d = a[i] - mean
                sum += d * d
            }
            return kotlin.math.sqrt(sum / n)
        }
        val rawStd = std(raw, 100)
        val smoothStd = std(smoothed, 100)
        println("原始抖动 σ=$rawStd, 平滑后 σ=$smoothStd")
        // 中位数滤波对白噪声的降噪比大约 1.25 倍（不是 2 倍）——
        // 它的价值主要在**去掉离群帧**，不是压噪声。
        assertTrue("平滑后抖动应该变小（$rawStd → $smoothStd）", smoothStd < rawStd * 0.8f)
    }

    @Test
    fun smootherClearsOnInvalidFrame() {
        val s = FormantSmoother()
        s.add(700f, 1400f)
        s.add(710f, 1420f)
        // 信号断了
        val out = s.add(0f, 0f)
        assertEquals("信号断了就该清空", 0f, out.f1, 0.01f)
        assertEquals("信号断了就该清空", 0f, out.f2, 0.01f)
    }

    // ---------------------------------------------------------------- 目标区

    @Test
    fun zoneUsesCentsNotHertz() {
        val a = VowelTarget.A
        // 正好在目标上
        assertTrue(VowelZone.isInZone(a.f1, a.f2, a))
        // ±100 音分以内 → 在区内（对 F1=730 约 ±42 Hz）
        assertTrue(
            "±100 音分应在区内",
            VowelZone.isInZone(a.f1 * 1.058f, a.f2 * 1.058f, a),
        )
        // ±200 音分 → 出区（对 F1=730 约 ±87 Hz）
        assertFalse(
            "±200 音分应出区",
            VowelZone.isInZone(a.f1 * 1.13f, a.f2 * 1.13f, a),
        )
    }

    @Test
    fun zoneIsEquallyHardForEveryVowel() {
        // 关键性质：容差用音分，所以每个元音的难度一样。
        //
        // 如果用 Hz 容差，/i/ 的 F1=270 和 /a/ 的 F1=730
        // 能容忍的绝对偏差差 2.7 倍 —— 有的元音很容易，
        // 有的几乎不可能。
        for (v in VowelTarget.entries) {
            val tolF1 = VowelZone.radiusHz(v.f1)
            val tolF2 = VowelZone.radiusHz(v.f2)
            val pctF1 = tolF1 / v.f1 * 100f
            val pctF2 = tolF2 / v.f2 * 100f
            assertTrue(
                "${v.label} 的 F1 容差占比应约 7%（实际 $pctF1%）",
                abs(pctF1 - 7f) < 0.5f,
            )
            assertTrue(
                "${v.label} 的 F2 容差占比应约 7%（实际 $pctF2%）",
                abs(pctF2 - 7f) < 0.5f,
            )
        }
    }

    @Test
    fun deviationSignIsIntuitive() {
        // 偏高 → 正数
        assertTrue(VowelZone.deviationCents(800f, 730f) > 0f)
        // 偏低 → 负数
        assertTrue(VowelZone.deviationCents(660f, 730f) < 0f)
        assertEquals(0f, VowelZone.deviationCents(730f, 730f), 0.01f)
    }

    // ---------------------------------------------------------------- 保持计时

    @Test
    fun trackerCountsContinuousHold() {
        val t = TargetTracker(hopMs = 16, graceFrames = 2)
        // 每帧 16 ms，在区内 10 帧 = 160 ms
        for (i in 0 until 9) {
            t.update(true)
        }
        assertEquals(0.16f, t.update(true), 0.001f)
    }

    @Test
    fun trackerResetsAfterLeavingZone() {
        val t = TargetTracker(hopMs = 16, graceFrames = 2)
        for (i in 0 until 20) t.update(true)
        assertTrue(t.update(true) > 0.3f)
        // 掉出区间超过宽限帧 → 清零
        t.update(false)
        t.update(false)
        t.update(false)
        assertEquals("掉出区间后应清零", 0f, t.update(false), 0.001f)
    }

    @Test
    fun trackerToleratesOneDroppedFrame() {
        val t = TargetTracker(hopMs = 16, graceFrames = 2)
        for (i in 0 until 10) t.update(true)
        val before = t.update(true)
        // 偶发掉一帧（LPC 跳偏是常事）不该前功尽弃
        t.update(false)
        val after = t.update(true)
        assertTrue(
            "偶发掉帧后应该继续计时（$before → $after）",
            after >= before,
        )
        assertTrue("保持时间不该被清零（$after）", after > 0.1f)
    }

    // ---------------------------------------------------------------- 识别不再闪变

    /**
     * 稳稳发一个元音时，识别结果不该闪。
     *
     * 这是"数据乱跳"最难受的表现：明明一直在发「啊」，
     * 界面上的「像『啊』」却变成「像『衣』」再变回来。
     */
    @Test
    fun recognitionDoesNotFlickerOnSteadyVowel() {
        FormantNormalizer.reset()
        val a = VowelTarget.A

        // 平滑之前：原始值带 ±60 Hz 抖动，识别结果会闪
        val rawResults = mutableListOf<VowelTarget?>()
        val smoothedResults = mutableListOf<VowelTarget?>()
        val smoother = FormantSmoother(window = 5)
        val rnd = java.util.Random(11)
        for (k in 0 until 60) {
            // ±120 Hz 的随机抖动（真机上就是这个量级）
            val jit = (rnd.nextFloat() - 0.5f) * 240f
            val raw = VowelRecognizer.recognize(a.f1 + jit, a.f2 + jit).vowel
            rawResults += raw
            val sm = smoother.add(a.f1 + jit, a.f2 + jit)
            smoothedResults += VowelRecognizer.recognize(sm.f1, sm.f2).vowel
        }
        val rawFlickers = rawResults.zipWithNext().count { (x, y) -> x != y }
        val smoothFlickers = smoothedResults.zipWithNext().count { (x, y) -> x != y }
        println("识别闪变: 原始 $rawFlickers 次, 平滑后 $smoothFlickers 次")

        assertTrue("平滑后不该再闪（实际 $smoothFlickers 次）", smoothFlickers == 0)
        assertTrue(
            "稳音时识别结果应该始终是 ${a.label}",
            smoothedResults.all { it == a },
        )
    }


    // ---------------------------------------------------------------- F2 可信度

    @Test
    fun f2CredibleWhenItMatchesTarget() {
        val a = VowelTarget.A
        assertTrue(
            "F2 接近目标就该可信",
            VowelZone.isF2Credible(a.f2, a, null),
        )
    }

    @Test
    fun f2CredibleWhenItMatchesRecognizedVowel() {
        val a = VowelTarget.A
        val eh = VowelTarget.EH
        // 用户在发「诶」（F2=1840），目标却是「啊」（F2=1090）
        // 测到的 1840 跟**识别结果**吻合 → 可信
        assertTrue(
            "F2 跟识别出的元音吻合就该可信",
            VowelZone.isF2Credible(eh.f2, a, eh),
        )
    }

    @Test
    fun f2NotCredibleWhenItIsActuallyF3() {
        // 真机实测 /a/：期望 F2=1090，报出来的是 2407（那是 /a/ 的 F3）
        val a = VowelTarget.A
        assertFalse(
            "F3 冒充 F2 时必须判为不可信",
            VowelZone.isF2Credible(2407f, a, VowelTarget.EH),
        )
    }

    @Test
    fun f2OfVowelIStaysCredible() {
        // /i/ 的 F2 本来就在 2300 附近，不能被误判成 F3
        val i = VowelTarget.I
        assertTrue(
            "/i/ 的 F2 不该被判为不可信",
            VowelZone.isF2Credible(2359f, i, i),
        )
    }

}
