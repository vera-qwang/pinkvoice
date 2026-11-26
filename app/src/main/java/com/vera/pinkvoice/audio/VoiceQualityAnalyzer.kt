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
    /** 跳帧。256 = 16 ms，和 YIN 一致。 */
    private val hopSize: Int = 256,
) {
    private val lpc = LpcAnalyzer(sampleRate = sampleRate)
    private val stability = StabilityTracker()

    /**
     * F1/F2 的中位数平滑。
     *
     * 显示、识别、距离都用**平滑后**的值 ——
     * 原始帧的抖动有几十 Hz，直接显示的话数字会疯狂跳，
     * 识别结果也会在两个元音之间来回闪。
     */
    private val smoother = FormantSmoother()

    /** 训练进度：连续保持在目标区里多久了。 */
    private val targetTracker = TargetTracker()

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

    /**
     * 当前训练目标元音。
     *
     * 设为 null 就停止计时。UI 换目标时调 [VoiceEngine.setVowelTarget]。
     */
    @Volatile
    var target: VowelTarget? = null

    /** 连续保持在目标区的秒数。 */
    @Volatile
    var holdSeconds: Float = 0f
        private set

    /** 基准录音里收集到的 F1（用来算中位数）。 */
    private val baselineF1 = ArrayList<Float>(256)

    /** 有效帧计数（连续有效帧数，用于"信号稳定了"的判断）。 */
    @Volatile
    var validFrames: Int = 0
        private set

    init {
        lpc.prepare(windowSize)
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
        if (!ok || raw.f1 <= 0f || raw.f2 <= 0f) {
            validFrames = 0
            // 信号断了就把平滑器也清掉 —— 留着过期的值显示出来更误导。
            smoother.reset()
            formants = LpcAnalyzer.Formants(0f, 0f, 0f)
            recognized = null
            recognizedDistance = Float.MAX_VALUE
            targetTracker.update(false)
            holdSeconds = targetTracker.update(false)
            return
        }
        validFrames++

        // 稳态度用**原始**频率（归一化是线性缩放，不影响抖动量）
        stabilityScore = stability.add(raw.f1, raw.f2)

        // 归一化到标准声道长度，这样不同人的元音图能对齐
        val normRaw = FormantNormalizer.normalize(raw)

        // 平滑 —— 显示、识别、距离都用平滑后的值。
        //
        // 原始帧的抖动有几十 Hz，直接显示的话数字疯狂跳，
        // 识别结果也会在两个元音之间来回闪。
        val smooth = smoother.add(normRaw.f1, normRaw.f2)
        formants = LpcAnalyzer.Formants(smooth.f1, smooth.f2, normRaw.f3)

        val t = lpc.spectralTilt()
        tilt = t

        // 元音识别（用平滑后的值，避免结果闪变）
        val r = VowelRecognizer.recognize(smooth.f1, smooth.f2)
        recognized = r.vowel
        recognizedDistance = r.distance

        // 训练进度：连续保持在目标区里多久了
        val tgt = target
        holdSeconds = if (tgt != null) {
            targetTracker.update(VowelZone.isInZone(smooth.f1, smooth.f2, tgt))
        } else {
            targetTracker.update(false)
        }

        // 音色轴
        timbreReading = timbre.read(t, normRaw.f1)

        // 基准录制
        if (capturingBaseline) {
            addBaselineSample(t, normRaw.f1)
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
        smoother.reset()
        targetTracker.reset()
        holdSeconds = 0f
    }

    /** 换训练目标：清掉之前的保持进度。 */
    fun selectTarget(v: VowelTarget?) {
        target = v
        targetTracker.reset()
        holdSeconds = 0f
    }

    /** 清空基准（重新录）。 */
    fun resetBaseline() {
        timbre.baseline.reset()
    }
}
