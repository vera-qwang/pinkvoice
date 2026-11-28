package com.vera.pinkvoice.audio

/**
 * 共振峰分析的状态快照（给 UI 订阅）。
 *
 * 用一个不可变的数据类打包，而不是拆成好几个 StateFlow ——
 * 界面需要的是"这一瞬间的一致快照"，拆开会出现
 * F1 是新的、稳态度还是旧的这种撕裂。
 */
data class QualityState(
    /** 原始频率的共振峰。 */
    val formants: LpcAnalyzer.Formants = LpcAnalyzer.Formants(0f, 0f, 0f),
    /** 当前数据是否有效（有没有在发声）。 */
    val valid: Boolean = false,
) {
    companion object {
        val EMPTY = QualityState()
    }
}
