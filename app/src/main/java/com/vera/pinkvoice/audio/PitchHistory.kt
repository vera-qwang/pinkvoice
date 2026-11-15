package com.vera.pinkvoice.audio

/**
 * 音高历史环形缓冲：引擎线程写入，UI 线程读取。
 *
 * 每条记录带"采集时刻"的时间戳，这样 UI 才能按时间窗口裁剪，
 * 并按输出延迟做视觉对齐（蓝牙场景下曲线要滞后于检测结果）。
 *
 * 写入时先过一遍 [PitchTracker]：真机实测底噪会让 YIN 冒出单帧伪周期，
 * 跟踪器能把这种离群点挡在曲线之外。
 */
class PitchHistory(
    private val capacity: Int = 4096,
    private val tracker: PitchTracker = PitchTracker(),
) {
    private val times = LongArray(capacity)
    private val hz = FloatArray(capacity)
    private val rawHz = FloatArray(capacity)
    private val clarity = FloatArray(capacity)
    private val rms = FloatArray(capacity)
    private var head = 0
    private var size = 0

    var latestTimeNs: Long = 0L
        private set

    @Synchronized
    fun add(timeNs: Long, frequency: Float, cl: Float, level: Float) {
        times[head] = timeNs
        hz[head] = tracker.accept(frequency, cl)
        rawHz[head] = frequency
        clarity[head] = cl
        rms[head] = level
        head = if (head + 1 == capacity) 0 else head + 1
        if (size < capacity) size++
        if (timeNs > latestTimeNs) latestTimeNs = timeNs
    }

    /**
     * 取出 [tMinNs, tMaxNs] 区间内的记录，按时间升序写入输出数组。
     * @return 有效记录数
     */
    @Synchronized
    fun copyRange(
        tMinNs: Long,
        tMaxNs: Long,
        outTime: LongArray,
        outHz: FloatArray,
        outClarity: FloatArray,
        outRms: FloatArray,
    ): Int {
        // 从最新一条往回扫，遇到早于窗口起点的就停。
        // 环内数据按时间递增排列，所以反向扫描可以提前退出；
        // 正向扫全环（最多 4096 条）每帧都是白费——窗口里通常只有 300 多条。
        var n = 0
        var visited = 0
        var idx = if (size == 0) -1 else {
            var i = if (size < capacity) 0 else head
            // 定位到最新一条
            repeat(size - 1) { i = if (i + 1 == capacity) 0 else i + 1 }
            i
        }
        while (idx >= 0 && visited < size) {
            visited++
            val t = times[idx]
            if (t < tMinNs) break
            if (t <= tMaxNs && n < outTime.size) {
                outTime[n] = t
                outHz[n] = hz[idx]
                outClarity[n] = clarity[idx]
                outRms[n] = rms[idx]
                n++
            }
            idx = if (idx == 0) {
                // 已经到环的物理起点，且还没把整圈扫完
                if (size < capacity) -1 else capacity - 1
            } else {
                idx - 1
            }
            if (n >= outTime.size) break
        }
        // 反向扫出来的顺序是"新 → 旧"，绘图需要"旧 → 新"，倒过来。
        if (n > 1) {
            for (i in 0 until n / 2) {
                val j = n - 1 - i
                var tmpL = outTime[i]; outTime[i] = outTime[j]; outTime[j] = tmpL
                var tmpF = outHz[i]; outHz[i] = outHz[j]; outHz[j] = tmpF
                tmpF = outClarity[i]; outClarity[i] = outClarity[j]; outClarity[j] = tmpF
                tmpF = outRms[i]; outRms[i] = outRms[j]; outRms[j] = tmpF
            }
        }
        return n
    }

    /**
     * 仅供测试：按时间升序导出全部记录。
     *
     * 这是 [copyRange] 优化前的朴素实现（正向扫全环），
     * 用来在测试里做等价性对照。
     */
    @Synchronized
    internal fun dump(): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>(size)
        var idx = if (size < capacity) 0 else head
        repeat(size) {
            out += times[idx] to hz[idx]
            idx = if (idx + 1 == capacity) 0 else idx + 1
        }
        return out
    }

    /**
     * 区间内的统计量。
     *
     * @param avgHz 窗口内**有声帧**的音高算术平均值（Hz）；没有有声帧时为 null
     * @param voicedRatio 窗口内有声帧占全部帧的比例（0f..1f）
     * @param avgRms 窗口内全部帧的 RMS 平均值（线性），用于响度显示
     * @param peakRms 窗口内最大的 RMS（线性）
     */
    data class WindowStats(
        val avgHz: Float?,
        val voicedRatio: Float,
        val avgRms: Float,
        val peakRms: Float,
    )

    /**
     * 统计 [tMinNs, tMaxNs] 区间内的音高与响度。
     *
     * 平均音高只算有声帧（clarity ≥ 0.3 且 hz > 0）——把静音帧的 0 Hz
     * 算进去会把平均值拉得很难看。响度则算**全部帧**：
     * 说话时的停顿本来就不发声，响度要反映的是"这段时间声音多大"。
     *
     * 走的是和 [copyRange] 一样的反向扫描：环内按时间递增排列，
     * 从最新一条往回扫，遇到早于窗口起点的就停。
     */
    @Synchronized
    fun statsIn(tMinNs: Long, tMaxNs: Long): WindowStats {
        if (size == 0) return WindowStats(null, 0f, 0f, 0f)

        var idx = if (size < capacity) 0 else head
        repeat(size - 1) { idx = if (idx + 1 == capacity) 0 else idx + 1 }

        var visited = 0
        var total = 0
        var voiced = 0
        var hzSum = 0f
        var rmsSum = 0f
        var peak = 0f

        while (idx >= 0 && visited < size) {
            visited++
            val t = times[idx]
            if (t < tMinNs) break
            if (t <= tMaxNs) {
                total++
                val r = rms[idx]
                rmsSum += r
                if (r > peak) peak = r
                val h = hz[idx]
                if (h > 0f && clarity[idx] >= 0.3f) {
                    voiced++
                    hzSum += h
                }
            }
            idx = if (idx == 0) {
                if (size < capacity) -1 else capacity - 1
            } else {
                idx - 1
            }
        }

        if (total == 0) return WindowStats(null, 0f, 0f, 0f)
        return WindowStats(
            avgHz = if (voiced > 0) hzSum / voiced else null,
            voicedRatio = voiced.toFloat() / total,
            avgRms = rmsSum / total,
            peakRms = peak,
        )
    }

    @Synchronized
    fun clear() {
        head = 0
        size = 0
        latestTimeNs = 0L
        tracker.reset()
    }

    /** 取不晚于 [timeNs] 的最近一条记录（用于"此刻听到的是哪个音"）。 */
    @Synchronized
    fun sampleAt(timeNs: Long): PitchSample? {
        if (size == 0) return null
        var idx = if (head == 0) capacity - 1 else head - 1
        var checked = 0
        while (checked < size) {
            if (times[idx] <= timeNs) {
                return PitchSample(times[idx], hz[idx], clarity[idx], rms[idx])
            }
            idx = if (idx == 0) capacity - 1 else idx - 1
            checked++
        }
        return null
    }

    data class PitchSample(
        val timeNs: Long,
        val hz: Float,
        val clarity: Float,
        val rms: Float,
    )
}
