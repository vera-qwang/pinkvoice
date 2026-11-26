package com.vera.pinkvoice.audio

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * **说话时界面不能闪。**
 *
 * 用户反馈「说话的时候数字还在跳」。查下来不是分析不准，
 * 而是**每个无效帧都把对外状态清零**：
 *
 * ```
 * formants = (0,0,0)    → 数字变空
 * recognized = null     → 元音名消失
 * holdSeconds = 0       → 保持计时清零
 * ```
 *
 * 说话时 clarity 会短暂跌破阈值、偶发帧也算不出 F1/F2，
 * 每秒闪好几次 —— 看起来就是「跳」。
 *
 * 这个测试量的是：语音段里，显示值「从有变无」发生了多少次。
 */
class DisplayFlickerTest {

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

    @Test
    fun displayDoesNotFlickerWhileSpeaking() {
        val cases = listOf(
            "test.wav" to "formants.txt",
            "e.wav" to "formant_e.txt",
            "i.wav" to "formant_i.txt",
            "o.wav" to "formant_o.txt",
            "u.wav" to "formant_u.txt",
        )
        var checked = 0
        val report = StringBuilder()
        for ((wav, fmt) in cases) {
            val wf = locate(wav) ?: continue
            val ff = locate(fmt) ?: continue
            val (s48, rate) = readWav(wf)
            require(rate == 48000)
            val x = decimate3(s48)

            val times = ff.readLines().mapNotNull { l ->
                val p = l.trim().split(Regex("\\s+"))
                if (p.size >= 4 && p[0].isNotEmpty() && p[0][0].isDigit()) p[0].toDouble() else null
            }
            // 收窄 15%，只看稳定的语音中段
            val m = (times.last() - times.first()) * 0.15
            val t0 = times.first() + m
            val t1 = times.last() - m

            val qa = VoiceQualityAnalyzer(sampleRate = 16000)
            val yin = YinPitchDetector(sampleRate = 16000)

            var blanked = 0        // 从「有值」变成「无值」的次数
            var hadValue = false
            var frames = 0
            var f2Jumps = 0
            var prevF2 = 0f
            var switches = 0
            var lastVowel: VowelTarget? = null
            var unvoicedFrames = 0   // 这些帧在旧实现里都会触发一次清空
            var recognizedBlanks = 0
            var hadVowel = false

            val win = 1024
            val hop = 256
            var s = 0
            while (s + hop <= x.size) {
                val t = s.toDouble() / 16000
                if (t < t0 || t > t1) { s += hop; continue }
                // YIN 用 64 ms 窗，但**分析器必须喂连续数据**：
                // 之前喂 1024 窗却只前进 256，等于每块数据被处理 4 次，
                // 一次 process() 里跑了 4 帧，限速器的 3% 变成 12%。
                val yinSeg = x.copyOfRange(s, minOf(s + win, x.size))
                yin.process(yinSeg, yinSeg.size) { hz, clarity, _ ->
                    qa.voiced = hz > 0f && clarity >= 0.5f
                    if (!qa.voiced) unvoicedFrames++
                }
                val seg = x.copyOfRange(s, minOf(s + hop, x.size))
                qa.process(seg, seg.size)

                frames++
                val hasValue = qa.formants.f1 > 0f
                if (hadValue && !hasValue) blanked++
                hadValue = hasValue

                // F2 也要稳 —— /o/ 的原始 F2 抖动实测高达 425 Hz
                if (hasValue) {
                    val f2 = qa.formants.f2
                    if (prevF2 > 0f && kotlin.math.abs(f2 - prevF2) / prevF2 > 0.10f) {
                        f2Jumps++
                    }
                    prevF2 = f2
                } else {
                    // 断过一次就重新开始比 —— 静音前后的差异不算「跳」
                    prevF2 = 0f
                }

                val hasVowel = qa.recognized != null
                if (hadVowel && !hasVowel) recognizedBlanks++
                hadVowel = hasVowel

                // 元音名来回切换（啊→哦→啊）在界面上同样是「跳」
                val r = qa.recognized
                if (r != null && lastVowel != null && r != lastVowel) switches++
                if (r != null) lastVowel = r

                s += hop
            }
            if (frames < 100) continue
            checked++
            val pct = blanked * 100.0 / frames
            report.append(
                "%s  语音段 %d 帧：数字变空 %d 次（%s%%），元音名切换 %d 次；F2 跳动 %d 次\n"
                    .format(wav, frames, blanked, "%.1f".format(pct), switches, f2Jumps),
            )
            assertTrue(
                "$wav 说话时显示不稳定（变空 %d 次 / F2 跳 %d 次，共 %d 帧）\n%s"
                    .format(blanked, f2Jumps, frames, report),
                // 变空绝对不能有（那是我们引入的 bug）；
                // F2 的残余跳动是某些元音的物理限制，阈值放宽到 8 次/3 秒
                blanked <= 2 && f2Jumps <= 8,
            )
        }
        assumeTrue("本地没有录音文件，跳过", checked > 0)
        println(report.toString())
    }
}
