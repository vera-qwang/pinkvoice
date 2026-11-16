package com.vera.pinkvoice.audio

/**
 * 返听线程的"有新活干"信号。
 *
 * ## 为什么不能直接用 `wait()` / `notify()`
 *
 * 最经典的丢唤醒：
 *
 * ```
 * 线程：检查条件 → 没活干 → （准备进入 wait）
 * UI  ：挂上任务 → notify()        ← 此刻还没人在等，通知丢了
 * 线程：wait() → 睡到超时才醒
 * ```
 *
 * 结果就是回放慢了半拍才出声，甚至看起来像"按了没反应"。
 *
 * ## 解法：代号（generation）
 *
 * 等待方在**锁里面**比对代号：
 *
 * ```
 * val gen = generation          // 进锁前读
 * synchronized(lock) {
 *     if (gen == generation) {   // 进锁后比对
 *         lock.wait(200)        // 代号没变 → 真的没活干 → 等
 *     }
 *     // 代号变了 → 有人刚派活 → 不等，直接回去看
 * }
 * ```
 *
 * 派活的一方必须在**同一个锁里面**加代号并通知，
 * 这样"检查条件"和"进入等待"之间插不进别人。
 */
class MonitorSignal {

    private val lock = Object()

    /** 每次派新活就 +1。只在 [lock] 里面改。 */
    private var generation = 0

    /**
     * 等一个新任务。
     *
     * @param hasWork 当前是否已经有活干（调用方自己判断）。
     *   有活干就立刻返回 false，不等。
     * @return true 表示等到（或醒来后）**可能**有新活，调用方要重新检查。
     */
    fun awaitWork(hasWork: Boolean): Boolean {
        if (hasWork) return true
        val gen = generation
        synchronized(lock) {
            // 代号变了 = 期间有人派活 → 别等
            if (gen != generation) return true
            try {
                lock.wait(200)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return true
    }

    /**
     * 派新活：加代号 + 通知。
     *
     * 必须在 [lock] 里面加代号，否则等待方可能读到过期的代号
     * 而误判成"没人派活"，白等一轮。
     */
    fun signal() {
        synchronized(lock) {
            generation++
            lock.notifyAll()
        }
    }
}
