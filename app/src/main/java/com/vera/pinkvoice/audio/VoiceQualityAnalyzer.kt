package com.vera.pinkvoice.audio

import android.util.Log

/**
 * 嗓音质量分析：共振峰、元音识别、稳态度、音色轴。
 *
 * ## 挂在哪
 *
 * 复用现有的分析支路（16 kHz / 1024 窗 / 256 跳）——
 * 也就是 YIN 已经在用的那条路。**不新开采集、不新开线程**，
 * 只是把同一帧数据多算一遍 LPC。
 *
 * ## 性能
 *
 * LPC 每帧的算量：1024 点自相关（约 12k 次乘加）+ 12 阶 Levinson
 * + 12 次多项式求根。16 ms 一帧 → 大约每秒 60 次。
 * 在手机上大约是几毫秒的 CPU，占用很低。
 *
 * 但**默认不开** —— 监测页（f0 曲线）不需要这些，
 * 只有训练页才打开。
 */
class VoiceQualityAnalyzer(
    private val sampleRate: Int = 16000,
    private val windowSize: Int = 1024,
    /** 跳帧。256 = 16 ms，和 YIN 一致。 */
    private val hopSize: Int = 256,
) {
    private val lpc = LpcAnalyzer(sampleRate = sampleRate)
    private val stability = StabilityTracker()

    /**
     * 滑动缓冲。
     *
     * 和 [YinPitchDetector] 用同样的窗口/跳帧，所以两边的分析
     * 落在**同一帧数据**上 —— 时间对齐，UI 上能直接叠在一起看。
     */
    private val buf = FloatArray(windowSize + hopSize)
    private var fill = 0

    /** 音色轴（含个人基准）。 */
    val timbre = TimbreAxis()

    /** 识别出的元音。 */
    @Volatile
    var recognized: VowelTarget? = null
        private set

    /** 到识别出的元音的距离。 */
    @Volatile
    var recognizedDistance: Float = Float.MAX_VALUE
        private set

    /** 当前稳态度（0..1）。 */
    @Volatile
    var stabilityScore: Float = 0f
        private set

    /** 当前的音色轴读数。 */
    @Volatile
    var timbreReading: TimbreAxis.Reading = TimbreAxis.Reading(0f, 0f, 0f, false)
        private set

    /** 最近一次的共振峰（归一化后）。 */
    @Volatile
    var formants: LpcAnalyzer.Formants = LpcAnalyzer.Formants(0f, 0f, 0f)
        private set

    /** 最近的谱倾斜（dB/oct）。 */
    @Volatile
    var tilt: Float = 0f
        private set

    /**
     * 是否正在录基准。
     *
     * 基准同时干两件事：
     * 1. 用念 /a/ 时的实测 F1 反推声道长度（归一化的前提）
     * 2. 作为音色轴的个人基准
     */
    @Volatile
    var capturingBaseline: Boolean = false
        private set

    /** 基准录制的进度（0..1）。 */
    @Volatile
    var baselineProgress: Float = 0f
        private set

    private var baselineFrames = 0

    private var diagCount = 0

    /** 诊断用的几组参数对比。 */
    private val diagAnalyzers = listOf(
        LpcAnalyzer(sampleRate, 12, 0.3f),
        LpcAnalyzer(sampleRate, 16, 0.3f),
        LpcAnalyzer(sampleRate, 20, 0.3f),
        LpcAnalyzer(sampleRate, 12, 0.7f),
        LpcAnalyzer(sampleRate, 16, 0.7f),
        LpcAnalyzer(sampleRate, 16, 0.97f),
    )

    /** 基准录音里收集到的 F1（用来算中位数）。 */
    private val baselineF1 = ArrayList<Float>(256)

    /** 有效帧计数（连续有效帧数，用于"信号稳定了"的判断）。 */
    @Volatile
    var validFrames: Int = 0
        private set

    init {
        lpc.prepare(windowSize)
        diagAnalyzers.forEach { it.prepare(windowSize) }
    }

    companion object {
        /** 基准录音的帧数（16 ms/帧 → 约 3 秒）。 */
        const val BASELINE_FRAMES = 190
    }

    /**
     * 送入分析域样本（16 kHz）。
     *
     * 内部按 hop 切成一帧一帧地处理，和 YIN 的节奏一致。
     *
     * @param samples 分析支路的样本
     * @param count 有效样本数
     */
    fun process(samples: FloatArray, count: Int) {
        var consumed = 0
        while (consumed < count) {
            val chunk = minOf(count - consumed, buf.size - fill)
            System.arraycopy(samples, consumed, buf, fill, chunk)
            fill += chunk
            consumed += chunk
            while (fill >= windowSize) {
                processWindow()
                System.arraycopy(buf, hopSize, buf, 0, fill - hopSize)
                fill -= hopSize
            }
        }
    }

    /**
     * 处理一帧（16 kHz 域）。
     *
     * @param samples 分析支路的样本
     * @param count 有效样本数
     */
    private fun processWindow(samples: FloatArray = buf, count: Int = windowSize) {
        val ok = lpc.analyze(samples, count)
        val raw = if (ok) lpc.extractFormants() else LpcAnalyzer.Formants(0f, 0f, 0f)
        if (++diagCount % 25 == 0) {
            val roots = lpc.extractFormantsByRoots()
            val peaks = lpc.findPeaks(150f, 4000f)
            Log.d(
                "VoiceQuality",
                "ok=$ok f=${raw.f1.toInt()}/${raw.f2.toInt()} roots=" +
                    roots.joinToString { "%.0f(%.0f)".format(it.hz, it.bandwidthHz) } +
                    " peaks=" + peaks.joinToString { "%.0f".format(it) } +
                    " env=" + (700..2600 step 100).joinToString {
                        "${it}:${"%.0f".format(lpc.envelopeDb(it.toFloat()))}"
                    } +
                    " raw=" + lpc.debugAllRoots().joinToString {
                        "%.0f(%.0f,%.2f)".format(it.first, it.second, it.third)
                    } +
                    diagAnalyzers.joinToString { da ->
                        if (!da.analyze(samples, count)) return@joinToString "X"
                        val pk = da.findPeaks(150f, 4000f)
                        "[" + pk.joinToString { "%.0f".format(it) } + "]"
                    },
            )
        }
        if (!ok) {
            validFrames = 0
            return
        }
        if (raw.f1 <= 0f || raw.f2 <= 0f) {
            validFrames = 0
            return
        }
        validFrames++

        // 归一化到标准声道长度，这样不同人的元音图能对齐
        val norm = FormantNormalizer.normalize(raw)
        formants = norm

        val t = lpc.spectralTilt()
        tilt = t

        // 稳态度用**原始**频率（归一化是线性缩放，不影响抖动量）
        stabilityScore = stability.add(raw.f1, raw.f2)

        // 元音识别
        val r = VowelRecognizer.recognize(norm.f1, norm.f2)
        recognized = r.vowel
        recognizedDistance = r.distance

        // 音色轴
        timbreReading = timbre.read(t, norm.f1)

        // 基准录制
        if (capturingBaseline) {
            addBaselineSample(t, norm.f1)
            baselineFrames++
            baselineProgress = baselineFrames.toFloat() / BASELINE_FRAMES
            if (baselineFrames >= BASELINE_FRAMES) {
                finishBaseline()
            }
        }
    }

    /**
     * 开始录基准。
     */
    fun startBaseline() {
        baselineFrames = 0
        baselineProgress = 0f
        baselineF1.clear()
        capturingBaseline = true
    }

    /** 取消录制。 */
    fun cancelBaseline() {
        capturingBaseline = false
        baselineProgress = 0f
        baselineFrames = 0
        baselineF1.clear()
    }

    /**
     * 结束录制并应用。
     *
     * 声道长度用 F1 的**中位数**估计，不用平均值 ——
     * 平均值会被起音、收音这几帧拉偏。
     */
    private fun finishBaseline() {
        capturingBaseline = false
        baselineProgress = 1f
        if (baselineF1.size >= 10) {
            val sorted = baselineF1.sorted()
            val median = sorted[sorted.size / 2]
            FormantNormalizer.calibrate(median)
        }
    }

    /**
     * 往基准里加一个样本。
     *
     * 用户录基准时调用。
     */
    fun addBaselineSample(tilt: Float, f1: Float) {
        timbre.baseline.add(tilt, f1)
        baselineF1.add(f1)
    }

    /**
     * 用实测的 /a/ 的 F1 校准声道长度。
     *
     * @return 估计的声道长度（cm）
     */
    fun calibrateLength(measuredF1: Float): Float =
        FormantNormalizer.calibrate(measuredF1)

    /** 清空所有状态。 */
    fun reset() {
        fill = 0
        stability.reset()
        stabilityScore = 0f
        validFrames = 0
        formants = LpcAnalyzer.Formants(0f, 0f, 0f)
        recognized = null
        recognizedDistance = Float.MAX_VALUE
        timbreReading = TimbreAxis.Reading(0f, 0f, 0f, false)
    }

    /** 清空基准（重新录）。 */
    fun resetBaseline() {
        timbre.baseline.reset()
    }
}
