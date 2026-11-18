package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PCM16 软件增益。
 *
 * 返听音量要能到 200%，但硬件音量封顶 1.0，多出来的靠这个函数补。
 * 错了不会崩，只会出来一片噪音——所以必须能单元测试。
 */
class PcmGainTest {

    /** 把一个 short 写成小端 PCM16。 */
    private fun pcm16(samples: IntArray): ByteArray {
        val b = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            b[i * 2] = (samples[i] and 0xFF).toByte()
            b[i * 2 + 1] = ((samples[i] shr 8) and 0xFF).toByte()
        }
        return b
    }

    /** 把小端 PCM16 读回 short。 */
    private fun read(buf: ByteArray): IntArray {
        val out = IntArray(buf.size / 2)
        for (i in out.indices) {
            val u = (buf[i * 2].toInt() and 0xFF) or (buf[i * 2 + 1].toInt() shl 8)
            out[i] = u.toShort().toInt()
        }
        return out
    }

    @Test
    fun doublesAmplitude() {
        val buf = pcm16(intArrayOf(1000, -1000, 500, -500))
        applyPcm16Gain(buf, buf.size, 2f)
        val r = read(buf)
        assertEquals(2000, r[0])
        assertEquals(-2000, r[1])
        assertEquals(1000, r[2])
        assertEquals(-1000, r[3])
    }

    /** 增益 1 应该原样不动。 */
    @Test
    fun unityLeavesUnchanged() {
        val buf = pcm16(intArrayOf(1234, -4321, 0))
        applyPcm16Gain(buf, buf.size, 1f)
        val r = read(buf)
        assertEquals(1234, r[0])
        assertEquals(-4321, r[1])
        assertEquals(0, r[2])
    }

    /** 衰减。 */
    @Test
    fun attenuates() {
        val buf = pcm16(intArrayOf(1000, -1000))
        applyPcm16Gain(buf, buf.size, 0.5f)
        val r = read(buf)
        assertEquals(500, r[0])
        assertEquals(-500, r[1])
    }

    /**
     * 超过满量程要削波（不能回绕成反号的噪声）。
     *
     * 这是最关键的一条：回绕会变成"炸"的噪音，
     * 削波顶多是"破了"，性质完全不同。
     */
    @Test
    fun clipsInsteadOfWrapping() {
        val buf = pcm16(intArrayOf(20000, -20000))
        applyPcm16Gain(buf, buf.size, 2f)
        val r = read(buf)
        assertEquals(32767, r[0])
        assertEquals(-32768, r[1])
        // 关键：正数不能变成负数
        assertTrue("正样本不能翻成负数", r[0] > 0)
        assertTrue("负样本不能翻成正数", r[1] < 0)
    }

    /** 极端输入也不能崩。 */
    @Test
    fun handlesExtremeValues() {
        val buf = pcm16(intArrayOf(32767, -32768, 0))
        applyPcm16Gain(buf, buf.size, 10f)
        val r = read(buf)
        assertEquals(32767, r[0])
        assertEquals(-32768, r[1])
        assertEquals(0, r[2])
    }

    /** 只处理前 n 个字节，剩下的不动。 */
    @Test
    fun respectsLength() {
        val buf = pcm16(intArrayOf(1000, 1000, 1000, 1000))
        applyPcm16Gain(buf, 4, 2f)   // 只处理前两个样本
        val r = read(buf)
        assertEquals(2000, r[0])
        assertEquals(2000, r[1])
        assertEquals(1000, r[2])
        assertEquals(1000, r[3])
    }

    /** 奇数长度（多出一个字节）不能越界。 */
    @Test
    fun handlesOddLength() {
        val buf = pcm16(intArrayOf(1000, 1000))
        applyPcm16Gain(buf, 3, 2f)   // 3 字节：第二个样本只有一个字节
        val r = read(buf)
        assertEquals(2000, r[0])
    }

    /** 空缓冲区不能崩。 */
    @Test
    fun handlesEmptyBuffer() {
        val buf = ByteArray(0)
        applyPcm16Gain(buf, 0, 2f)
        val buf2 = ByteArray(1)
        applyPcm16Gain(buf2, 1, 2f)
    }
}
