package com.vera.pinkvoice.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 用**真机录下来的原始数据**做回归测试。
 *
 * 这批数据是 PinkVoice 在真机上实测时抓的（Note 20 Ultra + Galaxy Buds Pro），
 * 逐帧记录了 YIN 的原始输出和清晰度，一共 11 万帧。
 *
 * 里面藏着典型的**八度误判**：YIN 把 f0 报成 f0/2 或 2×f0，而且常常
 * 连续好几帧、清晰度还不低（0.57~0.86），看着像是真的。
 *
 * 普通的 3 帧迟滞挡不住——第三帧一到，稳定值就整个跳到低八度，
 * 曲线上随之出现一个又深又窄的"倒钩"。这才是真正刺眼的那种大波动。
 *
 * 实测八度误判的持续长度分布是 3 / 4 / 4 / 6 / 7 / 8 帧（最长 128 ms），
 * 所以大跳变要 10 帧（160 ms）才认。
 */
class PitchTrackerRealDataTest {

    /** 真机录下来的片段：(YIN 原始输出 Hz, 清晰度)。 */
    /** 真机数据片段 1（原始 Hz, 清晰度）。 */
    private val seg1 = listOf(
        0.0f to 0.0f,
        89.0f to 0.51f,
        88.6f to 0.51f,
        0.0f to 0.0f,
        89.2f to 0.5f,
        0.0f to 0.0f,
        89.2f to 0.54f,
        176.8f to 0.67f,
        178.6f to 0.68f,
        179.8f to 0.58f,
        179.4f to 0.55f,
        177.4f to 0.5f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        169.0f to 0.53f,
        0.0f to 0.0f,
    )

    /** 真机数据片段 2（原始 Hz, 清晰度）。 */
    private val seg2 = listOf(
        0.0f to 0.0f,
        196.2f to 0.5f,
        196.1f to 0.51f,
        157.1f to 0.54f,
        174.1f to 0.5f,
        157.1f to 0.53f,
        0.0f to 0.0f,
        393.2f to 0.54f,
        393.1f to 0.56f,
        393.3f to 0.57f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        0.0f to 0.0f,
    )

    /** 真机数据片段 3（原始 Hz, 清晰度）。 */
    private val seg3 = listOf(
        195.6f to 0.61f,
        195.8f to 0.54f,
        195.6f to 0.56f,
        0.0f to 0.0f,
        391.7f to 0.57f,
        391.9f to 0.64f,
        391.9f to 0.62f,
        391.9f to 0.64f,
        391.8f to 0.63f,
        391.6f to 0.62f,
        391.7f to 0.55f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        87.0f to 0.5f,
        0.0f to 0.0f,
        195.8f to 0.57f,
        0.0f to 0.0f,
        0.0f to 0.0f,
    )

    /** 真机数据片段 4（原始 Hz, 清晰度）。 */
    private val seg4 = listOf(
        173.8f to 0.63f,
        166.2f to 0.83f,
        162.8f to 0.87f,
        159.4f to 0.94f,
        156.6f to 0.88f,
        162.9f to 0.57f,
        0.0f to 0.0f,
        474.7f to 0.55f,
        474.5f to 0.56f,
        475.2f to 0.58f,
        0.0f to 0.0f,
        0.0f to 0.0f,
        154.0f to 0.7f,
        152.2f to 0.83f,
        151.1f to 0.89f,
        150.7f to 0.91f,
        150.1f to 0.96f,
        149.8f to 0.97f,
    )

    /**
     * 走完整个片段，稳定值不能出现八度级的跳变。
     *
     * 八度误判的特征是"偏差正好在 1200 cent 附近"——
     * 小跳变（真实的滑音）不算。
     */
    @Test
    fun noOctaveSpikeInRealData() {
        listOf(seg1, seg2, seg3, seg4).forEachIndexed { idx, seg ->
            val t = PitchTracker()
            var prev = 0f
            for ((hz, cl) in seg) {
                val st = t.accept(hz, cl)
                if (prev > 0f && st > 0f) {
                    val d = abs(PitchMath.centsBetween(st.toDouble(), prev.toDouble()))
                    assertTrue(
                        "片段" + (idx + 1) + " 出现 " + d.toInt() +
                            " cent 的跳变（" + prev + " -> " + st + " Hz）",
                        d < 400.0,
                    )
                }
                prev = st
            }
        }
    }

    /**
     * 片段里的原始值确实含八度误判——记下来，免得以后有人以为这段数据本来就很干净。
     */
    @Test
    fun realDataDoesContainOctaveErrors() {
        var octaveErrorFrames = 0
        for (seg in listOf(seg1, seg2, seg3, seg4)) {
            var prev = 0f
            for ((hz, cl) in seg) {
                if (prev > 0f && hz > 0f) {
                    val d = abs(PitchMath.centsBetween(hz.toDouble(), prev.toDouble()))
                    if (abs(d - 1200.0) < 250.0) octaveErrorFrames++
                }
                prev = hz
            }
        }
        assertTrue("原始数据里应该含八度误判，实际 " + octaveErrorFrames + " 帧", octaveErrorFrames > 0)
    }
}
