package com.vera.pinkvoice.audio

import kotlin.math.log2

/**
 * 目标元音。
 *
 * F1/F2 用的是**归一化**坐标（声道长度折算到 17.5 cm 的标准男性声道），
 * 这样不同性别、不同声道长度的人的元音图可以对齐比较。
 */
enum class VowelTarget(
    /** 显示名。 */
    val label: String,
    /** 归一化后的目标 F1（Hz）。 */
    val f1: Float,
    /** 归一化后的目标 F2（Hz）。 */
    val f2: Float,
) {
    /** 啊 —— 嘴张最大。 */
    A("啊", 730f, 1090f),

    /** 衣 —— 舌位最高最前。 */
    I("衣", 270f, 2290f),

    /** 乌 —— 舌位最高最后。 */
    U("乌", 300f, 870f),

    /** 唉 —— 介于啊和衣之间。 */
    AE("哎", 660f, 1720f),

    /** 呃 —— 介于啊和乌之间。 */
    EH("诶", 530f, 1840f),

    /** 哦 —— 双元音起点，圆唇。 */
    AO("哦", 570f, 840f),
    ;

    /** 在元音图上的位置（F1 越低越高、F2 越低越靠前）。 */
    val x: Float get() = f2
    val y: Float get() = f1
}

/**
 * 声道长度归一化。
 *
 * ## 为什么需要
 *
 * 绝对共振峰频率因人而异 —— 声道长的人所有共振峰都低。
 * 但**相对关系**是稳定的。语音学里的标准做法是把共振峰折算到
 * 一个**标准声道长度**（成年男性约 17.5 cm），这样得到的元音图
 * 对所有人都是同一套坐标。
 *
 * 折算公式（Peterson & Barney 的经典做法）：
 *
 * ```
 * f_norm = f_actual × (L_actual / L_reference)
 * ```
 *
 * ## 声道长度怎么估
 *
 * 用 F3/F4 的关系（标尺法）太复杂，这里用一个更稳的办法：
 * **让用户自己录基准**。录下他念 /a/ 时测到的 F1，
 * 反推出他的声道长度，再把实时数据归一化到同一尺度。
 *
 * 这样：
 * - 不需要用户知道自己的声道长度
 * - 每个用户的元音图都落在同一个坐标系里
 * - 基准段同时还能当"稳态训练"的参考
 */
object FormantNormalizer {

    /** 标准男性声道长度（cm），语音学里的通用参考值。 */
    const val REFERENCE_LENGTH_CM = 17.5f

    /**
     * 用户声道的估计长度（cm）。
     *
     * 初始值用标准值；用户录了基准之后按实测 F1 反推。
     */
    @Volatile
    var userLengthCm: Float = REFERENCE_LENGTH_CM
        private set

    /**
     * 用念 /a/ 时的实测 F1 反推声道长度。
     *
     * 道理：/a/ 的归一化 F1 是 730 Hz（固定值，因为大家都在发同一个元音，
     * 只是声道长度不同）。所以
     *
     * ```
     * L_user = L_ref × (F1_measured / F1_normalized)
     * ```
     *
     * @param measuredF1 实际测到的 /a/ 的 F1（Hz）
     */
    fun calibrate(measuredF1: Float): Float {
        if (measuredF1 < 200f || measuredF1 > 1200f) return userLengthCm
        // 方向别搞反：F1 测出来**低**，说明声道**长**（共振峰都往下移）。
        // 所以是 normalized / measured，不是 measured / normalized。
        val length = REFERENCE_LENGTH_CM * (VowelTarget.A.f1 / measuredF1)
        userLengthCm = length.coerceIn(11f, 24f)
        return userLengthCm
    }

    /** 恢复默认（换设备、重新校准前用）。 */
    fun reset() {
        userLengthCm = REFERENCE_LENGTH_CM
    }

