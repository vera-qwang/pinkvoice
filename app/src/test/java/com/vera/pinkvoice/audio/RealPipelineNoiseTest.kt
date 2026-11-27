package com.vera.pinkvoice.audio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * 走 App **真实完整链路**的噪声测量。
 *
 * ## 为什么要这个测试
 *
 * 之前测噪声用的都是「离线复刻管线」，结论一度是
 * 「/衣/ 的 F2 σ = 317 Hz，/乌/ = 404 Hz，噪声大得没法用」。
 *
 * 但那条管线**少了 YIN 门控** —— App 里 YIN 会先判断这一帧是不是语音，
 * 不是语音就根本不跑 LPC。
 *
 * 加上 YIN 门控后：
 *
 * | | 无门控 F1 σ | 有门控 F1 σ | 无门控 F2 σ | 有门控 F2 σ |
 * |---|---|---|---|---|
 * | 啊 | 102 | **48** | 113 | **53** |
 * | 鹅 | 87 | **24** | 161 | **70** |
 * | 衣 | 69 | **30** | 329 | **224** |
 * | 乌 | 234 | **107** | 544 | **322** |
 *
 * 所以「F2 σ 300+」是**测法不对**造成的，不是 App 真的那么抖。
 *
 * 这个测试用**和 VoiceEngine 完全一样的方式**跑
 * （YIN 和分析器吃同一份 16 kHz 数据，YIN 的判定喂给分析器当门控），
 * 量出来的数字才是用户真正会看到的。
 */
class RealPipelineNoiseTest {

    /**
     * 「稳定」窗口有多长（块）。
     *
     * 一块 = 1024 个 16 kHz 样本 = 64 ms。
     */
    private val stableWindowBlocks: Int = 12

    /**
     * ## 为什么要找「最稳的一段」
     *
     * 实测显示，共振峰的大幅变化来自**元音的起音过渡**，不是测量噪声：
     *
     * | 元音 | 起音时 | 稳定段 |
     * |---|---|---|
     * | 衣 | F1 474→283，F2 **1522→2260** | F1 278，F2 2260 |
     * | 乌 | F1 232→307，F2 **1656→807** | F1 303，F2 763 |
     *
     * 人从一个音滑进另一个音（或者从静音滑进来），共振峰会连续变化 ——
     * 这是**生理事实**，显示出来也是对的，不该算进「发声稳不稳」。
     *
     * 过渡有多长因录音而异（衣的过渡持续了 18 块 ≈ 1.1 秒），
     * 所以取语音段的**中间**一段来量 —— 中间通常已经是稳定发声的。
     *
     * 这测的正是用户问的问题：「我稳稳地发一个音，数字还动吗？」
     */

    @Test
    fun displayedValuesAreNotTooNoisy() {
        val cases = listOf(
            Triple("test.wav", "formants.txt", "啊"),
            Triple("e.wav", "formant_e.txt", "鹅"),
            Triple("i.wav", "formant_i.txt", "衣"),
            Triple("o.wav", "formant_o.txt", "哦"),
            Triple("u.wav", "formant_u.txt", "乌"),
        )

        val report = StringBuilder()
        var checked = 0
        for ((wavName, fmtName, name) in cases) {
            val wav = locate(wavName) ?: continue
            locate(fmtName) ?: continue
            val series = runAppPipeline(wav) ?: continue
            checked++
            if (series.size < 8) continue

            val f1 = series.map { it.first }
            val f2 = series.map { it.second }
            if (f1.size < 8 || f2.size < 8) continue

            val s1 = stdev(f1)
            val s2 = stdev(f2)
            // 稳健离散度：P90 − P10，不受起音过渡的影响
            val r1 = percentile(f1, 90.0) - percentile(f1, 10.0)
            val r2 = percentile(f2, 90.0) - percentile(f2, 10.0)
            report.append(
                "%s  F1 σ %s Hz（P90-P10 %s）  F2 σ %s Hz（P90-P10 %s）  %d 块\n".format(
                    name, "%.0f".format(s1), "%.0f".format(r1),
                    "%.0f".format(s2), "%.0f".format(r2), f1.size,
                ),
            )

            // 稳定段的抖动要小
            assertTrue(
                "$name 稳定段的 F1 抖动太大（P90-P10 = ${"%.0f".format(r1)} Hz）\n$report",
                r1 <= 60.0,
            )
            assertTrue(
                "$name 稳定段的 F2 抖动太大（P90-P10 = ${"%.0f".format(r2)} Hz）\n$report",
                r2 <= 130.0,
            )
        }
        assumeTrue("本地没有录音文件，跳过", checked > 0)
        println(report.toString())
    }

