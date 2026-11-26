package com.vera.pinkvoice.audio

import kotlin.math.abs
import kotlin.math.pow

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
 * 按男→女的共振峰缩放系数换算。
 *
 * ## 系数从哪来
 *
 * **Fant (1966)** 给出跨元音的男→女平均缩放 **18%**
 * （约 1/4 个八度）；**Hillenbrand & Clark (2009)**
 * 从更��的数据里得到 F1 **18%**、F2 **17%**。
 *
 * 所以这里取：
 *
 * - **F1 × 1.18** —— 往上抬（开口度变小、舌位抬高）
 * - **F2 × 1.17** —— 往前移（舌位更靠前）
 *
 * 两个系数几乎一样 —— 这正是「整体往前、往上抬」的样子：
 * 在对数频率轴上是一个**平移**，等效于声道整体缩短。
 *
 * ## 为什么不直接用「女性平均值」
 *
 * 因为平均值是**一个点**，而目标是**一片分布**。
 * 真实说话人的共振峰散布很宽 —— 同样的「女性化程度」，
 * 不同人的 F1/F2 能差上百 Hz。
 *
 * 所以这里给的是**区域**（[RADIUS_CENTS]），
 * 落在区域里就算达标，不必非对着中心点。
 *
 * ## 参考值本身
 *
 * 底子用的是 Peterson & Barney (1952) 的成人男性参考。
 * 归一化是跨人比较的科学工具，但**界面上不显示它** ——
 * 初学者看不懂「声道 17.5 cm」是什么意思，
 * 那不是训练目标，只是背后的计算细节。
 */
object FeminineTarget {

    /** F1 目标系数（往上抬）。Fant (1966) / Hillenbrand & Clark (2009)。 */
    const val F1_SHIFT = 1.18f

    /** F2 目标系数（往前移）。同上。 */
    const val F2_SHIFT = 1.17f

    /**
     * 目标区域半径（**音分**）。
     *
     * ## 为什么用音分
     *
     * 频率是等比关系，1 音分 = 0.58% 的频率差。
     * 用 Hz 定容差的话，不同元音的难度天差地别：
     * /衣/ 的 F1=270 只能容忍 ±36 Hz（12%），而
     * /啊/ 的 F2=1090 能容忍 ±131 Hz —— 差了三倍多。
     *
     * 用音分的话，每个元音的容差都是 ±12%，
     * **每个元音一样难**，用户练哪个都是同样的挑战。
     *
     * ## 视觉上也是正圆
     *
     * 元音图两个轴的跨度差 3 倍多（F1 800 Hz vs F2 2600 Hz）。
     * 用 Hz 换算成像素的话，同样 ±80 Hz 在两个方向占的比例差 3 倍，
     * 画出来是竖条 —— 看着别扭，用户也判断不出「区有多大」。
     * 用音分就一样宽了。
     *
     * ## 为什么不是更大
     *
     * ±12% 是「听起来就是这个元音」和「听起来是相邻元音」的分界附近。
     * 再大就会跟邻居的区重叠，达标了也说明不了什么。
     */
    const val RADIUS_CENTS = 120f

    /** 目标区域半径：F1（Hz）—— 由音分换算，随中心频率变。 */
    fun radiusF1(v: VowelTarget): Float = radiusHz(centerF1(v))

    /** 目标区域半径：F2（Hz）。 */
    fun radiusF2(v: VowelTarget): Float = radiusHz(centerF2(v))

    /**
     * 某个频率上的 ±[RADIUS_CENTS] 对应多少 Hz。
     */
    fun radiusHz(centerHz: Float): Float =
        centerHz * (2f.pow(RADIUS_CENTS / 1200f) - 1f)

    /** 目标区域中心：F1（归一化 Hz）。 */
    fun centerF1(v: VowelTarget): Float = v.f1 * F1_SHIFT

    /** 目标区域中心：F2（归一化 Hz）。 */
    fun centerF2(v: VowelTarget): Float = v.f2 * F2_SHIFT

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
        //
        // **方向别搞反**：
        // - F1 与开口度成**反比**：嘴张得越大，F1 越低。
        //   所以 F1 偏低（要抬高）→ 得**合嘴**。
        // - F2 与舌位前后成**正比**：舌位越靠前，F2 越高。
        //   所以 F2 偏低（要抬高）→ 舌位**往前**。
        return when {
            abs(dF2) >= abs(dF1) && !inF2 ->
                if (dF2 < 0) "舌位再往前一点" else "舌位稍往后收一点"
            !inF1 ->
                if (dF1 < 0) "嘴稍合一点" else "嘴再张开一些"
            else -> null
        }
    }
}
