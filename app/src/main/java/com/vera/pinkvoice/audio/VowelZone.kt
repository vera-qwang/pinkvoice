package com.vera.pinkvoice.audio

import kotlin.math.abs
import kotlin.math.log2

/**
 * 元音的**目标区域**。
 *
 * ## 它是什么
 *
 * 一个以「女性化目标」为中心、覆盖统计范围的**区域**，
 * 不是「标准元音在图上的那个点」。
 *
 * 真实说话人的共振峰散布很宽 —— 同样的女性化程度，
 * 不同人的 F1/F2 能差上百 Hz。
 * 所以目标是区域，落在区域里就算达标。
 *
 * ## 与「元音身份」是两回事
 *
 * [VowelRecognizer] 回答「你在发哪个元音」（舌位前后高低）。
 * 这里回答「你的共振峰空间离女性化目标还有多远」。
 *
 * 两个问题都要问，但**不能混为一谈** ——
 * 落到标准元音的点 ≠ 女性化练好了。
 */
object VowelZone {

    /**
     * 实际频率相对目标频率偏了多少音分。
     *
     * 正数 = 偏高，负数 = 偏低。
     */
    fun deviationCents(actual: Float, target: Float): Float {
        if (actual <= 0f || target <= 0f) return 0f
        return (1200f * log2(actual.toDouble() / target.toDouble())).toFloat()
    }

    /**
     * 是否在目标区域内。
     *
     * @param f1Credible F1 读数可信吗
     * @param f2Credible F2 读数可信吗
     *
     * ## 为什么要带 credible 参数
     *
     * **判定必须只有一处。**
     *
     * 之前 UI 和保持计时各判各的：
     * - UI：F1 在区 **且**（F2 不可信 **或** F2 在区）
     * - 计时：F1 和 F2 都必须在区
     *
     * 结果是 F2 被判「不稳」（其实是 F3 冒充）时，
     * 界面把点画成绿色的「到位了」，但保持计时因为 F2 不在区
     * **永远不涨** —— 用户看到「已在目标区」、进度条 0.0 秒。
     *
     * 所以豁免规则放在这里，UI 和计时都调这个函数。
     */
    fun isInZone(
        f1: Float,
        f2: Float,
        target: VowelTarget,
        f1Credible: Boolean = true,
        f2Credible: Boolean = true,
    ): Boolean {
        if (f1 <= 0f || f2 <= 0f) return false
        // F1 不可信 → 什么都不算
        if (!f1Credible) return false
        val inF1 = abs(f1 - FeminineTarget.centerF1(target)) <= FeminineTarget.radiusF1(target)
        // F2 不可信 → 只看 F1
        if (!f2Credible) return inF1
        val inF2 = abs(f2 - FeminineTarget.centerF2(target)) <= FeminineTarget.radiusF2(target)
        return inF1 && inF2
    }

    /**
     * 判断可信度时允许的偏差（音分）。
     *
     * 比区域容差宽得多 —— 这里问的不是"准不准"，
     * 而是"这个数字是不是 F2"。
     */
    const val CREDIBLE_CENTS = 300f

    /**
     * F2 的读数**可信吗**。
     *
     * ## 要解决什么问题
     *
     * 真信号里 F2 偶尔会整个消失（共振峰被噪声淹没，
     * 或者带宽超限被过滤掉）。这时按频率取第二个根，
     * 拿到的是 **F3** —— 界面上显示「F2 2407 Hz」，
     * 看着挺像回事，其实完全不对。
     *
     * 真机实测 /a/：期望 F2=1090，报出来的是 2407（那是 /a/ 的 F3）。
     *
     * ## 跟谁比
     *
     * 跟**参考位置**比，不是跟女性化目标比。
     *
     * 之前搞错了：拿女性化目标当参照，而用户现在就在参考位置 ——
     * /啊/ 的真值 F2=1090、目标 1308，差 316 音分 > 300，
     * 于是**一个完全正确的读数被判成「读数不稳」**。
     *
     * 用户会从参考位置一路往前移到目标位置，
     * 所以可信范围要**同时罩住这两头**。
     *
     * 曾经也拿「识别出的元音」当第二参照，但那个有漏洞：
     * 识别结果本身可能就是被坏读数带偏的，两个错误凑巧对上，
     * 反而把坏读数判成了可信。
     */
    fun isF2Credible(
        measuredF2: Float,
        target: VowelTarget,
    ): Boolean {
        if (measuredF2 <= 0f) return false
        // 参照位置（用户出发的地方）
        val refF2 = target.f2
        if (abs(deviationCents(measuredF2, refF2)) <= CREDIBLE_CENTS) return true
        // 目标位置（用户要到达的地方）
        val goalF2 = FeminineTarget.centerF2(target)
        return abs(deviationCents(measuredF2, goalF2)) <= CREDIBLE_CENTS
    }
}

/**
 * 元音训练进度：连续保持在目标区里多久了。
 *
 * ## 测的是"连续保持"不是"累计"
 *
 * 累计的话，用户可以断断续续地凑够时间，对训练没意义 ——
 * 稳态元音训练要的恰恰是**连续稳定地保持**。
 *
 * ## 宽限期
 *
 * 允许掉出目标区 2 帧（32 ms）。
 * LPC 偶尔一帧跳偏是常事，不该因为这一帧就前功尽弃。
 */
