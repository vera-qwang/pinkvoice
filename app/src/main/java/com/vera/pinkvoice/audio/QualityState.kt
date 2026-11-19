package com.vera.pinkvoice.audio

/**
 * 质量分析的状态快照（给 UI 订阅）。
 *
 * 用一个不可变的数据类打包，而不是拆成好几个 StateFlow ——
 * 界面需要的是"这一瞬间的一致快照"，拆开会出现
 * F1 是新的、稳态度还是旧的这种撕裂。
 */
data class QualityState(
    /** 归一化后的共振峰。 */
    val formants: LpcAnalyzer.Formants = LpcAnalyzer.Formants(0f, 0f, 0f),
    /** 谱倾斜（dB/oct）。 */
    val tilt: Float = 0f,
    /** 识别出的元音。 */
    val recognized: VowelTarget? = null,
    /** 到识别元音的距离。 */
    val recognizedDistance: Float = Float.MAX_VALUE,
    /** 稳态度 0..1。 */
    val stability: Float = 0f,
    /** 音色轴读数。 */
    val timbre: TimbreAxis.Reading = TimbreAxis.Reading(0f, 0f, 0f, false),
    /** 当前数据是否有效（有没有在发声）。 */
    val valid: Boolean = false,
    /** 基准录制进度（0..1）。 */
    val baselineProgress: Float = 0f,
    /** 是否正在录基准。 */
    val capturingBaseline: Boolean = false,
) {
    companion object {
        val EMPTY = QualityState()
    }
}
