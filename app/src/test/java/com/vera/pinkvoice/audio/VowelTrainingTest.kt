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

    /**
     * 单帧无效**不该**清空历史。
     *
     * 这是用户反复反馈「F1/F2 还在乱飘」的直接原因之一：
     * 以前任何一帧无效就 reset()，把 272 ms 的中位数历史全扔掉，
     * 下一帧从头开始 —— 画面立刻开始抖。
     *
     * 现在短暂无效直接保持原值（和 PitchTracker 的补洞思路一致），
     * 连续断够久才真的清空。
     */
    @Test
    fun smootherHoldsThroughShortGaps() {
        val s = FormantSmoother()
        s.add(700f, 1400f)
        s.add(710f, 1420f)
        s.add(715f, 1410f)
        // 断一帧
        val held = s.add(0f, 0f)
        assertTrue("短暂中断时应保持原值，而不是清零", held.f1 > 0f)
        assertTrue("保持的值应该在合理范围", abs(held.f1 - 710f) < 30f)
        // 恢复后应该接着用历史（不会被从头再来）
        val resumed = s.add(712f, 1415f)
        assertEquals("恢复后应延续原来的中位数", 712f, resumed.f1, 15f)
    }

    /** 断得够久才真的清空。 */
    @Test
    fun smootherClearsAfterLongGap() {
        val s = FormantSmoother()
        s.add(700f, 1400f)
        repeat(10) { s.add(0f, 0f) }
        val out = s.add(0f, 0f)
        assertEquals("信号长时间断了就该清空", 0f, out.f1, 0.01f)
        assertEquals("信号长时间断了就该清空", 0f, out.f2, 0.01f)
    }

    // ---------------------------------------------------------------- 目标区

    @Test
    fun zoneIsTheFeminizedTargetArea() {
        val a = VowelTarget.A
        // 目标区域中心 = 女性化目标
        assertTrue(
            "目标中心应在区内",
            VowelZone.isInZone(
                FeminineTarget.centerF1(a),
                FeminineTarget.centerF2(a),
                a,
            ),
        )
        // 区域边缘也在区内
        assertTrue(
            "区域边缘应算达标",
            VowelZone.isInZone(
                FeminineTarget.centerF1(a) + FeminineTarget.radiusF1(a) - 1f,
                FeminineTarget.centerF2(a),
                a,
            ),
        )
        // 超出区域就出区
        assertFalse(
            "超出区域应出区",
            VowelZone.isInZone(
                FeminineTarget.centerF1(a) + FeminineTarget.radiusF1(a) + 20f,
                FeminineTarget.centerF2(a),
                a,
            ),
        )
    }

    @Test
    fun everyVowelHasTheSameDifficulty() {
        // 目标区域用**音分**容差，所以每个元音一样难。
        //
        // 用 Hz 的话天差地别：/衣/ 的 F1=297 只能容忍 ±36 Hz，
        // 而 /啊/ 的 F2=1308 能容忍 ±158 Hz —— 差四倍多。
        // 用音分的话每个都是 ±12%，用户练哪个都是同样的挑战。
        for (v in VowelTarget.entries) {
            // ±120 音分 = ±7.18%（半宽），全宽 14.4%
            //
            // 注意别写成 12%：120/1200 个八度 = 2^(0.1) = 1.0718，
            // 也就是 ±7.18%，不是 ±12%。
            val pctF1 = FeminineTarget.radiusF1(v) / FeminineTarget.centerF1(v) * 100f
            val pctF2 = FeminineTarget.radiusF2(v) / FeminineTarget.centerF2(v) * 100f
            assertTrue(
                "${v.label} 的 F1 容差半宽应约 7.18%（实际 $pctF1%）",
                kotlin.math.abs(pctF1 - 7.18f) < 0.2f,
            )
            assertTrue(
                "${v.label} 的 F2 容差半宽应约 7.18%（实际 $pctF2%）",
                kotlin.math.abs(pctF2 - 7.18f) < 0.2f,
            )
        }
    }

    @Test
    fun feminineTargetShiftsForwardAndUp() {
        // 目标 = 参考值 × 系数：F1 往上抬，F2 往前移
        for (v in VowelTarget.entries) {
            assertTrue(
                "${v.label} 的目标 F1 应该更高（抬）",
                FeminineTarget.centerF1(v) > v.f1,
            )
            assertTrue(
                "${v.label} 的目标 F2 应该更高（往前）",
                FeminineTarget.centerF2(v) > v.f2,
            )
            // 系数是**逐元音**的（吴宗济普通话表的男/女配对），
            // 不是全局常数 —— 之前用的全局 1.18/1.17 来自英语文献。
            assertEquals(
                "${v.label} 的 F1 系数",
                v.femaleF1Ratio,
                FeminineTarget.centerF1(v) / v.f1,
                0.001f,
            )
            assertEquals(
                "${v.label} 的 F2 系数",
                v.femaleF2Ratio,
                FeminineTarget.centerF2(v) / v.f2,
                0.001f,
            )
        }
    }

    /**
     * 女性化之后，元音的身份还得认得对。
     *
     * 这是**必须有**的：用户练到目标位置时，界面上的「元音身份」
     * 不能变成邻居 —— 那会让用户以为自己练错了。
     *
     * 只用男性参考位置的话，普通话 6 个元音里有 4 个会认错
     * （目标位置落在下一个元音的自然格里）。
     * [VowelRecognizer] 的门限做法解决这个 —— 见那里的说明。
     */
    @Test
    fun vowelIdentityStillWorksAtTheFeminizedTarget() {
        for (v in VowelTarget.entries) {
            val f1 = FeminineTarget.centerF1(v)
            val f2 = FeminineTarget.centerF2(v)
            val got = VowelRecognizer.recognize(f1, f2).vowel
            assertTrue(
                "在目标位置的 ${v.label} 被认成了 $got",
                got == v,
            )
        }
    }

    /**
     * 自然位置上的识别不能被「目标位置」抢走。
     *
     * 女性化目标点落在元音空间中间，如果无条件参与比对，
     * 会把本该属于别人的读数吸过去。
     */
    @Test
    fun naturalPositionsStillRecognizeCorrectly() {
        for (v in VowelTarget.entries) {
            val got = VowelRecognizer.recognize(v.f1, v.f2).vowel
            assertTrue(
                "在自然位置的 ${v.label} 被认成了 $got",
                got == v,
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
            VowelZone.isF2Credible(FeminineTarget.centerF2(a), a),
        )
    }

    @Test
    fun f2NotCredibleWhenItIsActuallyF3() {
        // 真机实测 /a/：期望 F2=1090，报出来的是 2407（那是 /a/ 的 F3）
        val a = VowelTarget.A
        assertFalse(
            "F3 冒充 F2 时必须判为不可信",
            VowelZone.isF2Credible(2407f, a),
        )
    }

    @Test
    fun f2OfVowelIStaysCredible() {
        // /i/ 的目标 F2 在 2750 附近，不能被误判成 F3
        val i = VowelTarget.I
        assertTrue(
            "/i/ 的 F2 不该被判为不可信",
            VowelZone.isF2Credible(FeminineTarget.centerF2(i), i),
        )
    }



    /**
     * 可信度要同时罩住「出发位置」和「目标位置」。
     *
     * 之前拿女性化目标当唯一参照 —— 而用户现在就在参考位置，
     * /啊/ 的真值 F2=1090、目标 1308，差 316 音分，
     * 于是**一个完全正确的读数被判成「读数不稳」**。
     */
    @Test
    fun credibleRangeCoversBothReferenceAndTarget() {
        for (v in VowelTarget.entries) {
            // 参考位置（用户出发的地方）必须可信
            assertTrue(
                "${v.label} 在参考位置的 F2（${v.f2.toInt()}）不该被判为不可信",
                VowelZone.isF2Credible(v.f2, v),
            )
            // 目标位置（用户要到达的地方）也必须可信
            assertTrue(
                "${v.label} 在目标位置的 F2 不该被判为不可信",
                VowelZone.isF2Credible(FeminineTarget.centerF2(v), v),
            )
            // 两者中间当然也要可信
            val mid = (v.f2 + FeminineTarget.centerF2(v)) / 2f
            assertTrue(
                "${v.label} 在中间位置的 F2 不该被判为不可信",
                VowelZone.isF2Credible(mid, v),
            )
            // 离目标区域很远的值必须判为不可信。
            //
            // 注意不能用「F3 的位置」来判：/衣/ 的目标 F2 是 2748，
            // 它的 F3 也在 3010 —— 两者只差 100 音分，
            // 频率上根本分不开（元音图的天花板，见 VowelRecognizer 的说明）。
            assertFalse(
                "${v.label} 在 4000 Hz 该判为不可信",
                VowelZone.isF2Credible(4000f, v),
            )
        }
    }

}
