package com.vera.pinkvoice.audio

/**
 * 嗓音质量分析：共振峰、元音识别、训练进度。
 *
 * ## 数据流
 *
 * ```
 * 16 kHz（YIN 用的那条分析支路）
 *      ↓ 重采样 3:4
 * 12 kHz  ← LPC 在这上面跑（理由见 [Resampler16kTo12k]）
 *      ↓ 600 点窗（50 ms）/ 192 点跳（16 ms）
 * 共振峰 → 平滑 → 识别 / 目标区判定
 * ```
 *
 * 为什么要多一次重采样：LPC 在 12 kHz 上明显更准 ——
 * 16 kHz 时 F1 的极点会被低频倾斜撑宽、常和邻居合并，
 * 实测对某些元音**整个丢掉 F1**。这套配置是从 Praat 反推出来的。
 *
 * ## 性能
 *
 * Burg 10 阶 + 10 个根。每秒约 60 帧。
 * 重采样每输出样本约 12 次乘加。
 * 整体在手机上仍然很轻。
 *
 * 但**默认不开** —— 监测页（f0 曲线）不需要这些，
 * 只有训练页才打开（debug 版诊断会强制打开）。
 */
/** LPC 分析速率。见 [Resampler16kTo12k] 的说明。 */
private const val LPC_RATE = 12000

class VoiceQualityAnalyzer(
    /** 输入采样率（分析支路的速率）。 */
    private val sampleRate: Int = 16000,
) {
    /** LPC 分析用的速率。 */
    private val lpcRate: Int = LPC_RATE

    /** 50 ms 窗 —— Praat 默认 25 ms，但 50 ms 在真人录音上更稳。 */
    private val windowSize: Int = lpcRate / 20

    /** 16 ms 跳，和 YIN 一致，方便时间对齐。 */
    private val hopSize: Int = lpcRate / 62

    private val resampler = Resampler16kTo12k()

    private val lpc = LpcAnalyzer(sampleRate = lpcRate)

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

    /** 重采样后的 12 kHz 样本。 */
    private var rateBuf = FloatArray(sampleRate * 2)
    private var rateFill = 0

    /** 重采样的输出暂存区。 */
    private var scratch = FloatArray(1024)

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
        if (count <= 0) return

        // ① 16 kHz → 12 kHz。一次处理整块，产出约 3/4 的样本数。
        val maxOut = count * 3 / 4 + 8
        if (scratch.size < maxOut) scratch = FloatArray(maxOut * 2)
        val n = resampler.process(samples, count, scratch)
        if (n > 0) {
            if (rateBuf.size < rateFill + n) {
                rateBuf = rateBuf.copyOf((rateFill + n) * 2)
            }
            System.arraycopy(scratch, 0, rateBuf, rateFill, n)
            rateFill += n
        }

        // ② 在 12 kHz 上分帧
        var consumed = 0
        while (consumed < rateFill) {
            val chunk = minOf(rateFill - consumed, buf.size - fill)
            System.arraycopy(rateBuf, consumed, buf, fill, chunk)
            fill += chunk
            consumed += chunk
            while (fill >= windowSize) {
                processWindow(buf, windowSize)
                System.arraycopy(buf, hopSize, buf, 0, fill - hopSize)
                fill -= hopSize
            }
        }
        if (consumed > 0) {
            if (rateFill - consumed > 0) {
                System.arraycopy(rateBuf, consumed, rateBuf, 0, rateFill - consumed)
            }
            rateFill -= consumed
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