    /**
     * 把实测共振峰归一化到标准声道。
     *
     * @param actual 实测频率（Hz）
     * @return 归一化后的频率（Hz）
     */
    fun normalize(actual: Float): Float =
        actual * (userLengthCm / REFERENCE_LENGTH_CM)

    /**
     * 把共振峰归一化到标准声道（同时处理 F1/F2/F3）。
     */
    fun normalize(f: LpcAnalyzer.Formants): LpcAnalyzer.Formants {
        val k = userLengthCm / REFERENCE_LENGTH_CM
        return LpcAnalyzer.Formants(f.f1 * k, f.f2 * k, f.f3 * k)
    }
}

/**
 * 元音识别。
 *
 * 用归一化后的 F1/F2 和**目标元音**比距离，取最近的。
 *
 * ## 为什么不直接拿绝对频率比
 *
 * 因为绝对值因人而异（见 [FormantNormalizer]）。
 * 归一化之后，/a/ 的 F1 就该在 730 附近，/i/ 的 F2 就该在 2290 附近 ——
 * 每个人都在同一个坐标系里。
 *
 * ## 已知限制：身份和女性化是纠缠的
 *
 * 识别用的是**参考声道**里的元音位置，而女性化目标在它前面 ——
 * 声音往前挪之后，识别标签会跟着漂。
 *
 * 实测：目标位置的 /æ/（726, 2064）会被认成 /诶/，
 * 目标位置的 /哦/（627, 1008）会被认成 /啊/。
 * 主要的 /a/ /i/ /u/ 没问题。
 *
 * 试过三种改法，都有更糟的副作用：
 *
 * 1. **把参照位置换成目标空间** —— 男性位置的 /i/（270, 2290）
 *    离目标位置的 /诶/ 更近，被认成 /诶/。
 * 2. **识别时允许整体缩放** —— /啊/ 和 /哦/ 的 F1:F2 比例几乎一样
 *    （1.49 vs 1.47），任何缩放都能把一个套到另一个上，
 *    噪声一大标签就在两者间闪。
 * 3. **用目标元音来估缩放因子** —— 目标本身估错了的时候，
 *    整个识别都跟着歪。
 *
 * 根因是元音图本身的局限：「啊」和「哦」的区别主要在圆唇
 * （影响 F3 和频谱倾斜），不在 F1/F2 上。
 * 只靠 F1/F2 分不出这两个 —— 这是元音图的天花板，
 * 不是实现能绕过去的。
 */
object VowelRecognizer {

    /** 识别结果。 */
    data class Result(
        /** 识别出的元音；null 表示哪个都不像。 */
        val vowel: VowelTarget?,
        /** 与目标元音的距离（归一化 Hz 的欧氏距离）。 */
        val distance: Float,
    ) {
        /** 是否够接近（可以认为"就是它"）。 */
        val confident: Boolean get() = vowel != null && distance <= CONFIDENT_DISTANCE
    }

    /**
     * 判定"就是这个元音"的距离阈值。
     *
     * 归一化 Hz 空间里的欧氏距离。实测合成元音的误差在 40–80 Hz，
     * 取 180 作为"认得出"的界。
     */
    const val CONFIDENT_DISTANCE = 180f

    /**
     * 识别一个元音。
     *
     * @param f1 归一化后的 F1（Hz）
     * @param f2 归一化后的 F2（Hz）
     */
    fun recognize(f1: Float, f2: Float): Result {
        if (f1 <= 0f || f2 <= 0f) return Result(null, Float.MAX_VALUE)
        var best: VowelTarget? = null
        var bestDist = Float.MAX_VALUE
        for (v in VowelTarget.entries) {
            val dx = f2 - v.f2
            val dy = f1 - v.f1
            // F2 的差异对"听起来像不像"影响更大（舌位变化更明显），
            // 所以给它 1.4 倍权重。
            val d = kotlin.math.sqrt(dx * dx + (dy * 1.4f) * (dy * 1.4f))
            if (d < bestDist) {
                bestDist = d
                best = v
            }
        }
        return Result(best, bestDist)
    }

