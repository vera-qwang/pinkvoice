package com.vera.pinkvoice.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 把 App 的分析管线跑在一段 WAV 上，逐帧导出 F1/F2/F3。
 *
 * 用途：和**真 Praat** 的逐帧输出做对照，验证分析层一致。
 * 这是开发期的工具（Praat 那边的导出需要手动跑脚本），
 * 文件不存在就跳过 —— CI 上不会失败。
 *
 * 跑法：
 * ```
 * DUMP_WAV=/tmp/live_e2e2.wav DUMP_OUT=/tmp/app_frames.txt \
 * DUMP_WINDOW=550 DUMP_HOP=250 \
 * ./gradlew :app:testDebugUnitTest --tests "*PipelineDumpTest*"
 * ```
 */
class PipelineDumpTest {

    @Test
    fun dumpPipelineFrames() {
        // 用环境变量而不是系统属性 —— Gradle 命令行的 -D 只到 Gradle 自己，
        // 不会传给测试 JVM。环境变量会继承过去。
        val env = System.getenv()
        val wavPath = env["DUMP_WAV"] ?: "/tmp/live_e2e2.wav"
        val outPath = env["DUMP_OUT"] ?: "/tmp/app_frames.txt"
        val window = env["DUMP_WINDOW"]?.toInt() ?: 550
        val hop = env["DUMP_HOP"]?.toInt() ?: 250

        val wav = File(wavPath)
        assumeTrue("找不到 ${wavPath}，跳过", wav.exists())

        val (samples, rate) = readWav(wav)
        val to16 = decimate3(samples)
        val resampler = Resampler16kTo11k()
        val out = FloatArray(to16.size + 64)
        val n11 = resampler.process(to16, to16.size, out)

        val lpc = LpcAnalyzer(sampleRate = 11000, order = 10)
        lpc.prepare(window)

        val sb = StringBuilder()
        sb.append("T F1 F2 F3\n")
        var start = 0
        var frame = 0
        while (start + window <= n11) {
            val t = (start + window / 2).toDouble() / 11000.0
            if (lpc.analyze(out.copyOfRange(start, start + window), window)) {
                val f = lpc.extractFormants()
                sb.append("%.5f %.2f %.2f %.2f\n".format(t, f.f1, f.f2, f.f3))
                frame++
            }
            start += hop
        }
        File(outPath).writeText(sb.toString())
        println("导出了 $frame 帧 → $outPath（窗 $window / 跳 $hop @ 11 kHz）")
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

    /** 48 kHz → 16 kHz（抽取 3），和 App 的 FirDecimator 等价。 */
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
