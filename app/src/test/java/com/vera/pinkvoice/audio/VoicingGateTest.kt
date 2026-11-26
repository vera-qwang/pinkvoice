package com.vera.pinkvoice.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **不是语音就不该产出共振峰。**
 *
 * 这是「F1/F2 乱跳」最隐蔽的原因：以前每一帧都跑 LPC，
 * 环境噪音也被拟合成「看起来很合理」的共振峰
 * （实测静音段能算出 297/1611、455/1318 这种值），
 * 用户没说话时界面照样在跳数。
 *
 * 用真人录音验证：噪音段（Praat 时间段之外）不应该有输出。
 */
class VoicingGateTest {

    private fun locate(name: String): File? {
        var d: File? = File(".").absoluteFile
        repeat(4) {
            val cur = d ?: return null
            val f = File(cur, name)
            if (f.exists()) return f
            d = cur.parentFile
        }
        return null
    }

    private fun readWav(f: File): Pair<FloatArray, Int> {
        val bytes = f.readBytes()
        val rate = (bytes[24].toInt() and 0xFF) or ((bytes[25].toInt() and 0xFF) shl 8) or
            ((bytes[26].toInt() and 0xFF) shl 16) or ((bytes[27].toInt() and 0xFF) shl 24)
        var pos = 12
        var off = -1
        var len = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            if (id == "data") { off = pos + 8; len = size; break }
            pos += 8 + size + (size and 1)
        }
        val n = minOf(len, bytes.size - off) / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[off + i * 2].toInt() and 0xFF
            val hi = bytes[off + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out to rate
    }

    private fun decimate3(x: FloatArray): FloatArray {
        val taps = 127
        val fc = 6800.0 / 48000.0
        val h = DoubleArray(taps)
        val mid = (taps - 1) / 2.0
        var sum = 0.0
        for (i in 0 until taps) {
            val t = i - mid
            val sinc = if (t == 0.0) 2 * fc else kotlin.math.sin(2 * Math.PI * fc * t) / (Math.PI * t)
            val w = 0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / (taps - 1))
            h[i] = sinc * w
            sum += h[i]
        }
        for (i in 0 until taps) h[i] /= sum
        val out = FloatArray(x.size / 3 + 1)
        var k = 0
        var i = 0
        while (i < x.size) {
            var acc = 0.0
            for (q in 0 until taps) {
                val idx = i - q
                if (idx < 0) break
                acc += h[q] * x[idx]
            }
            if (i % 3 == 0) out[k++] = acc.toFloat()
            i++
        }
        return out.copyOf(k)
    }


}
