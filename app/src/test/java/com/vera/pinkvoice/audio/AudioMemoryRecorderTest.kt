package com.vera.pinkvoice.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内存录音缓冲。
 *
 * 它是"回放"功能的地基：录进去的音频**只在内存里**，
 * 关掉 App 就没有了——这是这个功能最重要的性质。
 */
class AudioMemoryRecorderTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** 写入一段递增的字节，方便验证顺序和位置。 */
    private fun writeSeq(rec: AudioMemoryRecorder, from: Int, count: Int) {
        val buf = ByteArray(count) { (from + it).toByte() }
        rec.write(buf, 0, count)
    }

    @Test
    fun writesAndSnapshots() {
        val rec = AudioMemoryRecorder(100)
        writeSeq(rec, 0, 40)
        assertEquals(40, rec.availableBytes())

        val dst = ByteArray(40)
        val n = rec.snapshotRecent(dst, 0, 40)
        assertEquals(40, n)
        // 内容要和写进去的顺序一致
        for (i in 0 until 40) {
            assertEquals("第 $i 字节", (i).toByte(), dst[i])
        }
    }

    /** 缓冲不足时，拷贝现有的全部。 */
    @Test
    fun snapshotReturnsWhatIsAvailable() {
        val rec = AudioMemoryRecorder(100)
        writeSeq(rec, 0, 30)
        val dst = ByteArray(100)
        val n = rec.snapshotRecent(dst, 0, 100)
        assertEquals(30, n)
        assertEquals(30, rec.availableBytes())
    }

    /**
     * 写满之后，最旧的数据被丢掉——留下的应该是**最新**的那些。
     */
    @Test
    fun dropsOldestWhenFull() {
        val rec = AudioMemoryRecorder(100)
        writeSeq(rec, 0, 80)
        writeSeq(rec, 80, 40)   // 总共 120 > 100

        assertEquals(100, rec.availableBytes())
        assertEquals(20L, rec.droppedBytes)

        val dst = ByteArray(100)
        val n = rec.snapshotRecent(dst, 0, 100)
        assertEquals(100, n)
        // 最旧的 20 字节（0..19）应该已经被覆盖成 100..119
        assertEquals(20.toByte(), dst[0])
        assertEquals(119.toByte(), dst[99])
    }

    /** 快照要跨环形回绕边界取数据。 */
    @Test
    fun snapshotWrapsAroundRing() {
        val rec = AudioMemoryRecorder(100)
        writeSeq(rec, 0, 90)
        writeSeq(rec, 90, 30)   // 回绕

        val dst = ByteArray(100)
        rec.snapshotRecent(dst, 0, 100)
        for (i in 0 until 100) {
            assertEquals("第 $i 字节（回绕后）", (20 + i).toByte(), dst[i])
        }
    }

    /** 只要最近 N 秒，前面的不要。 */
    @Test
    fun snapshotTakesMostRecent() {
        val rec = AudioMemoryRecorder(200)
        writeSeq(rec, 0, 200)
        // 再写 50 字节，总共 250，最旧的 50 已被覆盖
        writeSeq(rec, 200, 50)

        val dst = ByteArray(50)
        val n = rec.snapshotRecent(dst, 0, 50)
        assertEquals(50, n)
        for (i in 0 until 50) {
            assertEquals("第 $i 字节", (200 + i).toByte(), dst[i])
        }
    }

    /** 清空后什么都没有了。 */
    @Test
    fun clearEmpties() {
        val rec = AudioMemoryRecorder(100)
        writeSeq(rec, 0, 50)
        rec.clear()
        assertEquals(0, rec.availableBytes())
        assertEquals(0L, rec.droppedBytes)
        assertEquals(0, rec.snapshotRecent(ByteArray(10), 0, 10))
    }

    /** 空缓冲取快照不能崩。 */
    @Test
    fun snapshotOnEmptyBuffer() {
        val rec = AudioMemoryRecorder(100)
        assertEquals(0, rec.snapshotRecent(ByteArray(10), 0, 10))
        assertEquals(0, rec.availableBytes())
    }

    /**
     * 快照的内容必须是**连续**的一段——不能因为环形回绕而错位。
     *
     * 这是回放功能的核心保证：播出来的音频必须连贯，不能有断裂或错位。
     */
    @Test
    fun snapshotIsContiguous() {
        val rec = AudioMemoryRecorder(64)
        // 写 200 字节，让指针转了好几圈
        writeSeq(rec, 0, 200)

        val dst = ByteArray(64)
        val n = rec.snapshotRecent(dst, 0, 64)
        assertEquals(64, n)
        // 应该是连续的 136..199
        for (i in 0 until 64) {
            assertEquals("第 $i 字节", (136 + i).toByte(), dst[i])
        }
    }

    /** 写满一圈之后数据仍然正确（环形缓冲的经典陷阱）。 */
    @Test
    fun dataSurvivesMultipleWraps() {
        val rec = AudioMemoryRecorder(32)
        writeSeq(rec, 0, 32)
        writeSeq(rec, 32, 32)
        writeSeq(rec, 64, 32)

        val dst = ByteArray(32)
        rec.snapshotRecent(dst, 0, 32)
        for (i in 0 until 32) {
            assertEquals("第 $i 字节", (64 + i).toByte(), dst[i])
        }
    }

    /** 目标缓冲比可用数据小的时候，只拷尾部。 */
    @Test
    fun snapshotIntoSmallerBuffer() {
        val rec = AudioMemoryRecorder(100)
        writeSeq(rec, 0, 100)
        val dst = ByteArray(10)
        val n = rec.snapshotRecent(dst, 0, 10)
        assertEquals(10, n)
        for (i in 0 until 10) {
            assertEquals("第 $i 字节", (90 + i).toByte(), dst[i])
        }
    }
}
