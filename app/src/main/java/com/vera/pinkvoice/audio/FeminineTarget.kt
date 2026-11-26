package com.vera.pinkvoice.audio

import kotlin.math.abs

/**
 * 女性化的共振峰目标。
 *
 * ## 目标是什么
 *
 * **不是**「把 /a/ 念成标准 /a/」—— 那是元音身份，不是女性化。
 *
 * 女性化的目标是：**把共振峰空间整体往前、往上抬**，
 * 等效于一个更短的声道、更靠前的共鸣，
 * 同时保持元音仍然可辨认。
 *
 * 声道短 → 所有共振峰都高。所以从参考值出发，
 * 按常用的女性化系数换算：
 *
 * - **F1 × 1.10** —— 往上抬（开口度变小、舌位抬高）
 * - **F2 × 1.20** —— 往前移（舌位更靠前）
 *
 * 这约等于声道缩短 8–10%。
 *
 * ## 为什么不直接用「女性平均值」
 *
 * 因为平均值是**一个点**，而目标是**一片分布**。
 * 真实说话人的共振峰散布很宽 —— 同样的「女性化程度」，
 * 不同人的 F1/F2 能差上百 Hz。
 *
 * 所以这里给的是**区域**（[SPREAD]），
 * 落在区域里就算达标，不必非对着中心点。
 *
 * ## 参考值本身
 *
 * 底子用的是经典的成人男性参考（归一化到 17.5 cm 声道）。
 * 归一化是跨人比较的科学工具，但**界面上不显示它** ——
 * 初学者看不懂「声道 17.5 cm」是什么意思，
 * 那不是训练目标，只是背后的计算细节。
 */
object FeminineTarget {

    /** F1 目标系数（往上抬）。 */
    const val F1_SHIFT = 1.10f

    /** F2 目标系数（往前移）。 */
    const val F2_SHIFT = 1.20f

    /**
     * 目标区域半径：F1（Hz）。
     *
     * 用**绝对值**而不是百分比。
     *
     * 跨说话人的共振峰变异在绝对值上更接近 —— 归一化之后，
     * F1 的散布大约 ±60–90 Hz，F2 大约 ±150–200 Hz，
     * 不管是哪个元音。
     *
     * 用百分比会有两个问题：
     * - /衣/ 的 F1 只有 297 Hz，12% 才 ±36 Hz —— 比音分还紧，根本进不去
     * - /乌/ 的 F2 才 1044 Hz，12% 是 ±125 Hz —— 松得没意义
     *
     * 用绝对值还有个好处：所有元音的区域在图上**大小一致**，
     * 看起来是一套统一的东西，而不是大小不一的斑点。
     */
    const val RADIUS_F1_HZ = 80f

    /** 目标区域半径：F2（Hz）。 */
    const val RADIUS_F2_HZ = 180f

    /** 目标区域中心：F1（归一化 Hz）。 */
    fun centerF1(v: VowelTarget): Float = v.f1 * F1_SHIFT

    /** 目标区域中心：F2（归一化 Hz）。 */
    fun centerF2(v: VowelTarget): Float = v.f2 * F2_SHIFT

    /** 目标区域半径：F1（Hz）。 */
    fun radiusF1(v: VowelTarget): Float = RADIUS_F1_HZ

    /** 目标区域半径：F2（Hz）。 */
    fun radiusF2(v: VowelTarget): Float = RADIUS_F2_HZ

    /**
     * 当前值相对**参考值**（男性）前移了多少（百分比）。
     *
     * 正数 = 已往前/往上抬。负数 = 还在后面。
     *
     * 这是「整体前移」的进度量：目标分别是 +10% 和 +20%。
     */
    fun shiftPercentF1(currentF1: Float, v: VowelTarget): Float =
        if (v.f1 <= 0f) 0f else (currentF1 - v.f1) / v.f1 * 100f

    fun shiftPercentF2(currentF2: Float, v: VowelTarget): Float =
        if (v.f2 <= 0f) 0f else (currentF2 - v.f2) / v.f2 * 100f

    /**
     * 目标区域的方向提示。
     *
     * 告诉用户**该往哪调**，而不只是"你偏了多少"。
     */
    fun hint(f1: Float, f2: Float, v: VowelTarget): String? {
        val dF1 = f1 - centerF1(v)
        val dF2 = f2 - centerF2(v)
        val inF1 = abs(dF1) <= radiusF1(v)
        val inF2 = abs(dF2) <= radiusF2(v)
        if (inF1 && inF2) return null
        // 只说差得最多的那个方向 —— 两个都说反而不知道先动哪个
        return when {
            abs(dF2) >= abs(dF1) && !inF2 ->
                if (dF2 < 0) "舌位再往前一点" else "舌位稍往后收一点"
            !inF1 ->
                if (dF1 < 0) "嘴再张开一些" else "嘴稍合一点"
            else -> null
        }
    }
}
