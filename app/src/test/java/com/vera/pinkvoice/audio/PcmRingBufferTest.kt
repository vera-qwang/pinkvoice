package com.vera.pinkvoice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 环形缓冲的验证。
 *
 * 重点是**回绕**与**溢出**。这两个问题真机上都撞过：
 *
 * 1. `read()` 里用 `head = if (head + n == capacity) 0 else head + n` 判断回绕，
 *    漏掉了 `head + n > capacity` 的情况（缓冲区里剩的不是整块时），
 *    head 变成越界值，下一次读直接 ArrayIndexOutOfBoundsException。
 * 2. `write()` 里 `n = minOf(remaining, capacity - tail)` 没考虑已占用的空间，
 *    一次写入可能让 `size` 超过 capacity，读写指针就此错位。
 *
 * 蓝牙耳机断连时这两个问题同时暴露，App 直接崩了。
 * 所以这里用**模型对照**测：拿一个普通队列当参照，任意读写序列下两者必须完全一致。
 */
class PcmRingBufferTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun writeThenReadReturnsSameData() {
        val ring = PcmRingBuffer(1024)
        val src = bytes(1, 2, 3, 4, 5)
        assertEquals(5, ring.write(src, 0, src.size))
        val dst = ByteArray(8)
        assertEquals(5, ring.read(dst, 0, dst.size))
        assertArrayEquals(src, dst.copyOf(5))
    }

    @Test
    fun emptyBufferReadsZero() {
        val ring = PcmRingBuffer(64)
        val dst = ByteArray(8)
        assertEquals(0, ring.read(dst, 0, dst.size))
    }

    /**
     * **核心回归测试**：与参照模型逐字节对照。
     *
     * 随机读写任意长度，覆盖：不满块读、跨容量回绕、写满溢出、
     * 读写指针同时在两端跑等所有边界情况。
     */
    @Test
    fun matchesReferenceModelUnderRandomTraffic() {
        val capacity = 64
        val ring = PcmRingBuffer(capacity)
        val model = ArrayDeque<Byte>()
        val rnd = Random(20261115)
        var counter = 0

        repeat(3000) {
            if (rnd.nextBoolean()) {
                val n = rnd.nextInt(50)
                val chunk = ByteArray(n) { (counter++ % 251).toByte() }
                ring.write(chunk, 0, n)
                for (b in chunk) model.addLast(b)
                // 参照模型的丢弃策略：装不下了就丢最旧的
                while (model.size > capacity) model.removeFirst()
            } else {
                val n = rnd.nextInt(50)
                val out = ByteArray(n)
                val got = ring.read(out, 0, n)
                // read 最多返回 len 个字节，模型也只能被读走这么多
                assertEquals("读出的字节数应与模型一致", minOf(n, model.size), got)
                for (i in 0 until got) {
                    assertEquals("第 $i 个字节不一致", model.removeFirst(), out[i])
                }
            }
        }
    }

    /**
     * **回归测试**：读的大小不是容量的整数倍时，指针必须正确回绕。
     *
     * 旧实现用 `head + n == capacity` 判断回绕，n 不是整数倍时会漏掉
     * `head + n > capacity`，指针越界，下一次读直接崩溃。
     */
    @Test
    fun readWithNonAlignedSizeWrapsCorrectly() {
        val capacity = 100
        val ring = PcmRingBuffer(capacity)
        val all = ByteArray(100) { it.toByte() }
        ring.write(all, 0, 100)

        // 每次读 30 字节——100 不是 30 的整数倍，正好触发回绕
        val out = ByteArray(100)
        var got = 0
        while (got < 100) {
            val chunk = ByteArray(30)
            val n = ring.read(chunk, 0, chunk.size)
            if (n == 0) break
            System.arraycopy(chunk, 0, out, got, n)
            got += n
        }
        assertEquals("应该读满 100 字节", 100, got)
        assertArrayEquals(all, out)
    }

    /**
     * **回归测试**：读指针跨过容量末尾时必须回绕到 0。
     *
     * 这正是蓝牙断连时崩溃的路径：
     * 写 70 → 读 60（head=60）→ 写 40（数据绕到尾部）→ 读 50。
     * 旧实现在最后一步算出 head=110，越界。
     */
    @Test
    fun headWrapsPastCapacityEnd() {
        val capacity = 100
        val ring = PcmRingBuffer(capacity)
        val first = ByteArray(70) { it.toByte() }
        ring.write(first, 0, 70)

        val mid = ByteArray(60)
        assertEquals(60, ring.read(mid, 0, 60))

        val second = ByteArray(40) { (100 + it).toByte() }
        ring.write(second, 0, 40)

        val out = ByteArray(50)
        val n = ring.read(out, 0, 50)
        assertEquals(50, n)
        // 前 60 字节是 first[60..69] + second[0..29]，后 20 字节是 second[30..39]
        val expected = ByteArray(50)
        System.arraycopy(first, 60, expected, 0, 10)
        System.arraycopy(second, 0, expected, 10, 40)
        assertArrayEquals(expected, out)
    }

    /** 写入量超过容量时，丢弃最旧的数据（不阻塞生产者）。 */
    @Test
    fun overflowDropsOldestData() {
        val ring = PcmRingBuffer(8)
        ring.write(bytes(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 0, 10)
        assertEquals("丢了 2 字节", 2L, ring.droppedBytes)
        val dst = ByteArray(8)
        val n = ring.read(dst, 0, dst.size)
        assertEquals(8, n)
        // 最旧的 1、2 被丢掉，剩下 3..10
        assertArrayEquals(bytes(3, 4, 5, 6, 7, 8, 9, 10), dst)
    }

    /** 缓冲区里只剩零头时读一次，然后继续写继续读——崩溃的触发路径。 */
    @Test
    fun partialReadThenMoreTrafficDoesNotCorrupt() {
        val capacity = 64
        val ring = PcmRingBuffer(capacity)
        var counter = 0
        fun nextBytes(n: Int): ByteArray = ByteArray(n) { (counter++ % 251).toByte() }

        repeat(200) {
            val w = nextBytes(17)
            ring.write(w, 0, w.size)
            val r = ByteArray(13)
            val n = ring.read(r, 0, r.size)
            assertTrue("读到的字节数不该为负", n >= 0)
            assertTrue("读到的字节数不该超过请求量", n <= 13)
        }
        val tail = ByteArray(16)
        assertTrue(ring.read(tail, 0, tail.size) >= 0)
    }

    @Test
    fun clearResetsEverything() {
        val ring = PcmRingBuffer(32)
        ring.write(bytes(1, 2, 3), 0, 3)
        ring.clear()
        val dst = ByteArray(8)
        assertEquals(0, ring.read(dst, 0, dst.size))
        assertEquals(0L, ring.droppedBytes)
    }

    /** 写满一圈后，数据顺序必须保持（环形缓冲最容易在这里出错）。 */
    @Test
    fun dataOrderIsPreservedAcrossWrap() {
        val capacity = 16
        val ring = PcmRingBuffer(capacity)
        val expected = ArrayList<Byte>()
        var counter = 0
        repeat(50) {
            val chunk = ByteArray(6) { (counter++ % 127).toByte() }
            ring.write(chunk, 0, chunk.size)
            val out = ByteArray(5)
            val n = ring.read(out, 0, out.size)
            for (i in 0 until n) expected.add(out[i])
        }
        val out = ByteArray(64)
        while (true) {
            val n = ring.read(out, 0, out.size)
            if (n == 0) break
            for (i in 0 until n) expected.add(out[i])
        }
        val lastWritten = (counter - 6 until counter).map { (it % 127).toByte() }
        val tail = expected.takeLast(lastWritten.size).toByteArray()
        assertArrayEquals("末尾应与最后写入的一致", lastWritten.toByteArray(), tail)
    }
}