    /**
     * 和 [VoiceEngine] 完全一样的链路：
     * 48 kHz → 16 kHz → {YIN 判断 voiced} + {共振峰分析}。
     *
     * @return 每个数据块之后的显示值 (F1, F2)
     */
    private fun runAppPipeline(wav: File): List<Pair<Float, Float>>? {
        val (samples, rate) = readWav(wav)
        require(rate == 48000) { "假定录音是 48 kHz，实际 $rate" }
        val to16 = decimate3(samples)

        val analyzer = VoiceQualityAnalyzer(sampleRate = 16000)
        val detector = YinPitchDetector()

        // 每个块：显示值 + 是不是语音帧
        val blocks = ArrayList<Triple<Float, Float, Boolean>>()
        val block = 1024
        var i = 0
        while (i < to16.size) {
            val n = minOf(block, to16.size - i)
            val chunk = to16.copyOfRange(i, i + n)

            // 和 VoiceEngine 一样：YIN 先跑，把判定喂给分析器
            detector.process(chunk, n) { hz, clarity, _ ->
                analyzer.voiced = hz > 0f && clarity >= 0.5f
            }
            analyzer.process(chunk, n)

            val f = analyzer.formants
            blocks += Triple(f.f1, f.f2, analyzer.voiced)
            i += n
        }

        // 只保留有语音、有显示值的块
        val voiced = blocks.filter { it.third && it.first > 0f && it.second > 0f }
        if (voiced.size < stableWindowBlocks + 5) return null

        // 取语音段的中间一段
        val mid = voiced.size / 2
        val from = (mid - stableWindowBlocks / 2).coerceAtLeast(0)
        val to = (from + stableWindowBlocks).coerceAtMost(voiced.size)
        return voiced.subList(from, to).map { it.first to it.second }
    }

    /** 百分位。 */
    private fun percentile(v: List<Float>, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        val idx = ((p / 100.0) * (s.size - 1)).toInt().coerceIn(0, s.size - 1)
        return s[idx].toDouble()
    }

    private fun stdev(v: List<Float>): Double {
        val mean = v.sumOf { it.toDouble() } / v.size
        var s = 0.0
        for (x in v) { val d = x - mean; s += d * d }
        return sqrt(s / v.size)
    }

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

    /** 读 16-bit PCM WAV。 */
    private fun readWav(f: File): Pair<FloatArray, Int> {
        val bytes = f.readBytes()
        require(bytes.size > 44) { "WAV 太短" }
        val rate = le32(bytes, 24)
        var pos = 12
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val sz = le32(bytes, pos + 4)
            if (id == "data") { dataOff = pos + 8; dataLen = sz; break }
            pos += 8 + sz + (sz and 1)
        }
        require(dataOff > 0) { "WAV 里没有 data 块" }
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

    /** 48 kHz → 16 kHz（抽取 3 倍），和 App 的捕获支路一致。 */
    private fun decimate3(x: FloatArray): FloatArray {
        val taps = 127
        val fc = 6800.0 / 48000.0
        val h = DoubleArray(taps)
        val mid = (taps - 1) / 2.0
        var s = 0.0
        for (i in 0 until taps) {
            val t = i - mid
            val sinc = if (t == 0.0) 2 * fc else kotlin.math.sin(2 * Math.PI * fc * t) / (Math.PI * t)
            val w = 0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * i / (taps - 1))
            h[i] = sinc * w
            s += h[i]
        }
        for (i in 0 until taps) h[i] /= s

        val out = ArrayList<Float>()
        var i = 0
        while (i < x.size) {
            var acc = 0.0
            for (q in 0 until taps) {
                val idx = i - q
                if (idx < 0) break
                acc += h[q] * x[idx]
            }
            if (i % 3 == 0) out += acc.toFloat()
            i++
        }
        return out.toFloatArray()
    }
}
