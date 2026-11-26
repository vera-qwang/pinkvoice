package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 音名换算。
 *
 * 读数卡上"平均音高"一格要同时给出音名（E3）和频率（220 Hz），
 * 两个必须指的是同一个音——音名就是从那个频率直接换出来的。
 */
class PitchMathTest {

    /** 几个熟悉的音：频率 -> 音名。 */
    @Test
    fun hzToNoteNameMatchesKnownNotes() {
        assertEquals("C3", PitchMath.hzToNoteName(130.81))
        assertEquals("E3", PitchMath.hzToNoteName(164.81))
        assertEquals("F3", PitchMath.hzToNoteName(174.61))
        assertEquals("A3", PitchMath.hzToNoteName(220.00))
        assertEquals("C4", PitchMath.hzToNoteName(261.63))
        assertEquals("A4", PitchMath.hzToNoteName(440.00))
        assertEquals("C5", PitchMath.hzToNoteName(523.25))
    }

    /** 取最近的半音：落在半音中间的频率归到哪边是定死的。 */
    @Test
    fun hzToNoteNamePicksNearestSemitone() {
        // E3 = 164.81，往下偏 3 Hz 仍在 E3 这一格里（最近的半音是 D♯3 = 155.56）
        assertEquals("E3", PitchMath.hzToNoteName(162.0))
        // E3 / F3 的中点在 169.7 Hz 左右，越过去才变成 F3
        assertEquals("F3", PitchMath.hzToNoteName(170.0))
    }

    /** 升号用 ♯（不是 #），和读数卡上的大音名、坐标轴刻度保持一致。 */
    @Test
    fun sharpNotesUseUnicodeSign() {
        assertEquals("D♯3", PitchMath.hzToNoteName(155.56))
        assertEquals("F♯4", PitchMath.hzToNoteName(369.99))
    }

    /** 音名必须由同一个频率换出来，不能出现"音名和 Hz 对不上"。 */
    @Test
    fun noteNameIsConsistentWithMidiRounding() {
        val samples = listOf(80.0, 110.0, 146.83, 164.81, 196.0, 246.94, 329.63, 440.0, 880.0)
        for (hz in samples) {
            assertEquals(
                "音名应与 MIDI 取整一致（$hz Hz）",
                PitchMath.midiToName(PitchMath.hzToMidiInt(hz)),
                PitchMath.hzToNoteName(hz),
            )
        }
    }
}
