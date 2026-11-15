package com.vera.pinkvoice.ui

import com.vera.pinkvoice.audio.PitchMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 男女声音域的分界。
 *
 * 分界点定在 **E3**，而不是随手取个整数——它同时是：
 * - 音域语义的分界（男声区到此为止）
 * - 色带的分界（画布上的换色位置）
 *
 * 这两条必须用**同一个常量**，否则标签和色带会错位。
 */
class BandBoundaryTest {

    /** 分界线必须正好落在 E3 这个整音上。 */
    @Test
    fun boundaryIsExactlyE3() {
        assertEquals(
            "分界应该是 E3 = 164.81 Hz",
            164.81f,
            BandBoundaryHz,
            0.01f,
        )
        // E3 的 MIDI 号是 52
        assertEquals(
            "E3 的 MIDI 号",
            52,
            PitchMath.hzToMidiInt(BandBoundaryHz.toDouble()),
        )
        // 顺带确认落在整音上：不能是 D#3 和 E3 之间的某个位置
        assertEquals(
            "分界线应该正好落在 E3，不能在两个半音之间",
            0.5f,
            PitchMath.hzToMidi(BandBoundaryHz.toDouble()).toFloat() - 51.5f,
            0.01f,
        )
    }

    /** 分界点必须在男声区和女声区的交界上，两边都不能错开。 */
    @Test
    fun boundaryMatchesRangeEnds() {
        assertEquals("男声区上界应等于分界线", BandBoundaryHz, MaleRange.endInclusive, 0.01f)
        assertEquals("女声区下界应等于分界线", BandBoundaryHz, FemaleRange.start, 0.01f)
    }

    /** 两个音域不能重叠——否则一个音名会被染成两个颜色。 */
    @Test
    fun rangesDoNotOverlapExceptAtBoundary() {
        assertTrue(
            "男声区上界 ${MaleRange.endInclusive} 应 <= 女声区下界 ${FemaleRange.start}",
            MaleRange.endInclusive <= FemaleRange.start,
        )
        assertTrue("男声区下限应该低于女声区下限", MaleRange.start < FemaleRange.start)
    }

    /** 女声区不能比男声区还宽——那说明写反了。 */
    @Test
    fun maleRangeIsLowerThanFemaleRange() {
        assertTrue("男声区上限应低于女声区上限", MaleRange.endInclusive < FemaleRange.endInclusive)
        assertTrue("男声区下限应低于女声区下限", MaleRange.start < FemaleRange.start)
    }
}
