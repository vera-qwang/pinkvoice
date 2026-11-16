package com.vera.pinkvoice.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回放缓冲。
 *
 * 这段逻辑最容易出竞态（快照什么时候挂上、读到哪了），
 * 所以单独抽出来用单元测试覆盖。
 */
class ReplayBufferTest {

    private fun seq(from: Int, count: Int) = ByteArray(count) { (from + it).toByte() }

    @Test
    fun beginHoldsData() {
        val rb = ReplayBuffer(1000)
        assertFalse(rb.active)
        val n = rb.begin(seq(0, 10))
        assertEquals(10, n)
        assertTrue(rb.active)
        assertEquals(10, rb.remaining)
    }

    /** 空数据不开始播放。 */
    @Test
    fun beginRejectsEmpty() {
        val rb = ReplayBuffer(1000)
        assertEquals(0, rb.begin(ByteArray(0)))
        assertFalse(rb.active)
    }

    /** 超过上限的数据不播。 */
    @Test
    fun beginRejectsOversized() {
        val rb = ReplayBuffer(100)
        assertEquals(0, rb.begin(ByteArray(101)))
        assertFalse(rb.active)
    }

    /** 读出来的数据和放进去的一致，顺序不能乱。 */
    @Test
    fun readReturnsDataInOrder() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 20))
        val dst = ByteArray(20)
        val n = rb.read(dst)
        assertEquals(20, n)
        for (i in 0 until 20) {
            assertEquals("第 $i 字节", (i).toByte(), dst[i])
        }
    }

    /** 分多次读，拼接起来还是完整的。 */
    @Test
    fun readInChunksConcatenates() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 25))
        val out = ByteArray(25)
        var off = 0
        while (off < 25) {
            val chunk = ByteArray(7)
            val n = rb.read(chunk)
            assertTrue("应该还能读出数据", n > 0)
            System.arraycopy(chunk, 0, out, off, n)
            off += n
        }
        for (i in 0 until 25) {
            assertEquals("第 $i 字节", (i).toByte(), out[i])
        }
    }

    /**
     * 读完最后一个字节后，再读必须返回 0（表示播完了）。
     *
     * 这是返听线程判断"回放结束"的唯一依据——
     * 返回负数或者抛异常都会让线程行为跑偏。
     */
    @Test
    fun readReturnsZeroWhenExhausted() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 10))
        val dst = ByteArray(10)
        assertEquals(10, rb.read(dst))
        assertEquals(0, rb.read(dst))
        assertEquals(0, rb.read(dst))
    }

    /** 剩余字节数随读取递减。 */
    @Test
    fun remainingDecreases() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 10))
        assertEquals(10, rb.remaining)
        rb.read(ByteArray(4))
        assertEquals(6, rb.remaining)
        rb.read(ByteArray(6))
        assertEquals(0, rb.remaining)
    }

    /** finish 之后就没有数据了。 */
    @Test
    fun finishClears() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 10))
        rb.finish()
        assertFalse(rb.active)
        assertEquals(0, rb.remaining)
        assertEquals(0, rb.read(ByteArray(10)))
    }

    /** 没有开始回放时读，返回 0 而不是崩。 */
    @Test
    fun readWithoutBegin() {
        val rb = ReplayBuffer(1000)
        assertEquals(0, rb.read(ByteArray(10)))
        assertEquals(0, rb.remaining)
    }

    /** 重新开始回放会覆盖上一段。 */
    @Test
    fun beginResetsPosition() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 10))
        rb.read(ByteArray(5))
        // 再来一段新的
        rb.begin(seq(100, 8))
        assertEquals(8, rb.remaining)
        val dst = ByteArray(8)
        assertEquals(8, rb.read(dst))
        assertEquals(100.toByte(), dst[0])
        assertEquals(107.toByte(), dst[7])
    }

    /** 目标缓冲比剩余数据小，只取一部分，剩下的还在。 */
    @Test
    fun readRespectsDestinationSize() {
        val rb = ReplayBuffer(1000)
        rb.begin(seq(0, 10))
        val dst = ByteArray(4)
        assertEquals(4, rb.read(dst))
        assertEquals(6, rb.remaining)
    }
}
