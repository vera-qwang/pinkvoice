package com.vera.pinkvoice.audio

/**
 * 内存录音缓冲：只留最近一段时间的音频，**从不落盘**。
 *
 * 用法：
 * - 采集线程持续 [write]
 * - 用户按下"回放"时，[snapshotRecent] 把最近 N 秒拷出来
 * - 拷出来的数据喂给 AudioTrack 播
 *
 * ## 为什么要额外拷一份快照
 *
 * 回放期间采集线程还在写（用户可能一边回放一边唱），
 * 而环形缓冲写满后**会覆盖最旧的数据**。
 * 如果回放指针直接在环形缓冲上走，边写边读会撞上：
 * 正在播的那段被写指针覆盖掉，播出来的是错乱的。
 *
 * 拷一份快照就没这个问题——快照是固定的，播完为止。
 * 代价是双份内存（30 秒 × 48 kHz × 2 字节 ≈ 2.9 MB 一份），
 * 这个量级对手机来说不算什么。
 *
 * ## 线程安全
 *
 * [write] 在采集线程，[snapshotRecent] 在 UI 线程，所以加锁。
 * 快照的拷贝是原子的：要么拿到完整的 N 秒，要么拿到现有的全部。
 */
class AudioMemoryRecorder(private val capacityBytes: Int) {

    private val buf = ByteArray(capacityBytes)

    /** 下一个写入位置（环形）。 */
    private var writePos = 0

    /** 累计写入的字节数（逻辑位置，不受覆盖影响）。 */
    private var totalWritten = 0L

    /** 因为装不下而丢弃的字节数。 */
    var droppedBytes: Long = 0L
        private set

    /**
     * 写入 [len] 字节。
     *
     * 装不下的部分丢最旧的——和 [PcmRingBuffer] 一个策略：
     * 新鲜的数据比旧的数据有用。
     *
     * @return 实际写进缓冲的字节数。
     */
    @Synchronized
    fun write(src: ByteArray, off: Int, len: Int): Int {
        var srcOff = off
        var remaining = len
        while (remaining > 0) {
            val n = minOf(remaining, capacityBytes - writePos)
            System.arraycopy(src, srcOff, buf, writePos, n)
            writePos = (writePos + n) % capacityBytes
            srcOff += n
            remaining -= n
            totalWritten += n
        }
        // 累计写入超过容量时，多出来的部分等于被覆盖掉的旧数据。
        val capacityLost = totalWritten - capacityBytes
        if (capacityLost > 0) droppedBytes = capacityLost
        return len
    }

    /**
     * 把最近 [bytes] 字节拷进 [dst]。
     *
     * 缓冲里不足 [bytes] 时，拷现有的全部（开头留空）。
     *
     * @return 实际拷出的字节数。
     */
    @Synchronized
    fun snapshotRecent(dst: ByteArray, dstOff: Int, bytes: Int): Int {
        val available = minOf(totalWritten, capacityBytes.toLong()).toInt()
        val n = minOf(bytes, available)
        if (n <= 0) return 0
        // 最近 n 字节的逻辑起点
        val start = totalWritten - n
        // 环形位置
        var src = (start % capacityBytes).toInt()
        var copied = 0
        while (copied < n) {
            val chunk = minOf(n - copied, capacityBytes - src)
            System.arraycopy(buf, src, dst, dstOff + copied, chunk)
            src = (src + chunk) % capacityBytes
            copied += chunk
        }
        return n
    }

    /** 当前缓冲里有多少字节可回放。 */
    @Synchronized
    fun availableBytes(): Int =
        minOf(totalWritten, capacityBytes.toLong()).toInt()

    /** 清空。 */
    @Synchronized
    fun clear() {
        writePos = 0
        totalWritten = 0
        droppedBytes = 0
    }
}
