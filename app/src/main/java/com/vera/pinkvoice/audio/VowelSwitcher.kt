package com.vera.pinkvoice.audio

/**
 * 元音识别的**迟滞开关**。
 *
 * ## 为什么需要
 *
 * 平滑后的 F1/F2 落在两个元音的分界附近时，识别结果会来回摆。
 *
 * 实测五段真人录音，每个都是持续 2–3 秒的单个元音，
 * 标签却切换了这么多次：
 *
 * | 录音 | A | E | I | O | U |
 * |---|---|---|---|---|---|
 * | 切换次数 | 9 | 6 | **15** | 3 | 9 |
 *
 * `/i/` 每秒变 5 次 —— 界面上看起来就是「跳」。
 *
 * ## 为什么不能只数帧数
 *
 * 先试过「连续 5 帧指向新元音才切」，切换只从 15 次降到 13 次。
 * 因为摆动的**每一段都超过 5 帧** —— 平滑后的点是在分界附近
 * 缓慢地成段来回摆，不是单帧抖动。
 *
 * ## 所以加距离裕度
 *
 * 只有满足两个条件才切换：
 * 1. 新候选比当前元音**近至少 [marginCents] 音分**
 * 2. 并且连续 [switchFrames] 帧都是它
 *
 * 点只要还在当前元音附近摆动，就永远不切 —— 这是「稳定优先」。
 * 用户真的换了元音时，新元音会明显更近，两个条件都满足，切得也快。
 */
class VowelSwitcher(
    /** 连续多少帧指向同一个新元音才切换。5 帧 ≈ 80 ms。 */
    private val switchFrames: Int = 5,

    /** 连续多少帧识别不出来才清空。8 帧 ≈ 130 ms。 */
    private val gapFrames: Int = 8,

    /**
     * 距离裕度（音分）。
     *
     * 250 音分 ≈ 15% 的频差。
     *
     * 原来是 60 音分，实测**不够**：现场数据里用户发一个持续的「啊」，
     * 标签在 4.6 秒内切了 6 次（啊→诶→哎→空→乌→啊）。
     *
     * 原因是实测点 (F1≈527, F2≈1354) 正好卡在「啊」(730/1090) 和
     * 「诶」(530/1840) **中间** —— 谁都不明显更近，F1 飘几十 Hz
     * 最近邻就翻。250 音分要求「新元音明显更近」才切。
     */
    private val marginCents: Float = 250f,
) {
    private var current: VowelTarget? = null
    private var candidate: VowelTarget? = null
    private var count = 0
    private var blankRun = 0

    fun reset() {
        current = null
        candidate = null
        count = 0
        blankRun = 0
    }

    /** 当前稳定下来的元音。 */
    val stable: VowelTarget? get() = current

    /**
     * 输入本帧的识别结果。
     *
     * @param f1 平滑后的 F1（用来算到当前元音的距离）
     * @param f2 平滑后的 F2
     * @param recognized 本帧最近的那个元音
     */
    fun update(f1: Float, f2: Float, recognized: VowelTarget?): VowelTarget? {
        if (recognized == null) {
            blankRun++
            if (blankRun > gapFrames) reset()
            return current
        }
        blankRun = 0

        if (recognized == current) {
            candidate = null
            count = 0
            return current
        }

        // 裕度检查：新候选得比当前元音明显更近，才值得考虑
        val toCurrent = current?.let { VowelRecognizer.distanceTo(it, f1, f2) }
            ?: Float.MAX_VALUE
        val toCandidate = VowelRecognizer.distanceTo(recognized, f1, f2)
        if (toCandidate + marginCents > toCurrent) {
            // 还在当前元音的地盘上 → 不切
            candidate = null
            count = 0
            return current
        }

        if (recognized == candidate) {
            count++
        } else {
            candidate = recognized
            count = 1
        }
        if (count >= switchFrames) {
            current = recognized
            candidate = null
            count = 0
        }
        return current
    }
}
