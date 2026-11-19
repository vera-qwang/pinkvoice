package com.vera.pinkvoice.audio

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
) {
    private val lpc = LpcAnalyzer(sampleRate = sampleRate)
    private val stability = StabilityTracker()

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

    /** 有效帧计数（连续有效帧数，用于"信号稳定了"的判断）。 */
    @Volatile
    var validFrames: Int = 0
        private set

    init {
        lpc.prepare(windowSize)
    }

    /**
     * 处理一帧（16 kHz 域）。
     *
     * @param samples 分析支路的样本
     * @param count 有效样本数
     */
    fun process(samples: FloatArray, count: Int) {
        if (count < windowSize) return
        if (!lpc.analyze(samples, count)) {
            validFrames = 0
            return
        }

        val raw = lpc.extractFormants()
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
    }

    /**
     * 往基准里加一个样本。
     *
     * 用户录基准时调用。
     */
    fun addBaselineSample(tilt: Float, f1: Float) {
        timbre.baseline.add(tilt, f1)
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
