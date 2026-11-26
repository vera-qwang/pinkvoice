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
 * 元音识别。
 *
 * 用 F1/F2 和**目标元音**比距离，取最近的。
 *
 * ## 用的是原始频率，不做声道归一化
 *
 * 之前这里做过归一化（折算到 17.5 cm 参考声道）——
 * 那是跨人对比的**科研工具**，对训练没有帮助：
 * 用户看不懂那个换算，也不知道该拿它怎么办。
 *
 * 而且它会把「天生的声道差异」也消掉 ——
 * 但那恰恰是「听起来像不像女性」的一部分。
 * 目标区域应该拿**原始频率**去比。
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
