package com.vera.pinkvoice.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** 音高 <-> MIDI / 音名 的换算工具。 */
object PitchMath {
    const val A4_HZ = 440.0
    const val A4_MIDI = 69

    private val noteNames = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    /** Hz -> 连续 MIDI 号（可带小数）。 */
    fun hzToMidi(hz: Double): Double = A4_MIDI + 12.0 * ln(hz / A4_HZ) / ln(2.0)

    /** Hz -> 最近的整数 MIDI 号。 */
    fun hzToMidiInt(hz: Double): Int = hzToMidi(hz).roundToInt()

    /** 整数 MIDI 号 -> 音名，如 60 -> "C4"。 */
    fun midiToName(midi: Int): String {
        val name = noteNames[((midi % 12) + 12) % 12]
        return "$name${midi / 12 - 1}"
    }

    /**
     * Hz -> 音名，取最近的半音，如 164.81 -> "E3"。
     *
     * 音名是从频率直接换算的，不做平均：
     * 平均音高换出音名时必须和它显示的 Hz 对得上，
     * 否则"平均音高 E3"和"平均音高 220 Hz"会指到两个不同的音。
     */
    fun hzToNoteName(hz: Double): String = midiToName(hzToMidiInt(hz))

    /**
     * 相对最近半音的偏移，单位 cent。
     * 正值 = 偏高，负值 = 偏低。
     */
    fun centsOffNearest(hz: Double): Double {
        val midi = hzToMidi(hz)
        return (midi - midi.roundToInt()) * 100.0
    }

    /** 目标半音的精确频率。 */
    fun midiToHz(midi: Int): Double = A4_HZ * 2.0.pow((midi - A4_MIDI) / 12.0)

    /** 判断两个频率是否在容差内（cent）。 */
    fun isWithinCents(hz: Double, targetHz: Double, toleranceCents: Double): Boolean =
        abs(centsBetween(hz, targetHz)) <= toleranceCents

    /** hz 相对 targetHz 的 cent 差。 */
    fun centsBetween(hz: Double, targetHz: Double): Double =
        1200.0 * ln(hz / targetHz) / ln(2.0)
}
