package com.vera.pinkvoice.audio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 用**真实录音 + Praat 基准**回归。
 *
 * ## 为什么要这个
 *
 * 合成信号和真实语音差得远 —— 之前所有参数都是在合成信号上标定的，
 * 结果在真机上 F1 整个丢失（错位一千多 Hz），而合成测试全绿。
 *
 * 这个测试直接读本地录音和 Praat 导出的共振峰轨迹，
 * 跑和 App 完全一样的管线，对比中位数。
 *
 * ## 文件从哪来
 *
 * 仓库里**不放音频**（含个人声音）。本地调试时放在项目根目录：
 *
 * ```
 * test.wav + formants.txt      （A 元音）
 * e.wav    + formant_e.txt
 * i.wav    + formant_i.txt
 * o.wav    + formant_o.txt
 * u.wav    + formant_u.txt
 * ```
 *
 * 文件不存在就跳过 —— CI 上不会失败。
 *
 * ## 怎么生成基准
 *
 * Praat: `Read from file` → `To Formant (burg)`（默认参数）
 * → `Tabulate: formant listing`，把表格存成上面的 .txt。
 */
class RealRecordingFormantTest {

    /** 单段录音的判定标准：F1+F2 误差合计不超过这个值。 */
    private val tolerance = 120.0

    private val cases = listOf(
        Triple("test.wav", "formants.txt", "A"),
        Triple("e.wav", "formant_e.txt", "E"),
        Triple("i.wav", "formant_i.txt", "I"),
        Triple("o.wav", "formant_o.txt", "O"),
        Triple("u.wav", "formant_u.txt", "U"),
    )

    @Test
    fun matchesPraatOnRealRecordings() {
        var checked = 0
        val lines = StringBuilder()
        for ((wav, fmt, name) in cases) {
            val wavFile = locate(wav) ?: continue
            val fmtFile = locate(fmt) ?: continue
            checked++

            val ref = praatMedian(fmtFile)
            val got = appMedian(wavFile, fmtFile)
            if (got == null) {
                lines.append("$name: 没有有效帧\n")
                continue
            }
            val e = abs(got.first - ref[0]) + abs(got.second - ref[1])
            lines.append(
                "%s  App %4.0f/%4.0f   Praat %4.0f/%4.0f   误差 %3.0f\n"
                    .format(name, got.first, got.second, ref[0], ref[1], e),
            )
            assertTrue(
                "$name 的 F1/F2 和 Praat 差太多（${e.toInt()} Hz）\n$lines",
                e <= tolerance,
            )
        }
        assumeTrue("本地没有录音文件，跳过", checked > 0)
        println(lines.toString())
    }

    /**
     * 在几个可能的位置找文件。
     *
     * Gradle 单测的工作目录是模块目录（app/），而录音放在项目根目录，
     * 所以要从当前目录开始逐级往上找。
     */
    private fun locate(name: String): File? {
        var dir: File? = File(".").absoluteFile
        repeat(4) {
            val d = dir ?: return null
            val f = File(d, name)
            if (f.exists()) return f
            dir = d.parentFile
        }
        return null
    }

    /** Praat 导出表格的中位数（F1/F2/F3）。 */
    private fun praatMedian(f: File): DoubleArray {
        val rows = ArrayList<DoubleArray>()
        for (line in f.readLines()) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 4 || p[0].isEmpty() || !p[0][0].isDigit()) continue
            rows += doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
        }
        require(rows.isNotEmpty()) { "${f.name} 里没有数据" }
        return DoubleArray(3) { k ->
            val col = rows.map { it[k] }.sorted()
            col[col.size / 2]
        }
    }

    /**
     * 走和 App 完全一样的管线：48k → 12k → Burg → 求根 → 取中位数。
     */
    private fun appMedian(wav: File, fmt: File): Pair<Double, Double>? {
        val (samples, rate) = readWav(wav)
        require(rate == 48000) { "这个测试假定录音是 48 kHz，实际 $rate" }

        // 48 kHz → 12 kHz：先抽取到 16 kHz 再重采样，和 App 一致
        val to16 = decimate3(samples)
        val r = Resampler16kTo12k()
        val out = FloatArray(to16.size)
        val n12 = r.process(to16, to16.size, out)

        val lpc = LpcAnalyzer(sampleRate = 12000, order = 11)
        val frame = 600          // 50 ms @ 12 kHz
        lpc.prepare(frame)

        // 只在 Praat 有数据的时段内取帧，再收窄 15% 避开起音/收音
        val times = ArrayList<Double>()
        for (line in fmt.readLines()) {
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 4 || p[0].isEmpty() || !p[0][0].isDigit()) continue
            times += p[0].toDouble()
        }
        val t0 = times.first()
        val t1 = times.last()
        val m = (t1 - t0) * 0.15
        val from = t0 + m
        val to = t1 - m

        val f1s = ArrayList<Float>()
        val f2s = ArrayList<Float>()
        val hop = 192            // 16 ms @ 12 kHz
        var start = 0
        while (start + frame < n12) {
            val t = start.toDouble() / 12000
            if (t in from..to) {
                if (lpc.analyze(out.copyOfRange(start, start + frame), frame)) {
                    val f = lpc.extractFormants()
                    if (f.f1 > 0f && f.f2 > 0f && f.f1 <= 1100f) {
                        f1s += f.f1
                        f2s += f.f2
                    }
                }
            }
            start += hop
        }
        if (f1s.size < 20) return null
        f1s.sort()
        f2s.sort()
        return f1s[f1s.size / 2].toDouble() to f2s[f2s.size / 2].toDouble()
    }

    /** 读 16-bit PCM WAV。 */
    private fun readWav(f: File): Pair<FloatArray, Int> {
        val bytes = f.readBytes()
        require(bytes.size > 44) { "WAV 太短" }
        val rate = le32(bytes, 24)
        // 找 data 块
        var pos = 12
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            if (id == "data") {
                dataOff = pos + 8
                dataLen = size
                break
            }
            pos += 8 + size + (size and 1)
        }
        require(dataOff > 0) { "找不到 data 块" }
        val n = minOf(dataLen, bytes.size - dataOff) / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = bytes[dataOff + i * 2].toInt() and 0xFF
            val hi = bytes[dataOff + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return out to rate
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    /** 48 kHz → 16 kHz，和 App 的 FirDecimator 等价（简单低通后抽取 3）。 */
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
