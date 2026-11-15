package com.vera.pinkvoice.audio

/**
 * 单生产者单消费者的字节环形缓冲区：采集线程写入，返听线程读出。
 *
 * 关键点：写满时丢弃最旧数据而不是阻塞采集线程——采集一旦阻塞，
 * 麦克风缓冲就会溢出（丢帧 + 延迟跳变），对实时音高分析是致命的。
 *
 * ## 两个真机上踩过的坑
 *
 * 1. **指针必须按取模回绕**，不能用"恰好等于 capacity"判断。
 *    读的大小不一定是 capacity 的整数倍（缓冲区里只剩零头时），
 *    `head + n == capacity` 会漏掉 `head + n > capacity` 的情况，
 *    指针变成越界值，下一次读直接 ArrayIndexOutOfBoundsException。
 *
 * 2. **size 不能超过 capacity**。写入量要算上已占用的空间，
 *    否则读写指针就此错位，size 会一路涨过 capacity。
 *
 * 蓝牙耳机断连时这两个问题同时暴露，App 直接崩了。
 */
class PcmRingBuffer(private val capacity: Int) {
    private val buf = ByteArray(capacity)
    private var head = 0
    private var tail = 0
    private var size = 0

    var droppedBytes: Long = 0L
        private set

    /**
     * 写入 [len] 字节。
     *
     * 语义：缓冲最终装的是「旧内容 + 新数据」里**最新的** [capacity] 个字节，
     * 装不下的部分从最旧的开始丢。丢的是旧数据优先——新数据更新鲜。
     *
     * @return 实际写进缓冲的字节数。
     */
    fun write(src: ByteArray, off: Int, len: Int): Int {
        var srcOff = off
        var remaining = len

        // 第一步：算出要丢多少。
        //
        // 设旧内容 O（size 字节）+ 新数据 N（len 字节）= C，
        // 最终要装的是 C 的最后 capacity 个字节。
        // 需要丢弃的量 = |C| - capacity = size + len - capacity。
        val excess = size + remaining - capacity
        if (excess > 0) {
            // 先丢旧数据
            val dropOld = minOf(excess, size)
            head = (head + dropOld) % capacity
            size -= dropOld
            droppedBytes += dropOld
            // 还差的部分只能从新数据里丢（跳过开头那几）
            val skipNew = excess - dropOld
            if (skipNew > 0) {
                srcOff += skipNew
                remaining -= skipNew
                droppedBytes += skipNew
            }
        }

        // 第二步：现在 size + remaining <= capacity，直接写。
        var written = 0
        while (remaining > 0) {
            val n = minOf(remaining, capacity - tail)
            System.arraycopy(src, srcOff, buf, tail, n)
            tail = (tail + n) % capacity
            size += n
            srcOff += n
            remaining -= n
            written += n
        }
        return written
    }

    /**
     * 读出最多 [len] 字节，返回实际读出的字节数。
     */
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        val n = minOf(len, size)
        if (n == 0) return 0
        var copied = 0
        var src = head
        while (copied < n) {
            val chunk = minOf(n - copied, capacity - src)
            System.arraycopy(buf, src, dst, off + copied, chunk)
            src = (src + chunk) % capacity
            copied += chunk
        }
        // 取模回绕，不能写成"恰好等于 capacity"——见类注释。
        head = (head + n) % capacity
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