class TargetTracker(
    /** 每帧多少毫秒（跳帧间隔）。 */
    private val hopMs: Int = 16,
    /** 允许连续掉出多少帧而不中断保持。 */
    private val graceFrames: Int = 2,
) {
    private var holdFrames = 0
    private var missFrames = 0

    /**
     * 更新一帧。
     *
     * @param inZone 这一帧是否在目标区内
     * @return 连续保持的秒数
     */
    fun update(inZone: Boolean): Float {
        if (inZone) {
            holdFrames++
            missFrames = 0
        } else {
            missFrames++
            if (missFrames > graceFrames) {
                holdFrames = 0
                missFrames = 0
            }
        }
        return holdFrames * hopMs / 1000f
    }

    /** 清零（换目标时）。 */
    fun reset() {
        holdFrames = 0
        missFrames = 0
    }
}

/**
 * 目标区判定器 —— 用**分布**判断，而不是瞬时点。
 *
 * ## 为什么不用瞬时点
 *
 * 瞬时点比较（`abs(f1 - center) <= radius`）有两个毛病：
 *
 * 1. **单帧坏读数就断保持。** 共振峰偶尔会整个消失（被噪声淹没，
 *    或者带宽超限被过滤掉），这时取到的是 F3，看起来是个合理的数字，
 *    但完全不对。用户正在稳稳地保持目标区，计时却断了。
 *
 * 2. **起音过渡会被误判成「在区内」。** 从静音滑进元音时，
 *    F1/F2 是连续变化的，中间有几十帧落在目标区里 ——
 *    瞬时判定会说「已经到位」，但用户其实还没稳定下来。
 *
 * ## 改成什么
 *
 * 维护最近 N 帧的窗口（默认 12 帧 ≈ 190 ms），用两个条件：
 *
 * - **中位数在区内** —— 对单帧坏读数免疫（中位数不受端点影响）
 * - **在区比例达标** —— 过渡期间虽然有若干帧落进区，
 *   但比例不够，不会被算成「到位」
 *
 * ## 和 [TargetTracker] 的分工
 *
 * - [ZoneEvaluator]：**这一帧算不算在区内**（带分布的判断）
 * - [TargetTracker]：**连续保持了多少秒**（带宽限期）
 *
 * 两者都要 —— 分布判断解决「读数不可靠」，宽限期解决「短暂抖动」。
 */
class ZoneEvaluator(
    /** 窗口帧数。12 帧 × 16 ms ≈ 190 ms。 */
    private val windowFrames: Int = 12,
    /**
     * 在区比例要达到多少才算在区内。
     *
     * 0.6：窗口里六成在区才算数。
     * 起音过渡期间虽然有若干帧恰好落在区里，但比例上不去。
     */
    private val requiredDwell: Float = 0.6f,
) {
    /** 窗口里的每一帧：(在区吗, F1, F2, F2 可信吗)。 */
    private val hits = ArrayDeque<Boolean>(windowFrames)

    /** 最近一帧的 F1。 */
    private var lastF1 = 0f

    /** 最近一帧的 F2。 */
    private var lastF2 = 0f

    /** 最近一帧 F2 可信吗。 */
    private var lastF2Credible = true

    /**
     * 送入一帧，更新判定。
     *
     * @return 现在算不算在目标区内
     */
    fun update(
        f1: Float,
        f2: Float,
        target: VowelTarget,
        f1Credible: Boolean,
        f2Credible: Boolean,
    ): Boolean {
        lastF1 = f1
        lastF2 = f2
        lastF2Credible = f2Credible

        val hit = VowelZone.isInZone(f1, f2, target, f1Credible, f2Credible)
        if (hits.size >= windowFrames) hits.removeFirst()
        hits.addLast(hit)
        return evaluate()
    }

    /**
     * 只做分布判断（不再收新帧）。
     *
     * 窗口没满时**不判定** —— 几帧的样本说明不了什么。
     */
    private fun evaluate(): Boolean {
        if (hits.size < windowFrames) return false
        val inCount = hits.count { it }
        return inCount.toFloat() / windowFrames >= requiredDwell
    }

    /**
     * 当前这一帧**单独**看在不在区内。
     *
     * UI 画点用这个 —— 点要跟着读数实时动，
     * 不能等窗口填满才更新。
     */
    fun currentHit(): Boolean {
        val t = currentTarget ?: return false
        return VowelZone.isInZone(lastF1, lastF2, t, true, lastF2Credible)
    }

    /** 当前目标（供 [currentHit] 用）。 */
    var currentTarget: VowelTarget? = null
        private set

    /** 设置目标。切目标时窗口要清空 —— 旧目标的分布对新目标没意义。 */
    fun setTarget(t: VowelTarget?) {
        if (t != currentTarget) {
            currentTarget = t
            reset()
        }
    }

    /** 清空窗口。 */
    fun reset() {
        hits.clear()
        lastF1 = 0f
        lastF2 = 0f
        lastF2Credible = true
    }
}
