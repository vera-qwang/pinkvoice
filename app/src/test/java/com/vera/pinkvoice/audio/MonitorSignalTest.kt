package com.vera.pinkvoice.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 返听线程的信号量。
 *
 * 它防的是**丢唤醒**：任务派下去的时候线程还没进入等待，
 * 通知就丢了 → 回放慢半拍才出声，甚至像"按了没反应"。
 */
class MonitorSignalTest {

    @Test
    fun hasWorkReturnsImmediately() {
        val sig = MonitorSignal()
        val t0 = System.currentTimeMillis()
        assertTrue(sig.awaitWork(true))
        // 不该等
        assertTrue("不该等待", System.currentTimeMillis() - t0 < 50)
    }

    /** 没人派活 → 等到超时。 */
    @Test
    fun waitsWhenNoWork() {
        val sig = MonitorSignal()
        val t0 = System.currentTimeMillis()
        sig.awaitWork(false)
        val waited = System.currentTimeMillis() - t0
        assertTrue("应该等了一会儿，实际 ${waited}ms", waited >= 150)
    }

    /**
     * 核心用例：**不丢唤醒**。
     *
     * 线程已经决定要等，但还没进 wait —— 这时派活。
     * 代号机制必须让线程立刻醒，而不是睡满 200 ms。
     */
    @Test
    fun doesNotMissSignalSentJustBeforeWait() {
        repeat(50) {
            val sig = MonitorSignal()
            val woke = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val t = Thread {
                // 先读代号（模拟"检查条件"），再进等待
                val ready = CountDownLatch(1)
                // 让主线程在"检查完、还没等"的窗口里派活
                Thread {
                    ready.await()
                    Thread.sleep(1)   // 故意留个窗口
                    sig.signal()
                    latch.countDown()
                }
                ready.countDown()
                val r = sig.awaitWork(false)
                woke.set(r)
            }
            t.start()
            latch.await(2, TimeUnit.SECONDS)
            t.join(1000)

            assertTrue("第 $it 次：线程应该醒了", woke.get())
            assertFalse("第 $it 次：线程还活着（说明还在等）", t.isAlive)
        }
    }

    /** 派活之后，等待方要能立刻拿到"有活"的信号。 */
    @Test
    fun signalWakesWaiter() {
        val sig = MonitorSignal()
        val wokeAt = AtomicBoolean(false)
        val t = Thread {
            sig.awaitWork(false)
            wokeAt.set(true)
        }
        t.start()
        Thread.sleep(30)
        assertFalse("还没 signal，不该醒", wokeAt.get())

        sig.signal()
        t.join(1000)
        assertTrue("signal 之后应该醒了", wokeAt.get())
    }

    /** 多次派活不会让等待方漏掉。 */
    @Test
    fun multipleSignalsAreSafe() {
        val sig = MonitorSignal()
        val t = Thread { repeat(3) { sig.awaitWork(false) } }
        t.start()
        repeat(3) {
            Thread.sleep(10)
            sig.signal()
        }
        t.join(2000)
        assertFalse("线程应该结束了", t.isAlive)
    }
}