    /**
     * 到目标元音的距离（越小越像）。
     */
    fun distanceTo(target: VowelTarget, f1: Float, f2: Float): Float {
        if (f1 <= 0f || f2 <= 0f) return Float.MAX_VALUE
        val dx = f2 - target.f2
        val dy = (f1 - target.f1) * 1.4f
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

/**
 * 稳态度。
 *
 * ## 测什么
 *
 * 帧与帧之间的**抖动**。稳态元音训练要的就是这个 ——
 * 不是"音高准不准"（那是别的事），而是"音色的各个维度稳不稳"。
 *
 * 用共振峰的**帧间标准差**衡量，单位换算成音分（cent），
 * 这样和听感一致（1 音分 = 1% 的频率差）。
 *
 * ## 为什么用中位数
 *
 * 单帧的共振峰会偶发跳变（清音、换气、爆破音），
 * 标准差会被这些离群点拉爆。用**帧间差分**再取中位数，
 * 对离群点免疫得多。
 */
class StabilityTracker(
    /** 统计窗口（帧）。16 ms/帧 → 64 帧 ≈ 1 秒。 */
    private val windowFrames: Int = 64,
) {
    private val f1 = FloatArray(windowFrames)
    private val f2 = FloatArray(windowFrames)
    private var head = 0
    private var size = 0

    /**
     * 加一帧。
     *
     * @return 当前的稳定度（0..1，1 = 完全稳定）；数据不够时返回 0
     */
    fun add(f1Hz: Float, f2Hz: Float): Float {
        if (f1Hz > 0f && f2Hz > 0f) {
            f1[head] = f1Hz
            f2[head] = f2Hz
            head = (head + 1) % windowFrames
            if (size < windowFrames) size++
        }
        return stability()
    }

    /** 清空。 */
    fun reset() {
        head = 0
        size = 0
    }

    /**
     * 当前的稳定度（0..1）。
     *
     * 做法：算相邻帧的频率差，取中位数，换算成音分，
     * 再映射到 0..1。
     *
     * 映射用经验值：
     * - 抖动 ≤ 10 音分 → 1.0（几乎不动）
     * - 抖动 ≥ 120 音分 → 0.0（完全不稳）
     */
    fun stability(): Float {
        if (size < 8) return 0f
        val diffs = FloatArray(size - 1)
        var n = 0
        for (k in 1 until size) {
            val i0 = (head - size + k - 1 + windowFrames * 2) % windowFrames
            val i1 = (head - size + k + windowFrames * 2) % windowFrames
            val d1 = centsBetween(f1[i0], f1[i1])
            val d2 = centsBetween(f2[i0], f2[i1])
            // 取**平均**，不取 min。
            //
            // 取 min 的话，只要有一个维度稳（比如 F2 不动）就完全掩盖了
            // 另一个维度的抖动 —— 实测抖动 ±60 Hz 的信号会拿到满分稳定度。
            // 取 max 又太苛刻，一个维度有噪声就整体判不稳。
            diffs[n++] = (d1 + d2) / 2f
        }
        val sorted = diffs.copyOf(n).also { it.sort() }
        val median = sorted[n / 2]
        val s = ((120f - median) / 110f).coerceIn(0f, 1f)
        return s
    }

    /**
     * 两个频率之间的音分差（**取绝对值**）。
     *
     * 必须取绝对值：抖动是双向的，不取绝对值的话正负差值互相抵消，
     * 中位数≈0，看起来比实际稳得多。
     */
    private fun centsBetween(a: Float, b: Float): Float {
        if (a <= 0f || b <= 0f) return 0f
        val cents = (1200f * log2(b.toDouble() / a.toDouble())).toFloat()
        return if (cents < 0f) -cents else cents
    }
}
