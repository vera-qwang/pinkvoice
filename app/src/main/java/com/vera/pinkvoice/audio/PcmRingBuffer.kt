package com.vera.pinkvoice.audio

/**
 * 单生产者单消费者的字节环形缓冲区：采集线程写入，返听线程读出。
 *
 * 关键点：写满时丢弃最旧数据而不是阻塞采集线程——采集一旦阻塞，
 * 麦克风缓冲就会溢出（丢帧 + 延迟跳变），对实时音高分析是致命的。
 */
class PcmRingBuffer(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0

    var droppedBytes: Long = 0L
        private set

    fun write(src: ByteArray, off: Int, len: Int): Int {
        var written = 0
        var srcOff = off
        var remaining = len
        while (remaining > 0) {
            if (size == capacity) {
                head = if (head + 1 == capacity) 0 else head + 1
                size--
                droppedBytes++
            }
            val n = minOf(remaining, capacity - tail)
            System.arraycopy(src, srcOff, buf, tail, n)
            tail = if (tail + n == capacity) 0 else tail + n
            size += n
            srcOff += n
            remaining -= n
            written += n
        }
        return written
    }

    fun read(dst: ByteArray, off: Int, len: Int): Int {
        val n = minOf(len, size)
        if (n == 0) return 0
        var copied = 0
        var src = head
        while (copied < n) {
            val chunk = minOf(n - copied, capacity - src)
            System.arraycopy(buf, src, dst, off + copied, chunk)
            src = if (src + chunk == capacity) 0 else src + chunk
            copied += chunk
        }
        head = if (head + n == capacity) 0 else head + n
        size -= n
        return n
    }

    fun clear() {
        head = 0
        tail = 0
        size = 0
        droppedBytes = 0
    }
}
