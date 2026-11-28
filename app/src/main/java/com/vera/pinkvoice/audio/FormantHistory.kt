package com.vera.pinkvoice.audio

/**
 * 共振峰历史环形缓冲：引擎线程写入，UI 线程读取。
 *
 * 和 [PitchHistory] 是同一套模式，但简单得多 ——
 * F1/F2 不需要音高那种「跟踪器 + 离群点剔除」：
 * 分析器吐出来的已经是平滑后的值。
 *
 * 每条记录带**采集时刻**的时间戳，UI 按时间窗口裁剪，
 * 这样「最近 8 秒」的轨迹不会因为分块大小变化而长度忽长忽短。
 */
class FormantHistory(
    private val capacity: Int = 1024,
) {
    private val times = LongArray(capacity)
    private val f1 = FloatArray(capacity)
    private val f2 = FloatArray(capacity)
    private var head = 0
    private var size = 0

    /** 最新一条记录的时刻（纳秒）。 */
    var latestTimeNs: Long = 0L
        private set

    @Synchronized
    fun add(timeNs: Long, f1Value: Float, f2Value: Float) {
        times[head] = timeNs
        f1[head] = f1Value
        f2[head] = f2Value
        head = if (head + 1 == capacity) 0 else head + 1
        if (size < capacity) size++
        if (timeNs > latestTimeNs) latestTimeNs = timeNs
    }

    /**
     * 取出 [tMinNs, tMaxNs] 区间内的记录，按时间升序写入输出数组。
     *
     * @return 有效记录数
     */
    @Synchronized
    fun copyRange(
        tMinNs: Long,
        tMaxNs: Long,
        outTime: LongArray,
        outF1: FloatArray,
        outF2: FloatArray,
    ): Int {
        var n = 0
        var visited = 0
        var idx = if (size == 0) -1 else {
            var i = if (size < capacity) 0 else head
            repeat(size - 1) { i = if (i + 1 == capacity) 0 else i + 1 }
            i
        }
        // 从最新一条往回扫，遇到早于窗口起点的就停。
        while (idx >= 0 && visited < size) {
            visited++
            val t = times[idx]
            if (t < tMinNs) break
            if (t <= tMaxNs && n < outTime.size) {
                outTime[n] = t
                outF1[n] = f1[idx]
                outF2[n] = f2[idx]
                n++
            }
            idx = if (idx == 0) {
                if (size < capacity) -1 else capacity - 1
            } else {
                idx - 1
            }
            if (n >= outTime.size) break
        }
        // 反向扫出来的是降序，翻回来
        for (i in 0 until n / 2) {
            val j = n - 1 - i
            val tt = outTime[i]; outTime[i] = outTime[j]; outTime[j] = tt
            val a = outF1[i]; outF1[i] = outF1[j]; outF1[j] = a
            val b = outF2[i]; outF2[i] = outF2[j]; outF2[j] = b
        }
        return n
    }

    @Synchronized
    fun clear() {
        head = 0
        size = 0
        latestTimeNs = 0L
    }
}
