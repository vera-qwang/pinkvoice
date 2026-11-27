package com.vera.pinkvoice.audio

/**
 * 质量分析的状态快照（给 UI 订阅）。
 *
 * 用一个不可变的数据类打包，而不是拆成好几个 StateFlow ——
 * 界面需要的是"这一瞬间的一致快照"，拆开会出现
 * F1 是新的、稳态度还是旧的这种撕裂。
 */
data class QualityState(
    /** 原始频率的共振峰。 */
    val formants: LpcAnalyzer.Formants = LpcAnalyzer.Formants(0f, 0f, 0f),
    /** 识别出的元音。 */
    val recognized: VowelTarget? = null,
    /** 到识别元音的距离。 */
    val recognizedDistance: Float = Float.MAX_VALUE,
    /** 当前数据是否有效（有没有在发声）。 */
    val valid: Boolean = false,
    /** 连续保持在目标区里的秒数。 */
    val holdSeconds: Float = 0f,
    /**
     * 现在算不算在目标区里（**分布判定**的结果）。
     *
     * UI 必须读这个，不能自己算 —— 之前 UI 和保持计时各判各的，
     * 结果点画成绿色「到位了」而进度条永远 0.0 秒，自相矛盾。
     *
     * 判定用最近 12 帧的中位数 + 在区比例，不是单帧跟中心点比。
     */
    val inZone: Boolean = false,
) {
    companion object {
        val EMPTY = QualityState()
    }
}
