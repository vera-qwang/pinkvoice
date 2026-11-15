package com.vera.pinkvoice.audio

import android.media.AudioRecord
import android.media.AudioTimestamp
import java.lang.reflect.Method

/**
 * 跨版本读取 AudioRecord 的时间戳。
 *
 * 背景：AudioRecord.getTimestamp 在不同 API 级别上签名不同——
 *  - API 24~35：public int getTimestamp(AudioTimestamp)          （单参）
 *  - API 34+ ：public int getTimestamp(AudioTimestamp, int timebase)（双参，timebase 见 AudioTimestamp.TIMEBASE_*）
 * 运行时反射拿到的是设备上的真实类，因此这里按"优先单参、其次双参"的顺序探测，
 * 两种形式都用 CLOCK_MONOTONIC 时间基准，与 System.nanoTime() 对齐。
 */
class TimestampProbe {

    private val oneArg: Method? = runCatching {
        AudioRecord::class.java.getMethod("getTimestamp", AudioTimestamp::class.java)
    }.getOrNull()

    private val twoArg: Method? = runCatching {
        AudioRecord::class.java.getMethod(
            "getTimestamp",
            AudioTimestamp::class.java,
            Int::class.javaPrimitiveType,
        )
    }.getOrNull()

    val available: Boolean get() = oneArg != null || twoArg != null

    /** 读取成功返回 true。 */
    fun read(record: AudioRecord, out: AudioTimestamp): Boolean {
        oneArg?.let { m ->
            val r = runCatching { m.invoke(record, out) as Int }.getOrNull()
            if (r != null) {
                out.nanoTime // 触发一次读，保证字段已填充
                if (r == 0) return true
            }
        }
        twoArg?.let { m ->
            val r = runCatching {
                m.invoke(record, out, AudioTimestamp.TIMEBASE_MONOTONIC) as Int
            }.getOrNull()
            if (r != null && r == 0) return true
        }
        return false
    }
}
