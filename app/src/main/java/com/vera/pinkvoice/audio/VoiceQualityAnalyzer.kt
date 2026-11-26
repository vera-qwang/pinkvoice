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

    /**
     * F1/F2 的中位数平滑。
     *
     * 显示、识别、距离都用**平滑后**的值 ——
     * 原始帧的抖动有几十 Hz，直接显示的话数字会疯狂跳，
     * 识别结果也会在两个元音之间来回闪。
     */
    private val smoother = FormantSmoother()

    /**
     * 共振峰跟踪器。
     *
     * 放在平滑**之前**：跟踪器负责压住离群帧（连续几帧的坏读数），
     * 平滑负责把剩下的抖动磨平。
     * 顺序反过来的话，平滑器会把坏帧也吸进来。
     */
    private val tracker = FormantTracker()

    /** 训练进度：连续保持在目标区里多久了。 */
    private val targetTracker = TargetTracker()

    /** 诊断计数（每 16 帧打一次）。 */
    private var diagFrame = 0

    /**
     * 滑动缓冲。
     *
     * 和 [YinPitchDetector] 用同样的窗口/跳帧，所以两边的分析
     * 落在**同一帧数据**上 —— 时间对齐，UI 上能直接叠在一起看。
     */
    private val buf = FloatArray(windowSize + hopSize)
    private var fill = 0

    /** 识别出的元音。 */
    @Volatile
    var recognized: VowelTarget? = null
        private set

    /** 到识别出的元音的距离。 */
    @Volatile
    var recognizedDistance: Float = Float.MAX_VALUE
        private set

    /** 最近一次的共振峰（原始频率）。 */
    @Volatile
    var formants: LpcAnalyzer.Formants = LpcAnalyzer.Formants(0f, 0f, 0f)
        private set

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

    /** 有效帧计数（连续有效帧数，用于"信号稳定了"的判断）。 */
    @Volatile
    var validFrames: Int = 0
        private set

    init {
        lpc.prepare(windowSize)
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

        // ---- 诊断：必须在「判有效」**之前** ----
        //
        // 之前放在有效性判断之后，结果一帧都打不出来 ——
        // F1 被判无效时函数已经 return 了，而那正是最需要看诊断的时候。
        //
        // 每 16 帧（约 256 ms）一行根列表 + 一行原始频谱。
        if (Diagnostics.enabled && ok && ++diagFrame % 16 == 0) {
            val d = lpc.diagnose()
            Diagnostics.log(
                "f=%d/%d roots=[%s]".format(
                    raw.f1.toInt(), raw.f2.toInt(),
                    d.roots.joinToString { "%.0f(bw%.0f)".format(it.hz, it.bandwidthHz) },
                ),
            )
            val spec = lpc.spectrumDb(samples, count)
            if (spec.isNotEmpty()) {
                Diagnostics.log(
                    "SPEC " + spec.joinToString(" ") { "%d:%.0f".format(it.first, it.second) },
                )
            }
        }

        if (!ok || raw.f1 <= 0f || raw.f2 <= 0f) {
            validFrames = 0
            // 信号断了就把跟踪器和平滑器都清掉 ——
            // 留着过期的值显示出来更误导。
            tracker.reset()
            smoother.reset()
            formants = LpcAnalyzer.Formants(0f, 0f, 0f)
            recognized = null
            recognizedDistance = Float.MAX_VALUE
            // 只调一次。调两次的话每个无效帧 missFrames += 2，
            // 宽限期（2 帧）等于直接失效 ——
            // 攒了几秒的保持进度会被一个偶发坏帧清零。
            holdSeconds = targetTracker.update(false)
            return
        }
        validFrames++

        // 平滑 —— 显示、识别、距离都用平滑后的值。
        //
        // 原始帧的抖动有几十 Hz，直接显示的话数字疯狂跳，
        // 识别结果也会在两个元音之间来回闪。
        //
        // 用的是**原始频率**，不做声道归一化 ——
        // 归一换算是跨人对比的科研工具，对训练没帮助，
        // 而且它会把「天生的声道差异」也消掉，
        // 但那恰恰是「听起来像不像女性」的一部分。
        val tracked = tracker.add(raw.f1, raw.f2)
        val smooth = smoother.add(tracked.f1, tracked.f2)
        formants = LpcAnalyzer.Formants(smooth.f1, smooth.f2, raw.f3)

        // 元音识别（用平滑后的值，避免结果闪变）
        val r = VowelRecognizer.recognize(smooth.f1, smooth.f2)
        recognized = r.vowel
        recognizedDistance = r.distance

        // 训练进度：连续保持在目标区里多久了
        val tgt = target
        holdSeconds = if (tgt != null) {
            // 和 UI 用**同一个判定函数**，连 F2 可信度豁免都一样。
            val t = smooth.f1
            val u = smooth.f2
            val inZone = VowelZone.isInZone(
                t,
                u,
                tgt,
                f1Credible = true,
                f2Credible = VowelZone.isF2Credible(u, tgt),
            )
            targetTracker.update(inZone)
        } else {
            targetTracker.update(false)
        }

    }

    /** 清空所有状态。 */
    fun reset() {
        fill = 0
        validFrames = 0
        formants = LpcAnalyzer.Formants(0f, 0f, 0f)
        recognized = null
        recognizedDistance = Float.MAX_VALUE
        tracker.reset()
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

}
